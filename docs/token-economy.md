# Token Economy — spend grok, save Claude

How to get work done while using as few Claude tokens as possible, by delegating to
grok CLI sub-agents. See [`grok-fleet.md`](grok-fleet.md) for the fan-out mechanics.

## Where Claude tokens actually go

Measured from a real session transcript (295 turns):

| Component | Amount |
|---|---|
| `cache_read_input_tokens` | **36,359,748** |
| `output_tokens` (Claude generating) | **366,110** |
| tool results entering context | 110 KB ≈ **27,588 tokens** |

**The intuitive optimization is the wrong one.** Shortening tool output attacks the
smallest line item. The real costs are:

1. **Turns × resident context** — 36.4M cache reads over 295 turns ≈ **123K tokens
   re-read every turn**. An extra Claude turn costs roughly a full context replay.
2. **Claude's own authoring** — 366K output tokens writing code, docs, and prompts.
3. **Resident context** — a file read once is re-read on every later turn.

So: **collapse turns, stop authoring, keep context small.**

## Measured result

Same benchmark (audit 3 skill files for frontmatter and list-numbering drift), done
by Claude alone vs delegated to grok:

| Metric | Claude-only | Delegated | Cut |
|---|---|---|---|
| Turns | 11 | 4 | **−64%** |
| `output_tokens` | 7,514 | 2,657 | **−65%** |
| tool-result bytes | 14,096 | 465 | **−97%** |
| billable tokens | 38,300 | 5,427 | **−86%** |

Quality held: grok found all three defects, with more precise line references than the
Claude-only pass. Verified semantically by `scripts/check-parity.sh`.

## The three rules

### 1. Collapse turns — dispatch once, block, read one summary

```bash
# one turn: dispatch N agents, wait, get a compact summary
bash scripts/grok-fleet.sh spawn --manifest run.json
bash scripts/grok-fleet.sh collect --run-id RUN --brief
```

Prefer **one 8-agent manifest over eight single spawns**. Do not poll in a loop — each
poll is a turn, and each turn re-reads the whole context.

### 2. Stop authoring — script the dispatch, template the prompts

Claude writing a long inline bash block to launch a fan-out is itself a top-two cost. In
our own benchmark this was the difference between a 28% and a 65% cut. Encapsulate it:

```bash
bash scripts/bench-audit.sh RUN file1 file2 file3      # one line, not 30
bash scripts/mk-task.sh audit out.md TARGET=… CHECKS=… # template, not prose
```

Let grok author drafts (code, docs, findings) and have Claude review the **diff**.

### 3. Keep context small — grok reads, Claude decides

- Never read a large file into Claude just to describe it to grok — **pass the path**.
- Prefer counts and paths (`rg -c`) over content when Claude only needs to decide.
- Read a specific range, not a whole file, when a decision needs one detail.
- Route anything long through a grok **reducer** agent first.

**Claude reads verdicts, not artifacts.** Open a full `output/` file only when a verdict
says FAIL and the failure needs diagnosis.

## Routing

**Always grok** — file reading and exploration, breadth research, per-file mechanical
transforms, test generation, first drafts, N-way exploration, cross-model review, and
reduction/summarization.

**Must stay Claude** — final integration and merge decisions, security-sensitive changes,
ambiguous requirements needing the user's intent, writes to the real working tree, and
adjudicating conflicting grok results.

Free execution is not free judgment.

## When grok is unmetered

If grok spend is not billed, set `GROK_BUDGET_USD=0` (unlimited — cost is still tracked
and reported, it just never halts a run) and delegate far more aggressively:

- **Send generous context every time.** Re-explaining the project to grok is free.
- **`--check`** (on by default) makes grok self-verify, so an error costs a free grok
  pass instead of a Claude review turn. *Disabled automatically with `--json-schema`,
  where the extra verification turn would displace the structured output.*
- **`--best-of-n`** (`GROK_BEST_OF_N`) runs a task N ways and keeps the best.
- **Retry on the grok side** (`GROK_MAX_ATTEMPTS`, default 3) — Claude sees terminal
  states, never intermediate attempts. Deliberate stops are never retried.
- **Delegate marginal cases** — the downside is free, the upside is a saved Claude turn.

## Measuring it yourself

