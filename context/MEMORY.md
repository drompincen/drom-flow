# Session Memory

## Current Focus


## Recent Decisions


## Key Findings


## Open Questions


## Session Log

## Grok Sub-Agent Fleet — closed loop (2026-08-01)

Built `scripts/grok-fleet.sh` + `scripts/grok-verify.sh`: fan out work to **grok CLI** sub-agents
(grok.exe, Windows process driven from WSL — **the CLI, not the API**; auth is the user's `grok login`
session). Filesystem is the control plane: `task.md` in → `status.json` / `PROGRESS.md` /
`result.json` / `output/` out.

### Iterations
- **Iter 0 — 2/6 gates.** All agents died at $0: `-m grok-4.5-build` rejected as `unknown model id`.
- **Iter 1 — 5/6 gates, $0.24.** Pinned `grok-4.5` (the only id `grok models` lists). Only `combined` left.
- **Iter 2 — 6/6 gates, $0.22, 82s. PASS.** Fixed verdict parsing (`structuredOutput`, not `text`);
  Claude authored `reports/grok-claude-merge.md`.

No regressions in any iteration. Total loop spend $0.46 of a $5 cap.

### Hard-won facts
- grok.exe **cannot see WSL-native paths** — `/tmp` and the Claude scratchpad are invisible. The project
  must live under `/mnt/<drive>`; `doctor` hard-fails otherwise. All paths via `wslpath -w`.
- `streaming-json` has **no tool-call events** — only token deltas + a final `end`. Progress must come
  from agent-written `PROGRESS.md`; stream mtime is only a liveness/stall signal.
- The model name in `modelUsage` (`grok-4.5-build`) is **not** a selectable `-m` id. Always check `grok models`.
- `--json-schema` results arrive in `result.json` under **`structuredOutput`**, not `text`.
- `kill` on the WSL-side PID propagates to the Windows process (verified: gone from `tasklist.exe`,
  stream frozen). `taskkill.exe` is only the orphan fallback.
- Cost ≈ $0.02–0.10 per agent — real enough to need `GROK_BUDGET_USD`.
- An agent exiting 0 having written nothing is treated as `FAILED`, not `DONE`.

### Bug caught during final confirmation (2026-08-01)
`stop --all` originally ran `pkill -f 'grok.exe'`. `pkill -f` matches the **whole command line**, so
it killed the caller's own shell (which merely mentioned the string in a `tasklist.exe /FI` filter) —
surfaced as a silent exit 144. Removed: `stop --all` now kills only tracked PIDs, then uses
`taskkill.exe /IM grok.exe /F` for Windows-side orphans. Never pattern-kill on a string this generic.

### Hardening round (2026-08-01)
- **`--sandbox` does NOT confine writes on Windows/WSL.** An agent with cwd `…\.sbx\inner`, asked to
  write to the parent, succeeded. Invented profile names are also accepted silently. The agent cwd is a
  **convention, not a security boundary** — use `--deny` / narrower `--permission-mode` for untrusted work,
  and never point a fleet at a tree you can't afford to have modified.
- **Concurrency ceiling ≥8**: 8 agents all DONE in 13s, no degradation. Default stays 4.
- **Budget auto-halt works**: cap $0.05 / 8 agents → halted after 3, `HALT` written, exit 1. The check must
  be *synchronous at launch* — the watchdog alone races with the concurrency gate.
- Two bugs found by testing: stopping an agent erased its cost (under-reporting the total the guard relies
  on), and the gate counted the watchdog as an agent job (shrinking parallelism by one). Both fixed.
- `*.sh` is gitignored repo-wide — **SCRIPTS.md is the real distribution mechanism**. Any script change must
  be re-embedded there (including `init.sh`), or ZIP users regenerate stale scripts. Round-trip verified.

## Token economy — minimize Claude, maximize grok (2026-08-01, v0.7.0)

Measured from the live session transcript, then optimized in a closed loop.

### Where Claude tokens actually go (295-turn session)
- `cache_read_input_tokens` **36,359,748** — dominant
- `output_tokens` **366,110** — second
- tool results into context 110 KB ≈ **27,588 tokens** — minor

**The intuitive fix (shorten tool output) targets the smallest line item.** Real drivers:
turns × resident context (~123K re-read per turn), Claude's own authoring, and context size.

### Benchmark: audit 3 skill files, Claude-only vs delegated
| Metric | Claude-only | Delegated | Cut |
|---|---|---|---|
| turns | 11 | 4 | −64% |
| output_tokens | 7,514 | 2,657 | −65% |
| tool_result bytes | 14,096 | 465 | −97% |
| billable tokens | 38,300 | 5,427 | −86% |

Parity held — grok found all 3 defects with better line references than the Claude pass.

