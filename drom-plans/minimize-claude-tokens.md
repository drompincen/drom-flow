---
title: Minimize Claude Token Usage, Maximize Grok Delegation
status: pending
created: 2026-08-01
updated: 2026-08-01
current_chapter: 1
loop: true
check_command: bash scripts/token-audit.sh --benchmark --json
pass_condition: all seven gates PASS in reports/token-audit.json
max_iterations: 10
---

# Plan: Minimize Claude Tokens, Maximize Grok Reliance

Shift execution off Claude and onto grok sub-agents, so Claude acts as a **thin controller** — deciding,
dispatching, and adjudicating — while grok does the reading, writing, and producing. Success is measured
in Claude tokens per unit of delivered work, not in vibes.

Built on the fleet from [`grok-subagent-fleet.md`](grok-subagent-fleet.md), which is complete and green.

---

## Measured baseline — where Claude tokens ACTUALLY go

Taken from this project's own session transcript (`~/.claude/projects/…/<session>.jsonl`, which records
per-turn `input_tokens`, `output_tokens`, `cache_read_input_tokens`). One real session, 295 turns:

| Component | Amount | Share |
|---|---|---|
| `cache_read_input_tokens` | **36,359,748** | dominant |
| `output_tokens` (Claude generating) | **366,110** | second |
| `tool_result` bytes entering context | 110,355 B ≈ **27,588 tokens** | minor |
| fresh `input_tokens` | 532 | negligible |

**The obvious optimization is the wrong one.** Trimming tool output attacks ~27K tokens while the real
costs are elsewhere:

1. **Turn count × resident context.** 36.36M cache reads over 295 turns ≈ **123K tokens re-read per
   turn**. Every extra Claude turn costs roughly a full context replay. *Fewer, larger delegations beat
   many small ones.*
2. **Claude's own authoring.** 366K output tokens — Claude writing code, docs, plans, and task prompts.
   *Whatever grok can author, grok should author.*
3. **Resident context size.** What Claude has read stays in context and is re-read every turn, so a
   large file read once is paid for on every subsequent turn. *Grok reads files; Claude reads verdicts.*

Design follows directly: **collapse turns, stop authoring, keep context small.**

---

## EXIT CRITERIA (loop pass condition)

`scripts/token-audit.sh --benchmark` runs a fixed benchmark task two ways — Claude-only vs
grok-delegated — and writes `reports/token-audit.json`.

| # | Gate | Criterion |
|---|---|---|
| 1 | `measurable` | Audit harness parses the live transcript and reports turns, output tokens, cache reads, tool-result bytes for a bounded run |
| 2 | `delegation` | **≥95%** of executed work-units run on grok (`engine=grok` / total) — grok is unmetered here, so the bar is high |
| 3 | `turns` | Claude turns for the benchmark task cut **≥50%** vs baseline |
| 4 | `authoring` | Claude `output_tokens` for the benchmark cut **≥50%** vs baseline |
| 5 | `context` | Total `tool_result` bytes into Claude context per fan-out run **≤4 KB** (brief mode) |
| 6 | `parity` | Quality holds: benchmark output still correct, and `grok-fleet.sh verify` still 6/6 |
| 7 | `resume` | Claude killed mid-run (simulating token exhaustion) → grok work continues to completion, and a **cold** Claude session resumes from ≤2 KB of state with **no unit re-run and none lost** |
| 8 | `ship` | Work is committed and pushed in drom-flow, and the **catsandbears installation is refreshed** to the new version and re-verified live there |

Gate 6 is the guard against the degenerate solution — you can always cut tokens by doing less work.

**Max iterations: 10.** Per iteration capture: each gate, turns, output tokens, delegation ratio, grok $ spent.

---

## Chapter 1: Measurement harness
**Status:** pending
**Depends on:** none

No optimization without a metric.

