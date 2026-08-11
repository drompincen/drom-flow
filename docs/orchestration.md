---
title: Claude orchestrator, grok workers
nav_order: 2
---

# Claude orchestrator, grok workers

The core idea in drom-flow: **Claude decides, grok does.**

Claude is a good orchestrator and an expensive worker. Grok CLI sub-agents are cheap, parallel, and
have their own context. So Claude plans, dispatches, and adjudicates; grok reads files, fetches the
web, drafts, and critiques. Claude never sees the raw material — only verdicts.

This is not an aesthetic preference. It comes out of measurement.

---

## Why: context size is the bill

Measured from a real Claude Code session transcript:

| Component | Tokens |
|---|---|
| `cache_creation` | **6,493,045** |
| Claude `output_tokens` | 663,313 |
| All tool results entering context | ~42,000 |
| **Total billable** | **~7.16M** |

The dominant term is `cache_creation`, and it scales with **resident context size**. Every time the
cache is re-written you pay for the *entire* current context. From the same log:

```
237,576  18:25:37  <- TaskUpdate()
237,576  18:25:37  <- TaskUpdate()
237,576  18:25:38  <- TaskUpdate()
237,576  18:25:38  <- TaskUpdate()
```

Four turns inside two seconds, **~950,000 tokens**. The work was four trivial status updates. They
cost a quarter-million tokens each because that is what the context weighed.

The obvious optimisation — shortening tool output — targets the smallest line item. The real levers
are: **collapse turns, stop authoring, keep context small.**

### What delegation actually buys

Same benchmark task (audit three skill files), run by Claude alone vs delegated to grok:

| Metric | Claude-only | Delegated | Cut |
|---|---|---|---|
| Turns | 11 | 4 | **−64%** |
| Claude `output_tokens` | 7,514 | 2,657 | **−65%** |
| Context bytes | 14,096 | 465 | **−97%** |
| Billable tokens | 38,300 | 5,427 | **−86%** |

Quality held: grok found every defect, with more precise line references than the Claude-only pass.

---

## The contract: the filesystem is the control plane

A unit of work is a directory. Task in, results out.

```
.claude/.grok-fleet/<run-id>/
  agents/<agent-id>/
    task.md        the prompt          cmd.txt      exact command (reproducible)
    status.json    QUEUED|RUNNING|DONE|FAILED|STOPPED|TIMEOUT|STALLED
    PROGRESS.md    agent-written checkpoints — the real progress signal
    stream.jsonl   raw streaming output; mtime = liveness heartbeat
    result.json    final event + structured verdict
    output/        the work product (the agent's cwd)
```

This buys three things a daemon would not:

- **Model-agnostic** — Claude sub-agents and grok agents satisfy the same contract, so units are routable to either.
- **Survives everything** — context compaction, session restart, crash. Nothing lives only in memory.
- **Inspectable** — `cat` any agent's progress without special tooling.

---

## Routing: who gets the work

**Always grok**

- Reading files and exploring a codebase
- Breadth research and web fetching
- Per-file mechanical transforms
- Test generation, first drafts
- N-way exploration
- Cross-model review and reduction/summarisation

**Must stay Claude**

- Final integration and merge decisions
- Security-sensitive changes
- Ambiguous requirements needing the user's intent
- Any write to the real working tree
- Adjudicating conflicting grok results

**Hard rule:** never let both engines write the same files in one phase. Grok writes only inside its
own `output/`; Claude reads those and integrates. That is what makes a parallel fan-out safe.

Free execution is not free judgment.

---

## Lifecycle

```
   Claude                          fleet                         grok workers
     |                               |                                 |
     |-- spawn --manifest ---------->|                                 |
     |                               |-- concurrency gate (max 4-8) -->|
     |                               |                            [ task.md ]
     |                               |<-- PROGRESS.md checkpoints -----|
     |                               |<-- stream.jsonl (heartbeat) ----|
     |                               |                                 |
     |   (stall detection: stream idle > 180s -> STALLED)              |
     |                               |                                 |
     |-- stop --all (optional) ----->|-- STOP -> kill -> taskkill ---->X
     |                               |                                 |
     |<-- collect --brief -----------|<-- result.json + output/ -------|
     |   (verdict lines only, <=4KB)
```

Verified: **8 concurrent agents complete in 13s** with no degradation.

## Worker anatomy

Every task gets a **fleet preamble** injected automatically. It tells the agent to write
`PROGRESS.md` checkpoints, keep output inside its working directory, and end with a `RESULT:` line.

Because grok is often unmetered, several quality features are effectively free:

