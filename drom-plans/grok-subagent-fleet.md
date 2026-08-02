---
title: Grok Sub-Agent Fleet — Closed-Loop Filesystem-Controlled Fan-Out from WSL
status: completed
created: 2026-08-01
updated: 2026-08-01
current_chapter: 7
loop: true
check_command: bash scripts/grok-fleet.sh verify --json
pass_condition: all six exit criteria PASS in reports/grok-verify.json
max_iterations: 10
---

# Plan: Grok Sub-Agent Fleet (Closed-Loop)

Give drom-flow's planning skills the ability to fan out work to **grok CLI sub-agents** running as Windows processes driven from WSL, using the **filesystem as the control plane**, with full spawn / monitor / stop control alongside Claude's native sub-agents.

This plan runs as a **closed loop** per `workflows/closed-loop.md`: a single check command emits a machine-readable verdict on the exit criteria; failures are grouped and fixed by parallel agents; re-check; repeat until all pass or max iterations.

---

## EXIT CRITERIA (the loop's pass condition)

These are the user's stated criteria, made machine-checkable. `scripts/grok-fleet.sh verify` emits one `PASS`/`FAIL` per gate to `reports/grok-verify.json`. **The loop exits only when all six are PASS.**

| # | Gate ID | Exit criterion | Machine check |
|---|---|---|---|
| 1 | `feasibility` | Feasibility of driving grok from WSL | `doctor --live` ok:true, exit 0 |
| 2 | `work_done` | **Verified ability to get work done via grok CLI** | Multi-agent fan-out: every agent `DONE`, every `output/` non-empty and contains the required marker |
| 3 | `monitor` | **Monitor progress** | While running, ≥2 distinct `PROGRESS.md` checkpoints observed for ≥1 agent; `status` reports live state; stall detection functional |
| 4 | `stop` | **Stop progress** | Long agent killed mid-flight → `STOPPED` within grace+5s, absent from `tasklist.exe`, `stream.jsonl` byte count frozen across two samples |
| 5 | `combined` | **Claude + grok sub-agents combined** | Grok agents produce outputs; a Claude agent consumes all of them and emits a merged artifact; cross-model schema verdict returned |
| 6 | `control` | Full control of sub-agents | Failure reported honestly (`FAILED`, non-zero collect), budget guard fires, idempotent resume re-launches only dead agents |

**Max iterations: 10.** Per-iteration capture: gate pass/fail, failing gate IDs, total USD spent, wall clock, regressions.

### LOOP RESULT — CONVERGED IN 2 ITERATIONS ✅