- [ ] `scripts/token-audit.sh` — locate the active transcript (`~/.claude/projects/<slug>/<session>.jsonl`), parse per-turn usage
- [ ] `--since <marker>` / `--window N` to bound a measurement to one task rather than a whole session
- [ ] Report: turns, `output_tokens`, `cache_read_input_tokens`, `tool_result` bytes, and **tokens-per-work-unit**
- [ ] `--benchmark` runs the fixed benchmark both ways and emits the comparison to `reports/token-audit.json`
- [ ] Define the benchmark task — fixed, repeatable, real: *audit every `template/.claude/skills/*/​*.md` for frontmatter drift and produce one findings report*. Large enough to matter, verifiable by content
- [ ] Record the Claude-only baseline for that benchmark (turns, output tokens, context bytes, wall clock)

## Chapter 2: Thin-controller protocol — collapse turns
**Status:** pending
**Depends on:** Chapter 1

Attacks cost driver #1 (turn count × context).

- [ ] `grok-fleet.sh spawn --manifest --block` — one Claude turn dispatches N agents, waits, and returns a single compact summary. Today's poll-in-a-loop pattern costs a turn per check
- [ ] `collect --brief` — emit only `agent | state | verdict | one-line summary | cost` (target ≤4 KB total), never raw agent output. Full output stays on disk, read only on demand
- [ ] `status --brief` — one line per agent, no `PROGRESS.md` bodies
- [ ] Document the rule in the skill: **Claude reads verdicts, not artifacts.** Open a full `output/` file only when a verdict says FAIL and the failure needs diagnosis
- [ ] Batching guidance: prefer one 8-agent manifest over eight single spawns — same work, one turn instead of eight
- [ ] **Statusline split counters**: extend `.claude/hooks/statusline.sh` to show **Claude agents and grok agents as separate counters** at the bottom of the window (e.g. `C:2 G:8`), reading Claude agent count from the existing `track-agents.sh` state and grok count from live `RUNNING` agents under `.claude/.grok-fleet/*/agents/*/status.json`. Makes the delegation ratio visible at a glance while work is in flight — free to read, zero Claude tokens

## Chapter 3: Stop Claude authoring — grok writes, Claude reviews
**Status:** pending
**Depends on:** Chapter 2

Attacks cost driver #2 (366K output tokens).

- [ ] **Task-file templating**: `scripts/mk-task.sh <template> KEY=VAL …` generates `task.md` from a parameterized template, so dispatching N units costs Claude a command, not N prose prompts
- [ ] Ship templates for the recurring shapes: `audit`, `transform`, `generate-tests`, `research`, `review`
- [ ] **Grok-authored drafts**: for code, docs, and plan drafts, grok produces the artifact and Claude reviews the **diff** rather than writing the content
- [ ] **Grok-side reducer**: add a final `reducer` agent to a fan-out whose job is to merge the other agents' `output/` into one small artifact. Claude then reads one summary instead of N outputs — this replaces the manual merge that gate 5 of the fleet plan does today
- [ ] Reducer output constrained by `--json-schema` so it stays compact and parseable
- [ ] **`--check` on by default** for fleet agents — grok self-verifies before returning, so a wrong answer costs a free grok pass instead of a Claude review turn
- [ ] **`--best-of-n`** wired into `spawn` for units whose output Claude would otherwise correct (drafts, generated code, reductions); free on an unmetered account
- [ ] **Grok-side retry-until-pass**: on `FAILED`, re-dispatch to grok with the failure text appended, up to `GROK_MAX_ATTEMPTS` (default 3), before surfacing anything to Claude. Claude sees terminal states, never intermediate attempts
- [ ] **Redundant review pass**: a second grok agent reviews the first's output with a schema verdict, replacing the Claude review turn entirely

## Chapter 4: Delegation router — grok by default
**Status:** pending
**Depends on:** Chapter 3

