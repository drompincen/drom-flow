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