| Iter | Gates | Failing | Cost | Wall | Change made |
|---|---|---|---|---|---|
| 0 | 2/6 | work_done, monitor, combined, control | $0.00 | 26s | baseline — all agents died instantly |
| 1 | 5/6 | combined | $0.2398 | 91s | **fixed invalid model id** `grok-4.5-build` → `grok-4.5` (finding #3) |
| 2 | **6/6** | — | $0.2236 | 82s | fixed verdict parser (`structuredOutput`, not `text`); Claude authored the merge artifact |
| 3 | — | — | — | — | aborted: `stop --all` used `pkill -f 'grok.exe'`, which killed the **caller's own shell** (its command line merely mentioned the string) — silent exit 144 |
| 4 | **6/6** | — | $0.1760 | 83s | confirmation run after removing the pattern-kill; tracked PIDs + `taskkill.exe` for orphans only |
| 5 | **6/6** | — | $0.1693 | 78s | final run after Chapter 7 hardening (manifest, budget auto-halt, cost preservation, gate fix) |

No regressions; no iteration ever increased the failing-gate count. Total loop spend **$0.64** against a $5 cap.
Final `verify` exits 0 with all six gates PASS — evidence in `reports/grok-verify.json`.

**Do not pattern-kill on a string this generic.** `pkill -f` matches the whole command line, so any
process merely *mentioning* `grok.exe` (a `tasklist.exe /FI` filter, a log line, an editor) becomes a
casualty. Stop control uses tracked PIDs, with `taskkill.exe /IM grok.exe /F` reserved for orphans.

---

## Feasibility test — EXECUTED 2026-08-01, PASSED ✅

Probed against the real binary before this plan was written.

**Environment:** `/mnt/c/Users/drom/.grok/bin/grok.exe` — `grok 0.2.101 (5bc4b5dfad) [stable]`, windows-aarch64. Not on WSL `PATH`. Authenticated (`~/.grok/auth.json`); `permission_mode = "always-approve"`.

| # | Capability | Result |
|---|---|---|
| 1 | Headless from WSL | ✅ exit 0, `PONG`, 10.4s |
| 2 | Real tool use (file write) | ✅ exact content |
| 3 | WSL sees grok output | ✅ shared disk both ways |
| 4 | Machine-readable progress | ✅ `streaming-json`; `end` event has `sessionId`, `stopReason`, `usage`, `total_cost_usd` |
| 5 | **Parallel fan-out** | ✅ 3 concurrent, **all exit 0 in 5s** |
| 6 | Structured verdicts | ✅ `--json-schema` conformant |
| 7 | **Stop control** | ✅ `kill` propagates; gone from `tasklist.exe`; stream froze at 315 lines |
| 8 | External observability | ✅ `tasklist.exe` works from WSL |

### Findings that shape the design

1. **grok.exe is a Windows process — it cannot see WSL-native paths.** `/tmp` and Claude's scratchpad are invisible. The fleet workspace **must** live under `/mnt/c` (`C:\`); all paths go through `wslpath -w`. Session dirs confirm it (`C%3A%5CUsers%5C…`).
2. **`streaming-json` has no tool-call events** — only `thought`/`text` deltas and a final `end`. Progress must be **file-based** (`PROGRESS.md`); the stream is only a liveness heartbeat.
3. **Model ids: the reported name is not a selectable id.** The `end` event's `modelUsage` says `grok-4.5-build`, and `config.toml` says `grok-composer-2.5-fast`, but **neither is a valid `-m` value** — passing `grok-4.5-build` fails hard with `unknown model id`. `grok models` lists the only selectable id on this account: **`grok-4.5`**. Pin that, and validate with `grok models` before ever changing it. *(Corrected during iteration 1 — this was the single defect that failed the whole first fan-out.)*
4. **~$0.025–0.028 per trivial call** → hard budget cap required.
5. **`kill` on the WSL PID suffices**; `taskkill.exe` is the orphan fallback.
6. `bypassPermissions` lets grok write anywhere — confinement is our job.

### Settled defaults (no further prompting)

`GROK_MAX_PARALLEL=4` · `GROK_BUDGET_USD=5` · **`GROK_MODEL=grok-4.5`** (pinned; the only id `grok models` lists) · grace 10s · stall 180s · agent timeout 600s · `--no-memory` for reproducibility.

---

## Architecture

Filesystem control plane, one directory per run, under the repo (Windows-visible):

```
.claude/.grok-fleet/<run-id>/
  run.json          # manifest: agents, engine routing, budget, max-parallel, status
  fleet.log
  agents/<agent-id>/
    task.md         # prompt (--prompt-file)      cmd.txt   # exact command, reproducible
    pid             # wsl_pid + win_pid           status.json # QUEUED|RUNNING|DONE|FAILED|STOPPED|TIMEOUT
    PROGRESS.md     # agent-written checkpoints — the real progress signal
    stream.jsonl    # raw streaming-json (mtime = heartbeat)
    result.json     # end event + schema verdict  STOP # cooperative-stop sentinel
    output/         # work product = the agent's sandboxed cwd
```

The contract is model-agnostic — `task.md` in, `status.json`/`result.json` out — so Claude and grok agents are interchangeable and the loop survives context compaction, restarts, and crashes.

---

## Chapter 1: Check command — `grok-fleet.sh doctor` + `verify` skeleton
**Status:** completed
**Depends on:** none

The loop needs its check command to exist before anything else.

- [x] Create `scripts/grok-fleet.sh` with subcommands: `doctor`, `spawn`, `status`, `stop`, `collect`, `verify`, `clean`
- [x] Binary resolution: `$GROK_BIN` → `command -v grok` → `/mnt/c/Users/$USER/.grok/bin/grok.exe` → `$(wslpath -u "$(cmd.exe /c echo %USERPROFILE%)")/.grok/bin/grok.exe`
- [x] `doctor` gates: binary executable; `--version`; `auth.json` present; interop live (`tasklist.exe`); `wslpath -w` round-trip; **repo under `/mnt/c` (hard fail with explanation otherwise)**
- [x] `doctor --live`: `PONG` smoke test, `--max-turns 2`, 60s timeout, assert exit 0 + non-empty stdout
- [x] `verify --json` runs all six exit-criteria gates and writes `reports/grok-verify.json`: `{ ok, iteration, gates[{id,status,detail,evidence}], cost_usd, wall_clock_s }`; exit 0 only if all PASS
- [x] Env contract: `GROK_BIN`, `GROK_MODEL`, `GROK_FLEET_ROOT`, `GROK_MAX_PARALLEL`, `GROK_BUDGET_USD`

## Chapter 2: Spawn — one agent, fully controlled
**Status:** completed
**Depends on:** Chapter 1

- [x] `spawn --run-id R --agent-id A --task-file F [--model M] [--timeout S]` → agent dir + `task.md` + `cmd.txt` + `status.json`=`QUEUED`
- [x] Command pinned explicitly (finding #3): `--cwd "$(wslpath -w "$AGENT_DIR/output")" -m "$GROK_MODEL" --prompt-file <win> --output-format streaming-json --permission-mode bypassPermissions --no-memory --max-turns N`
- [x] Every path through `wslpath -w`; reject task files outside the fleet root (traversal guard)
- [x] Confine: cwd = own `output/`; `--sandbox` evaluated and found NOT to confine (see Chapter 7) — documented as a caveat
- [x] Detached under `timeout S`; record WSL + Windows PID to `pid`; status → `RUNNING`
- [x] **Fleet preamble** injected into every `task.md`: append a checkpoint line to `PROGRESS.md` after each step; write final answer under the cwd; never write outside it; end with a one-line summary
- [x] Wrapper parses last `end` event → `result.json`; terminal status by exit code: 0 `DONE`, 124 `TIMEOUT`, 143 `STOPPED`, other `FAILED`
- [x] `--schema <file>` → `--json-schema` for verdict agents

## Chapter 3: Fan-out, monitor, stop
**Status:** completed
**Depends on:** Chapter 2

- [x] `spawn --manifest run.json` with concurrency gate; ramp-tested at 8 with no degradation — safe ceiling ≥8, default 4
- [x] `status --run-id R` → table + `reports/grok-fleet-<R>.json`: per-agent state, elapsed, last `PROGRESS.md` line, stream-mtime age, cost; run rollup
- [x] **Stall detection**: `RUNNING` with stream mtime older than `--stall-secs` (180) → `STALLED` (mitigates finding #2)
- [x] **Budget guard**: per-launch check + watchdog; on breach writes `HALT`, stops agents, exits non-zero (verified)
- [x] `stop --run-id R [--agent-id A]`: `STOP` sentinel → grace 10s → `kill` → `kill -9` → `taskkill.exe /PID <win> /F`; verify via `tasklist.exe`; status `STOPPED`
- [x] `stop --all` reconciles orphans: any `grok.exe` PID in a fleet `pid` file whose run is terminal
- [x] `collect --run-id R` → `reports/grok-fleet-<R>.md`; non-zero exit if any agent failed
- [x] Idempotent resume: re-running `spawn --manifest` relaunches only non-terminal agents; `--iteration N` supported

## Chapter 4: Skill + orchestrator integration
**Status:** completed
**Depends on:** Chapter 3

- [x] `template/.claude/skills/grok-fleet/grok-fleet.md` (`name`, `description`, `user-invocable: true`) — decompose → write `task.md` → spawn → poll → stop → collect
- [x] **Routing policy**, documented and encoded:
  - **Claude sub-agents** — repo context, memory, hooks, multi-file coherence, final integration, anything touching the real working tree
  - **Grok sub-agents** — wide independent well-specified units with verifiable outputs: breadth research, per-file mechanical transforms, test generation, N-way exploration, cross-model second opinions
  - **Never** let both engines write the same files in one phase; grok writes only inside `output/`, Claude integrates
- [x] **Cross-model review**: grok reviews Claude's diff and Claude reviews grok's output, both with `--json-schema` verdicts
- [x] `/planner`: when a chapter has ≥3 independent units, emit `engine:` per unit (`claude`|`grok`) plus a ready-to-run `run.json`
- [x] `/orchestrator`: grok fan-out as the fix stage of the CLAUDE.md loop (baseline → parallel fix → re-check → log → regression revert)
- [x] Mirror + register: `template/`, `CLAUDE.md`, `template/CLAUDE.md`, `README.md`, `SCRIPTS.md` (embedded source, round-trip verified)
- [x] Gitignore `.claude/.grok-fleet/`

## Chapter 5: Run the closed loop to green
**Status:** completed
**Depends on:** Chapter 4

Execute `workflows/closed-loop.md` with the check/pass/max defined above.

- [x] **Iteration 0 — baseline**: run `verify --json`, record which of the six gates pass, log to `context/MEMORY.md`
- [x] **Analyze**: group failing gates by fix category (spawn / monitor / stop / integration / control)
- [x] **Fix in parallel**: one Agent per failing category, all spawned in ONE message with `run_in_background: true`
- [x] **Review all agent results together**, checking for conflicts (two agents editing `grok-fleet.sh` in the same region)
- [x] **Re-check**: `verify --json`; improved → continue; **regression (more gates failing) → revert that iteration immediately**, log, and use a different approach; all pass → confirm
- [x] **Log every iteration** to `context/MEMORY.md`: `### Iteration N — gates X/6, failing:[ids], cost $Y, regressions:[...]`
- [x] **Confirm**: final clean `verify --live` run from a fresh shell; `stop --all`; no stray `grok.exe` in `tasklist.exe`
- [x] **On exit**: final summary to `context/MEMORY.md`; routing-policy decision to `context/DECISIONS.md`; measured parallelism ceiling and real per-unit cost recorded
- [x] Set plan `status: completed` when all six gates PASS

### Loop control
- **Pass condition:** all six gates PASS in `reports/grok-verify.json`
- **Max iterations:** 10 — on reaching it, stop and report remaining failing gates rather than grinding
- **Regression rule:** more failing gates than the prior iteration → revert that iteration's changes, never retry the same fix
- **Budget stop-loss:** cumulative loop spend over `GROK_BUDGET_USD` → halt and report

## Chapter 6: Host-project portability — seamless sub-agents anywhere drom-flow is installed
**Status:** completed
**Depends on:** Chapter 4

Any project that installs drom-flow must get grok fan-out with no extra setup.

- [x] Mirror `grok-fleet.sh` + `grok-verify.sh` into `template/scripts/` — `init.sh` copies `template/` recursively, so host projects receive them automatically
- [x] Mirror the `grok-fleet` skill into `template/.claude/skills/grok-fleet/`
- [x] Path independence: `REPO_ROOT` derives from the script's own location, so `FLEET_ROOT` resolves to `<host-project>/.claude/.grok-fleet` with zero configuration
- [x] Binary resolution works for any user: `$GROK_BIN` → `PATH` → `/mnt/c/Users/$USER/…` → `%USERPROFILE%` via `cmd.exe` (never hardcodes this machine's user)
- [x] `init.sh`: register `.claude/skills/grok-fleet` in `MANAGED_DIRS`; add `.claude/.grok-fleet/` to the gitignore patterns and to uninstall collection (run artifacts are transient and may embed prompt text)
- [x] Register the skill in `CLAUDE.md`, `template/CLAUDE.md`, `README.md`
- [x] Wire routing into `/planner` (emit `engine: claude|grok` per unit when a chapter has ≥3 independent units) and `/orchestrator` (grok fan-out as the fix stage) — mirrored into `template/`
- [x] **Verified on a real host project**: fresh `git init` dir → `init.sh` → 75 files copied → `doctor --live` ok (7.2s) → live grok agent ran, wrote `HOST_PROJECT_OK`, 2 progress checkpoints, `DONE`, cost tracked ($0.0721), `FLEET_ROOT` correctly resolved to the host project

## Chapter 7: Hardening — all remaining items closed
**Status:** completed
**Depends on:** Chapter 6

- [x] **`--sandbox` evaluated — it does NOT confine writes.** Tested directly: an agent launched with `--sandbox` and cwd `…\.sbx\inner`, told to write into the parent, did exactly that (`escape.txt` landed in `…\.sbx\`). The flag also accepts invented profile names with no error. **Conclusion: the working directory is a convention, not a boundary.** Documented as a security caveat; scope is enforced cooperatively via the preamble + per-agent cwd, and `--deny` / a narrower `--permission-mode` are the real levers for untrusted tasks
- [x] **`spawn --manifest`** with concurrency gate, per-launch budget check, watchdog, missing-task-file reporting, and skip-if-done
- [x] **Ramp test at 8 — no degradation.** 8 concurrent agents, all `DONE` in **13s**, all markers correct, $0.46. **Safe ceiling ≥8**; default stays 4
- [x] **Budget auto-halt verified**: cap $0.05 with 8 agents → halted after 3, `HALT` written, run marked `BUDGET_EXCEEDED`, exit 1
- [x] Fixed: stopping an agent wiped its recorded cost, under-reporting run spend the guard depends on — prior cost is now preserved on `STOPPED`
- [x] Fixed: concurrency gate counted the budget watchdog as an agent job, silently shrinking the gate by one
- [x] **`SCRIPTS.md`** — both scripts embedded, `init.sh` block refreshed (it had gained grok-fleet changes), template-copy rows added; **round-trip verified byte-for-byte** for `init.sh`, `grok-fleet.sh`, `grok-verify.sh`
- [x] `docs/grok-fleet.md` written — architecture, routing, monitoring, stop semantics, cost, security caveats, troubleshooting, measured benchmarks
- [x] Final `verify` after all hardening: **6/6 PASS, $0.169, 78s**

> Pre-existing, untouched: the embedded `scripts/orchestrate.sh` in `SCRIPTS.md` differs from the local
> copy by a 2-line comment. This drift is already in `HEAD` and was not introduced here; the committed
> (embedded) version is the richer one, so the local file is simply a stale regeneration.

---

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Repo on a WSL-native path → grok blind to it | Hard fail in `doctor` (Ch 1) |
| Coarse stream progress (finding #2) | `PROGRESS.md` + stall detection (Ch 2–3) |
| Runaway cost (finding #4) | Budget guard + loop stop-loss (Ch 3, 5) |
| `bypassPermissions` write-anywhere | Per-agent cwd, `--sandbox`, traversal guard (Ch 2) |
| Orphaned `grok.exe` after a crash | `stop --all` reconciliation (Ch 3) |
| Model drift (finding #3) | Pin `-m`; record model in `result.json` (Ch 2) |
| Concurrency ceiling unproven above 3 | Ramp test at 8, publish safe default (Ch 3) |
| Both engines editing one file | Routing policy; grok writes only `output/` (Ch 4) |
| Loop oscillates without converging | Max 10 + regression revert + no-repeat rule (Ch 5) |

## Assumptions

- Repo stays under `C:\` / `/mnt/c` (true for `drom-flow` today)
- grok stays authenticated; `doctor` catches expiry before fan-out
- `--no-memory` for fleet agents keeps runs reproducible
- The movie project's `GROK_RUNBOOK.md` is **project-specific, not a template** — no conventions inherited