- [ ] Flip the default: work is **grok's unless it needs Claude**. Encode the exception list rather than the delegation list
- [ ] **Must stay Claude** (do not delegate): final integration and merge decisions; security-sensitive changes; ambiguous or under-specified requirements needing user judgment; anything touching the real working tree; adjudicating conflicting grok results
- [ ] **Always grok**: file reading and exploration, breadth research, per-file mechanical transforms, test generation, first drafts, N-way exploration, cross-model review, reduction/summarization
- [ ] `scripts/route.sh <unit-spec>` → emits `engine: claude|grok` + rationale, so routing is mechanical and auditable rather than ad-hoc
- [ ] Update `/planner` to default `engine: grok` and require a stated reason for any `engine: claude` unit
- [ ] Update `/grok-fleet` and `/orchestrator` skills with the thin-controller rules
- [ ] **Escape hatch**: `GROK_DELEGATE=off` restores Claude-only execution when grok is unavailable or a task proves undelegatable

## Chapter 5: Context hygiene
**Status:** pending
**Depends on:** Chapter 4

Attacks cost driver #3 (resident context is re-read every turn).

- [ ] Rule: **never read a large file into Claude to describe it to grok** — pass the path; grok reads it
- [ ] Prefer `rg -c` / counts / paths over content dumps when Claude only needs to decide
- [ ] Cap incidental reads: when a file is only needed for a decision, read the specific range, not the file
- [ ] Where a long artifact must inform Claude, route it through a grok reducer first
- [ ] Document the anti-patterns in `docs/token-economy.md` with the measured numbers behind each

## Chapter 6: Survive Claude token exhaustion — grok keeps working, Claude resumes clean
**Status:** pending
**Depends on:** Chapter 5

Claude tokens are finite here and grok's are not. The system must therefore treat **Claude as the
interruptible component**: when Claude runs out mid-task, in-flight grok work should finish on its own,
and a fresh Claude session should pick up exactly where the last one stopped — cheaply.

**Durable state already exists** (per-agent `status.json`, `result.json`, `output/`, and idempotent
`spawn` that skips finished agents). What is missing is (a) a driver that isn't Claude, (b) a compact
resume record, and (c) crash-safe writes.

- [ ] **Detached drain runner**: `grok-fleet.sh drain --manifest M` processes the queue to completion in the background, detached (`nohup`/`disown`) so it survives the Claude session ending. Claude dispatches and can then die without stranding the run
- [ ] **Crash-safe state**: `set_status` currently writes `status.json` in place — a kill mid-write truncates it and the unit becomes unreadable. Switch to write-temp-then-`mv` (atomic rename) for `status.json`, `result.json`, and the resume record
- [ ] **`grok-fleet.sh checkpoint --run-id R`** → writes `RESUME.md`, hard-capped at **2 KB**: goal, plan file + current chapter, units done / running / pending, last decision, next action, blockers. This is the *entire* cost of resuming, so it must stay tiny
- [ ] Checkpoint automatically after each unit reaches a terminal state, so the record is never stale
- [ ] **`grok-fleet.sh resume --run-id R`** — reconciles reality against the record: marks agents whose process is gone but whose `result.json` is complete as `DONE`, re-dispatches only genuinely incomplete units, never re-runs finished ones (already proven: idempotent resume, no re-bill)
- [ ] **Orphan reconciliation on resume** — an agent left `RUNNING` with no live PID and no result is `INTERRUPTED`, and is re-dispatched rather than trusted
- [ ] Extend the `memory-sync` session-start hook to surface **in-progress fleet runs** alongside in-progress plans, so a cold session is told immediately that a run is resumable
- [ ] Plan-level continuity: chapter checkboxes in `drom-plans/` remain the coarse resume point; `RESUME.md` is the fine-grained one. The two must agree — `checkpoint` writes the plan file and chapter it belongs to
- [ ] **Verification (gate 7)**: start an 8-unit run, kill the Claude process mid-flight, confirm the detached runner finishes the remaining units; then from a **cold** session run `resume`, and assert every unit is accounted for exactly once — none re-run, none lost — with total resume context ≤2 KB

## Chapter 8: Ship (gate 8)
**Status:** pending
**Depends on:** Chapter 7

