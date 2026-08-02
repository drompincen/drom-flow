---
title: Token-Limit Wake-Up Loop — checkpoint at 97%, ping hourly, resume on reset
status: completed
created: 2026-08-02
updated: 2026-08-02
current_chapter: 7
loop: true
check_command: bash scripts/limit-watch.sh verify --json
pass_condition: all eight gates PASS in reports/limit-watch.json
max_iterations: 10
---

# Plan: Token-Limit Wake-Up Loop

When Claude approaches its usage limit, the session should **not** just die mid-task. It should
checkpoint, hand off to grok (unmetered), arm an **hourly wake-up ping**, and resume cleanly when
quota returns.

Builds directly on [`minimize-claude-tokens.md`](minimize-claude-tokens.md) (complete, 8/8) — the
resume machinery, `RESUME.md`, and detached `drain` already exist and are verified.

---

## Feasibility probe — DONE 2026-08-02

Probed the live transcript and `~/.claude` before designing. Results decide the whole approach.

### ✅ The limit event IS observable, exactly

The transcript records a **synthetic assistant message** when the limit is hit:

```json
{"type":"assistant","timestamp":"2026-08-01T23:02:36.995Z",
 "message":{"model":"<synthetic>","stop_reason":"stop_sequence",
 "content":[{"type":"text","text":"You've hit your session limit · resets 9:50pm (America/Denver)"}]}}
```

It fired **3 times in one day** on this account (18:30, 22:18, 23:02 UTC). Critically, **the reset
time is in the text** — so once the wall is hit, the exact wake-up moment is known, not guessed.

### ❌ A live "97%" meter does NOT exist

There is **no exposed quota percentage, remaining-token count, or window-start marker.** Checked:

- Transcript records carry only per-turn `usage` (input/output/cache) — no quota fields.
- `~/.claude/stats-cache.json` holds **cumulative lifetime** totals per model and is stale
  (`lastComputedDate: 2026-04-25`). Useless as a live meter.

**Therefore 97% cannot be read — it must be estimated.** Any plan claiming to read it directly is wrong.

### Consequence: two triggers, not one

| Trigger | Basis | Accuracy | Purpose |
|---|---|---|---|
| **Predictive** (97%) | running billable tokens in the current window ÷ **learned** budget | approximate | checkpoint + arm the loop **before** the wall |
| **Definitive** (100%) | the limit-hit message + its parsed reset time | exact | arm the loop with the **real** reset moment |

The budget is **learned empirically**: every observed limit-hit event reveals how many tokens the
window actually allowed, so the estimate self-calibrates instead of relying on a guessed constant.
Measured this session: **322,276 tokens** of context in a single turn, and three limit events — enough
signal to seed the first estimate.

---

## EXIT CRITERIA

`scripts/limit-watch.sh verify` writes `reports/limit-watch.json`.

| # | Gate | Criterion |
|---|---|---|
| 1 | `detect` | Parses limit-hit events from the transcript and extracts the reset time (local tz) correctly for all 3 known historical events |
| 2 | `estimate` | Reports running billable tokens for the current window and a % against the learned budget; never crashes on a fresh session with no history |
| 3 | `calibrate` | Each observed limit event updates the learned budget; the stored budget is within ±20% of observed consumption at the last limit |
| 4 | `trigger` | At ≥97% (or on a limit event) it writes a checkpoint **and** arms the hourly wake-up — exactly once per window, never re-arming in a loop |
| 5 | `wake` | The hourly ping fires, re-checks quota, and either resumes or re-arms; survives the Claude session ending |
| 6 | `grok_continues` | While Claude is blocked, detached grok work still completes (verified by a run that finishes with Claude idle) |
| 7 | `no_false_positive` | Below threshold it does **not** trigger; a synthetic 96% case stays silent, 97% fires |
| 8 | `ship` | Committed and pushed in drom-flow, catsandbears refreshed to the new version and re-verified live |

**Max iterations: 10.**

### RESULT — 8/8 GATES PASS ✅