### Iterations
- **Iter 1** — delegated but output only −28%: Claude still hand-wrote a 30-line dispatch
  block. Fix: `scripts/bench-audit.sh` encapsulation → −65%. **Claude authoring the
  dispatch is itself a top-two cost.**
- **Iter 2 (regression, caught by verify)** — 6/6 → **4/6**. My own changes broke two gates:
  the retry loop treated a deliberate `STOPPED` as failure and **relaunched the agent**,
  defeating `stop`; and `--check` displaced `--json-schema` output so verdicts came back
  empty. Fixed: never retry past a stop; disable `--check` when a schema is used.
- **Iter 3** — 6/6 PASS restored.

### Hard-won facts
- `--check` and `--json-schema` are mutually exclusive in practice.
- Retry logic must exempt deliberate stops or it silently defeats stop control.
- A parity check must be semantic — my first version grepped for a literal line number
  and failed a correct grok answer that phrased it differently.
- Resume costs a **227-byte** `RESUME.md` read; detached `drain` survives Claude exiting;
  killed agents are recovered, finished units never re-run.

## Usage-limit wake-up loop (2026-08-02, v0.8.0)

`scripts/limit-watch.sh` — checkpoint near the Claude usage limit, hand work to grok, arm an hourly
ping, resume when quota returns. **8/8 gates pass.**

**Claude Code exposes no live quota meter** (verified: transcript has per-turn usage only;
`~/.claude/stats-cache.json` is stale lifetime totals). The limit EVENT is exact — a synthetic
transcript message `You've hit your session limit · resets 9:50pm` — so the definitive trigger is
reliable; the 97% figure is an estimate and is suppressed until trustworthy.

**Four bugs testing caught:**
- Clustered limit events (re-hits minutes apart) made the learned budget 272K vs 2.75M actually spent.
  Gaps <30min are not windows.
- Reset time anchored on `now` instead of the event's own date → stale events looked active forever.
- Off-by-one window boundary in two places (usage sum + observation loop) → 97 turns read as 96%.
- Budget == spend-so-far always yields exactly 100% → would arm constantly. Now `low-bounded`,
  `percent: null`, predictive trigger off.

Hook cost: PostToolUse check runs in **0.2s**, outside Claude's context, zero Claude tokens.

## Repository intelligence — repo-intel (2026-08-09, v0.10.0)

Private, automatic repository-awareness layer. Zero-dependency Java 21 engine (JBang-compatible,
prefers a cached `javac` compile), state under `.claude/.state/repo-intel/`, invisible to host users.

### Hard-won facts
- **The JDK here is a Windows binary reached from WSL.** It cannot see `/tmp` or any WSL-native
  path and cannot run WSL's git. Everything must go through `wslpath -w`, and the file list is
  computed in bash and passed via `DROMFLOW_REPO_INTEL_FILELIST`. Same class of problem as grok.exe.
- **A version constant is not a cache key.** Editing an extractor without bumping `ENGINE_VERSION`
  left an old graph in service and every measurement taken against it was wrong. The launcher now
  fingerprints the engine sources into `DROMFLOW_REPO_INTEL_ENGINE_STAMP`.
- **Linear scans per call site are quadratic per file.** Three extractors looked up the enclosing
  scope by scanning all scopes; a 9,655-file repo never finished indexing. A cursor over scopes
  sorted by start fixed it — 191 s, and that is IO-bound (raw read of the same files: 236 s).
- **`bin/` is not build output** in shell projects. Excluding it silently dropped every entrypoint.
- **Masked comments cost search recall.** Extractors mask comments (correct for structure), but in
  a shell/config repo the searchable vocabulary lives there. One header-comment line per file is
  kept as a `summary` attribute; that is what made "install update uninstall" find `init.sh`.
- **The repo's own `.gitignore` ignored `*.sh`** — 0 of 138 tracked files were shell, including
  `init.sh`. A fresh clone could not install drom-flow at all. Negations added.
- Fixture ground truth authored independently (by grok) is worth the trouble: it caught the `bin/`
  exclusion, the block-scope typing bug, and the TS re-export matcher, none of which self-written
  tests would have questioned.

### Measured
- Fixtures: declarations 170/170 (100%), relations 181/186 (97.3%), cross-file 61/66 (92.4%),
  **0 of 489 confident edges wrong**.
- Discovery cost (scripted grep-then-read baseline vs one bounded query): spring-petclinic
  97→13 tool calls (−86.6%), 952KB→106KB (−88.9%); axios 138→11 (−92.0%), 3.01MB→69KB (−97.7%).
- Edit hook 20 ms, no JVM. Warm query ~250 ms in-engine. 12/12 release gates.
- Skill adoption judged by 5 independent grok agents on 12 unlabelled tasks: 30/30 complex used
  it, 30/30 trivial did not.