| Feature | Effect |
|---|---|
| `--check` | The worker self-verifies before returning, so a wrong answer costs a free grok pass instead of a Claude review turn. **Disabled automatically with `--json-schema`**, where the extra turn displaces structured output |
| `--best-of-n` | Runs the task N ways in parallel, keeps the best |
| `GROK_MAX_ATTEMPTS` | Retries a failed unit on the grok side with the failure appended. Claude sees terminal states, never attempts |
| Deliberate stops | **Never** retried — otherwise `stop` would be defeated by the retry relaunching the agent |

## Reading results cheaply

```bash
bash scripts/grok-fleet.sh collect --run-id RUN --brief
```

Returns `agent | state | one-line summary` — target ≤4 KB for a whole run. Full artifacts stay on
disk. **Open an agent's `output/` only when a verdict says FAIL and the failure needs diagnosis.**

The rule that matters: **Claude reads verdicts, not artifacts.**

---

## When Claude runs out of tokens

Claude tokens are finite; grok's may not be. drom-flow therefore treats **Claude as the interruptible
component**.

```bash
bash scripts/grok-fleet.sh drain --manifest run.json   # detached; finishes without Claude
bash scripts/limit-watch.sh status                     # window usage, budget, reset time
bash scripts/grok-fleet.sh resume --run-id RUN         # pick up exactly where it stopped
```

There are **two triggers**, and being honest about the difference matters:

| Trigger | Basis | Accuracy |
|---|---|---|
| **Definitive** | the limit event itself — a transcript message carrying the reset time | exact |
| **Predictive (97%)** | billable tokens ÷ a *learned* budget | estimate — **suppressed until trustworthy** |

Claude Code exposes **no live quota meter**. This was verified, not assumed: transcripts carry only
per-turn usage, and `stats-cache.json` holds stale lifetime totals. So the percentage is estimated
against a budget learned from past limit events, and while that budget is only a lower bound the
watcher reports `percent: null` rather than inventing a number.

On trigger it checkpoints in-flight runs, hands queued work to detached grok agents that keep running
while you are blocked, and arms an hourly ping (capped, with auto-disarm). **Resuming costs a
~230-byte read**, not a re-explanation. Finished units are never re-run.

The check runs in a `PostToolUse` hook: ~0.2s, **zero Claude tokens**.

---

## Honest limits

- **`--sandbox` does not confine writes.** Tested directly: an agent with a working directory of
  `…\.sbx\inner`, told to write to its parent, did so. The flag also accepts invented profile names.
  The working directory is a **convention, not a security boundary**. Do not point a fleet at a tree
  you cannot afford to have modified.
- **Never `pkill -f grok.exe`.** It matches whole command lines and kills bystanders — including the
  shell that ran it. Stop control uses tracked PIDs, with `taskkill /IM` for orphans.
- **X/social sources are signal, not evidence.** In research runs they are tagged, counted
  separately, and must be labelled in the report.
- **grok.exe is a Windows process** — it cannot see WSL-native paths. The project must live under
  `/mnt/<drive>`; `doctor` hard-fails otherwise.

## Worked example

```bash
bash scripts/grok-fleet.sh doctor --live
bash scripts/df-research.sh run "How effective are LLM code review comments at finding real defects?"
```

Real result: 4 parallel sweep workers, 3 parallel critics, one cite-check gate. **38 distinct sources
(34 primary), 6 contradiction clusters, 172 citations.** The cite-check gate rejected 6 unsupported
citations; a remediation pass re-cited 1, weakened 12, deleted 5 — inventing nothing — and the
re-check returned **149 supported / 0 unsupported / 0 fabricated**.

Claude's share of that: dispatch it, read the audit verdict.

See the host runbook (`.claude/docs/runbook.md`, installed into every project) for the
copy-pasteable version.

## Two runners, one control plane

drom-flow can fan work out to **grok** or **codex**, whichever a machine actually has. Both are
optional and both are silent when missing: a project with neither installed behaves exactly as it
would if the feature did not exist — no warnings, no failed hooks, nothing in the statusline.

|  | grok | codex |
|---|---|---|
| binary | `grok.exe`, a Windows process driven from WSL | native, runs anywhere |
| paths | every path via `wslpath -w`; control plane must be Windows-visible | no translation |
| progress | agent-written `PROGRESS.md` (the stream carries no tool events) | real `item.completed` events |
| verdict | distilled from the terminal stream event | `-o` writes the final message to a file |
| accounting | USD | tokens |
| sandbox | prompt discipline | enforced: reads allowed, writes confined to the agent's directory |

Codex agents run `workspace-write` scoped to their own directory, so they can read the repository
and cannot write outside their own output. Writing into the repository itself takes an explicit
`--write-repo`, and that forces parallelism to 1 — parallel agents sharing one working tree
corrupt each other, and fan-out is the whole point.

Turn either off with `CODEX_DISABLE=1` / `GROK_DISABLE=1` even when installed.