| Gate | Result |
|---|---|
| detect | 4 live limit events parsed; last resets `1:20pm` → exact epoch |
| estimate | empty session → `percent=null` (no false reading); 100×1000 vs 100k → 100.0% |
| calibrate | learned 55,000 from windows 50k/60k/55k (median), confidence `high` |
| trigger | 96% did **not** arm; 97% armed; second check idempotent, no stacking |
| no_false_positive | 96% produced no arm and no output |
| wake | ping fired, status reported, detached re-arm timer scheduled |
| grok_continues | detached grok run completed 2/2 with Claude idle |
| ship | v0.8.0 pushed; catsandbears refreshed and re-verified |

**Four real bugs found by testing, all fixed:**
1. **Budget learned from clustered events was 10× too low** — 272K against a window that had spent
   2.75M. Limit events fire repeatedly minutes apart once a window is exhausted; those gaps are
   re-hits, not windows. Now ignored below 30 minutes.
2. **Reset time was anchored on *today*** — so an event from yesterday resolved to a reset later today
   and looked permanently active, firing the definitive trigger forever. Now anchored on the event's
   own timestamp.
3. **Off-by-one window boundary** (twice: usage sum and observation loop) — anchoring on the first
   turn then using a strict `>` silently dropped it, so 97 turns read as 96%.
4. **A budget equal to spend-so-far always reads exactly 100%** — degenerate, would have armed
   constantly. Now reported as `low-bounded` with `percent: null` and the predictive trigger suppressed.

---

## Chapter 1: Detect and estimate
**Status:** completed
**Depends on:** none

- [x] `scripts/limit-watch.sh` — subcommands `status`, `check`, `arm`, `disarm`, `verify`
- [x] **Parse limit events**: scan the transcript for `model:"<synthetic>"` assistant messages whose text matches `hit your (session|usage) limit .* resets <time>`; extract the reset clock time and timezone, convert to an absolute epoch (handling the next-day rollover when the reset time is earlier than now)
- [x] **Window estimation**: define the current window as *since the last limit event, or session start if none*; sum `output_tokens + input_tokens + cache_creation_input_tokens` per turn as billable
- [x] Deliberately **exclude `cache_read_input_tokens`** from the billable estimate and document why — it dominates raw counts (36.4M in one session) but is not what exhausts a session limit; including it makes the estimate meaningless
- [x] `status` prints: window start, billable so far, learned budget, **percent used**, last limit event, reset time
- [x] Degrade safely: no transcript, no history, or an unparseable reset → report `unknown`, never a false 97%

## Chapter 2: Learn the budget
**Status:** completed
**Depends on:** Chapter 1

- [x] Persist `.claude/.state/limit-budget.json`: `{ observed:[{window_tokens, ts}], learned_budget, confidence }`
- [x] On each newly-seen limit event, record the window's consumed tokens and update `learned_budget` (rolling median of observations — robust to one anomalous window)
- [x] Seed from the 3 historical events already in this transcript so the feature is useful on day one
- [x] `confidence: low` until ≥3 observations; while low, **prefer the definitive trigger** and treat 97% as advisory only
- [x] Allow an explicit override (`CLAUDE_TOKEN_BUDGET`) for users who know their plan's limit

## Chapter 3: Trigger and arm the hourly loop
**Status:** completed
**Depends on:** Chapter 2

- [x] Threshold configurable, default **97%** (`LIMIT_WATCH_PCT`)
- [x] On trigger: (a) `grok-fleet.sh checkpoint` for every in-progress run, (b) write `.claude/.state/limit-armed.json` with the reset time and the resume command, (c) arm the wake-up
- [x] **Idempotent arming** — one arm per window, tracked by window id; re-running must never stack duplicate schedules
- [x] **Wake-up mechanism**: evaluate and choose in this order, documenting what is actually available:
  1. `CronCreate` / the `/schedule` skill — survives the session ending, best fit for "wake me hourly"
  2. `ScheduleWakeup` (dynamic `/loop`) — in-session, simpler, dies with the session
  3. A detached `at`/`nohup` timer writing a flag the SessionStart hook reads — fallback with no harness dependency