- [ ] Sync `scripts/` → `template/scripts/`, re-embed every changed script in `SCRIPTS.md`, round-trip verify byte-for-byte
- [ ] Bump `VERSION`; register any new skill/script in `CLAUDE.md`, `template/CLAUDE.md`, `README.md`
- [ ] Commit and push drom-flow
- [ ] **Refresh catsandbears**: `init.sh --update`, then re-verify live there (`doctor --live` + a real fan-out)
- [ ] Commit and push catsandbears, staging only drom-flow-owned paths (its WIP stays untouched)

## Chapter 7: Run the loop to green
**Status:** pending
**Depends on:** Chapter 6

- [ ] Iteration 0: run `--benchmark`, record all seven gates
- [ ] Group failing gates by cause (turns / authoring / context / delegation) and fix in parallel
- [ ] Re-run; improved → continue; **regression (more gates failing, or gate 6 parity lost) → revert immediately** and try another approach
- [ ] Log each iteration to `context/MEMORY.md`: gates, turns, output tokens, delegation ratio, grok $
- [ ] On exit: final summary to `context/MEMORY.md`; routing decision to `context/DECISIONS.md`; set `status: completed`

---

## Economics: grok is unmetered on this account

The user has **unlimited grok**. That removes the usual counterweight and changes the strategy:
Claude tokens are the only scarce resource, so **grok inefficiency is free** and worth spending
liberally to save even small amounts of Claude work.

What this unlocks — all previously uneconomical:

- **Re-establish context freely.** The old caveat "delegation loses when a unit needs deep project
  context, because re-explaining costs more" **no longer applies**. Feed grok the whole file, the
  whole directory, the whole spec, every time. Verbose prompts are free.
- **`--best-of-n`** — the CLI runs a task N ways in parallel and picks the best (headless only).
  Free quality improvement; use it for anything whose output Claude would otherwise have to fix.
- **`--check`** — appends a self-verification loop to the prompt. Make it the default for fleet
  agents so grok catches its own errors instead of spending a Claude turn on review.
- **Retry-until-pass on the grok side.** A failed unit re-runs on grok with the failure appended,
  up to N attempts, before it ever surfaces to Claude. Claude sees final states, not attempts.
- **Redundant cross-checking.** Run a second grok agent to review the first's output. Two grok
  passes cost nothing and remove a Claude review turn.
- **Delegate marginal cases.** When it is unclear whether a unit is worth delegating, delegate it —
  the downside is free and the upside is a saved Claude turn.

**Budget guard becomes a safety rail, not a cost control.** `GROK_BUDGET_USD=0` now means unlimited
(implemented: cost is still tracked and reported, it just never halts a run). Set it to `0` in these
projects; keep the wall-clock timeout and `max_turns` as the real runaway protection, since an
unmetered account still has finite concurrency and time.

### What still must not be delegated

Free execution is not free judgment. Unchanged:

- Final integration and merge decisions
- Security-sensitive changes
- Ambiguous requirements needing the user's intent
- Writes to the real working tree
- Adjudicating conflicting grok results

Two failure modes the gates deliberately guard:

- **Doing less work to score better** — gate 6 requires the benchmark output to still be correct.
- **Delegating judgment** — the exception list above is explicit and enforced by the router.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Grok lacks project context → worse output | Context is free to supply — send whole files/specs every time; `--check` and a redundant review pass catch errors; parity gate is the backstop |
| Round-trips to grok add wall-clock latency | Prefer batched manifests; 8 agents complete in ~13s |
| Reducer loses detail Claude needed | Full outputs stay on disk; verdicts link to paths for drill-down |
| Resume record drifts from reality | `checkpoint` rewrites after every terminal unit; `resume` reconciles against on-disk results rather than trusting the record |
| Transcript format changes, breaking the audit | Harness fails loudly rather than reporting zeros; fields validated on parse |
| Cheaper-looking but costlier overall | Report grok $ alongside Claude tokens every iteration |
| Over-delegation of judgment calls | Explicit "must stay Claude" list; `GROK_DELEGATE=off` escape hatch |