```bash
bash scripts/token-audit.sh mark before
# ... do the work ...
bash scripts/token-audit.sh measure before     # turns, output tokens, context bytes
bash scripts/token-audit.sh gates --json       # evaluate the exit-criteria gates
```

The audit parses the live Claude Code session transcript, so the numbers are real usage,
not estimates.

## Watching the split live

The statusline shows Claude and grok agent counts separately:

```
drom-flow v0.7.0 • repo • main • 10h46m • edits:231 • C:0 G:8 • mem:on
```

`C:` is Claude sub-agents (from the track-agents hook), `G:` is grok agents currently
`RUNNING` in the fleet control plane. A healthy delegated run looks like `C:0 G:N`.

## Running out of Claude tokens: the wake-up loop

Claude Code exposes **no live quota meter** — this was verified, not assumed. The transcript
carries only per-turn usage, and `~/.claude/stats-cache.json` holds stale lifetime totals. So a
"97% used" figure cannot be *read*; it can only be *estimated*. The watcher is built around that
honestly, with two triggers:

| Trigger | Basis | Accuracy |
|---|---|---|
| **Definitive** | the limit event itself — a synthetic transcript message: `You've hit your session limit · resets 9:50pm` | exact, including the reset time |
| **Predictive (97%)** | billable tokens this window ÷ a **learned** budget | estimate; suppressed when untrustworthy |

```bash
bash scripts/limit-watch.sh status     # window usage, budget, percent, reset time
bash scripts/limit-watch.sh check      # hook entry point: arm if at/over threshold
bash scripts/limit-watch.sh ping       # wake-up: still blocked? re-arm : resume
```

### How the budget is learned

Each completed window (reset boundary → next limit event) is an observation; the budget is their
rolling median. Two corrections matter:

- **Clustered events are discarded.** Once a window is exhausted, every further attempt logs another
  limit event minutes later. Those gaps are re-hits, not windows — counting them yielded a budget of
  272K against a window that had actually spent 2.75M. Gaps under 30 minutes are ignored.
- **The in-flight window is a lower bound.** Spending N tokens without being limited proves the budget
  exceeds N, so a learned value below N is rejected outright.

Until there are three real observations the budget is marked `low-bounded`, `percent` is reported as
`null` rather than a fabricated number, and **the 97% trigger is suppressed** — only the definitive
event fires. Set `CLAUDE_TOKEN_BUDGET` to supply a known limit and skip the learning phase.

`billable` deliberately **excludes `cache_read_input_tokens`**: it dominates raw counts (36M in one
session) but is not what exhausts a session limit.

### What arming does

1. Checkpoints every in-progress fleet run (`RESUME.md`, ~227 B)
2. Hands queued work to detached `drain`, so **grok keeps working while Claude is blocked**
3. Starts an hourly ping timer (detached — survives the session ending)
4. Records `.claude/.state/limit-armed.json`; arming is idempotent per window, so schedules never stack

On each ping: still before the reset → re-arm; past it → resume via `grok-fleet.sh resume`. Capped at
`LIMIT_WATCH_MAX_PINGS` (default 12), then auto-disarms.

The check runs in the **PostToolUse hook** — outside Claude's context, ~0.2s, **zero Claude tokens**.
The statusline shows `⏳armed` or `⏳ping-due`.

| Var | Default | Purpose |
|---|---|---|
| `LIMIT_WATCH_PCT` | 97 | predictive threshold |
| `LIMIT_WATCH_INTERVAL` | 3600 | ping interval (seconds) |
| `LIMIT_WATCH_MAX_PINGS` | 12 | give-up cap |
| `CLAUDE_TOKEN_BUDGET` | — | explicit budget override |

## Surviving Claude running out mid-task

Claude is the interruptible component; grok is not. See [`grok-fleet.md`](grok-fleet.md).

```bash
bash scripts/grok-fleet.sh drain --manifest run.json   # detached; finishes without Claude
bash scripts/grok-fleet.sh checkpoint --run-id RUN     # RESUME.md, hard-capped at 2 KB
bash scripts/grok-fleet.sh resume --run-id RUN         # reconcile + finish what's left
```

`resume` trusts on-disk results over recorded state: an agent whose process is gone but
whose result is complete is marked `DONE`; one with neither is `INTERRUPTED` and redone.
Finished units are never re-run. Resuming costs a **~230-byte** read, not a re-explanation.