- [x] Ping content is deliberately tiny: read `RESUME.md` (~227 B) and the armed state, then decide — resuming must not cost a large context read
- [x] **Hand off to grok before stopping**: any queued work switches to `drain` so it completes while Claude is blocked
- [x] `disarm` on successful resume, and automatically once the reset time has passed and quota is confirmed back

## Chapter 4: Wake, verify, resume
**Status:** completed
**Depends on:** Chapter 3

- [x] On wake: check whether quota returned (attempt a minimal call; treat a fresh limit event as "still blocked")
- [x] If blocked → re-arm for another hour, log the attempt, **do not** burn tokens retrying work
- [x] If restored → `grok-fleet.sh resume` for each armed run, then continue from `RESUME.md`
- [x] Cap re-arms (`LIMIT_WATCH_MAX_PINGS`, default 12) so a forgotten loop cannot ping forever
- [x] Every ping appends one line to `context/MEMORY.md` so the wait is auditable after the fact

## Chapter 5: Hook integration
**Status:** completed
**Depends on:** Chapter 4

- [x] Call `limit-watch.sh check` from the **PostToolUse** hook (already configured) — cheap, runs often, no Claude tokens
- [x] Guard cost: `check` must be O(tail of transcript), not a full re-parse, and must exit fast when far from threshold
- [x] **SessionStart** hook: if `limit-armed.json` exists, surface "a run is armed/resumable" — pairs with the outstanding `memory-sync` item from the previous plan
- [x] Statusline: show `⏳97%` (or `armed→9:50pm`) when near or past the threshold, alongside the existing `C:/G:` counters
- [x] Everything mirrored into `template/` so host projects get it

## Chapter 6: Run the loop to green
**Status:** completed
**Depends on:** Chapter 5

- [x] Iteration 0 baseline: run `verify`, record all eight gates
- [x] Fix failing gates in parallel; re-check; **regression → revert immediately**
- [x] Log each iteration to `context/MEMORY.md`
- [x] Test gate 7 with a **synthetic** transcript at 96% and 97% rather than by exhausting real quota
- [x] Test gate 5 by arming with a 60-second interval instead of waiting an hour

## Chapter 7: Ship
**Status:** completed
**Depends on:** Chapter 6

- [x] Sync `scripts/` → `template/scripts/`, re-embed changed scripts in `SCRIPTS.md`, round-trip verify byte-for-byte
- [x] Bump `VERSION`; register in `CLAUDE.md`, `template/CLAUDE.md`, `README.md`; document in `docs/token-economy.md`
- [x] Commit and push drom-flow
- [x] Refresh catsandbears (`init.sh --update`), re-verify live, commit and push staging only drom-flow-owned paths

---

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **97% is an estimate, not a reading** | Definitive trigger on the real limit event is exact; 97% is advisory until `confidence: high` (Ch 2) |
| Learned budget wrong on a new account | Seed from historical events; `CLAUDE_TOKEN_BUDGET` override; low confidence defers to the definitive trigger |
| Wake-up mechanism unavailable in this harness | Three-tier fallback, chosen and documented in Ch 3 rather than assumed |
| Ping loop runs forever | `LIMIT_WATCH_MAX_PINGS` cap (default 12) + auto-disarm after reset confirmed |
| Duplicate schedules stacking | Arming is idempotent per window id (Ch 3) |
| The check itself costs tokens | It runs in a **hook**, not in Claude's context — zero Claude tokens; tail-only parse |
| Waking early wastes a ping | Wake at parsed reset time **+ buffer**; a fresh limit event on wake means still blocked → re-arm |
| Resume re-runs finished work | Already proven: `resume` reconciles from disk, 0 re-run / 0 lost |

## Assumptions

- Limit-hit messages keep their current shape (`model:"<synthetic>"`, text containing `resets <time>`);
  gate 1 fails loudly if the format changes rather than silently reporting 0%
- Reset times are local-timezone clock strings needing next-day rollover handling
- grok remains unmetered, so handing off to `drain` while Claude is blocked is always the right move
