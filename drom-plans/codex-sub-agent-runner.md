---
title: Codex sub-agent runner alongside grok
status: in-progress
created: 2026-08-10
updated: 2026-08-10
current_chapter: 1
---

# Plan: Codex sub-agent runner alongside grok

Add **codex** as a second sub-agent backend next to the existing grok fleet, behind one shared
filesystem control plane, so Claude can fan work out to whichever runner a machine actually has.

**The governing rule: both backends are optional, and absence is silent.** A project with neither
installed must behave exactly as it does today — no warnings at session start, no failed hooks,
no error output, nothing in the statusline. Optional means invisible when missing, not
"degrades with a message".

## What is actually on this machine

- `codex-cli 0.147.0`, a **WSL-native ELF binary** at `/home/drom/.local/bin/codex`, on PATH.
- The directory named in the request, `/mnt/c/Users/drom/codex`, is **empty**. The working
  install is the PATH binary; the plan resolves codex the same way it resolves grok (PATH first,
  then known locations, then `CODEX_BIN`), so both are covered.
- Being WSL-native is the important difference from grok: `grok.exe` is a Windows process that
  cannot see `/tmp` or any WSL path, which forced the whole `wslpath -w` discipline and the
  Windows-visible control plane. **codex has none of those constraints.**

## Relevant codex surface (verified against `codex exec --help`)

| Flag | Why it matters here |
|---|---|
| `codex exec [PROMPT]` | non-interactive run; prompt as arg or stdin |
| `-C, --cd <DIR>` | the agent's working root — one directory per agent, as with grok |
| `-s, --sandbox read-only \| workspace-write \| danger-full-access` | the safety dial this design turns on |
| `--json` | JSONL events on stdout — the progress signal grok's stream never gave us |
| `-o, --output-last-message <FILE>` | final message straight to a file: **no stream parsing at all** |
| `--output-schema <FILE>` | structured verdicts, equivalent to grok's `--json-schema` |
| `-m, --model` | model pinning |
| `--skip-git-repo-check` | lets an agent work in a scratch dir that is not a repo |
| `--add-dir <DIR>` | additional writable roots — deliberately used sparingly, see Chapter 4 |
| `--ephemeral` | no session files on disk |
| `codex exec resume [ID] --last` | resume, mapping onto our existing resume protocol |

`-o` and `--json` together remove the most fragile part of the grok runner: we currently distil a
terminal `end` event out of `stream.jsonl` and learned the hard way that `structuredOutput`, not
`text`, carries schema results. Codex hands us the final message as a file.

---

## Chapter 1: Probe and record the contract
**Status:** pending
**Depends on:** none

- [ ] `codex --version`, `codex exec --help`, `codex exec resume --help` captured into the plan notes
- [ ] Confirm exit codes for: success, prompt failure, sandbox denial, missing auth, no network
- [ ] Confirm what `--json` actually emits (event kinds, whether tool calls appear)
- [ ] Confirm `-o` file contents on both success and failure
- [ ] Confirm behaviour when `--cd` is outside a git repo, with and without `--skip-git-repo-check`
- [ ] Record how codex authenticates and where its config lives (`CODEX_HOME`, `config.toml`)

**Notes:**
> Do not design against the help text alone. The grok runner cost three iterations to facts the
> help text did not state — an unusable `-m` id, a stream with no tool-call events, and
> `--check` silently displacing schema output. Probe first, write second.

## Chapter 2: One control plane, two backends
**Status:** pending
**Depends on:** Chapter 1

- [ ] Extract the backend-independent parts of `grok-fleet.sh` into `scripts/fleet-common.sh`:
      run/agent directory layout, `status.json` atomic writes, budget accounting, stall detection,
      `collect --brief`, checkpoint/resume records
- [ ] Keep the on-disk protocol **identical** for both backends:
      `<fleet-root>/<run>/agents/<id>/{task.md,status.json,PROGRESS.md,result.json,output/}`
- [ ] `grok-fleet.sh` keeps its current behaviour and public commands — this is a refactor, and
      the existing 8/8 grok gates must still pass unchanged
- [ ] Backend interface, one file per runner, implementing exactly: `probe`, `launch`, `kill`,
      `harvest`
- [ ] The fleet root name stays `.claude/.grok-fleet/` for now to avoid breaking existing runs;
      a rename to `.claude/.fleet/` is a separate migration with a compatibility symlink

## Chapter 3: `scripts/codex-fleet.sh`
**Status:** pending
**Depends on:** Chapter 2

- [ ] Subcommands mirroring grok exactly: `doctor | spawn | status | stop | collect | resume | clean`
- [ ] `spawn --manifest` fan-out with the same concurrency gate and budget guard
- [ ] Per agent: `codex exec --json -C <agent>/output -s <sandbox> -o <agent>/last-message.txt
      --skip-git-repo-check --prompt-file-equivalent` (prompt via stdin, since codex reads `-`)
- [ ] Progress from `--json` events where available; fall back to the agent-written `PROGRESS.md`
      convention the fleet preamble already mandates
- [ ] `result.json` distilled from `-o` output plus the terminal JSON event
- [ ] Verdicts via `--output-schema` when a manifest entry declares one
- [ ] Retry on failure with the failure text appended, and **never** retry past a deliberate stop —
      the exact bug that once defeated grok's `stop`
- [ ] No `wslpath` anywhere: codex is WSL-native and paths are passed through untouched

## Chapter 4: Sandbox policy — the part worth getting right
**Status:** pending
**Depends on:** Chapter 3

- [ ] Default sandbox is **`read-only`**. Analysis, review, research and audit agents get nothing more.
- [ ] Authoring agents get `workspace-write` scoped to **their own** `output/` directory via `-C`,
      never the host repository
- [ ] Writing into the repository itself requires an explicit `--write-repo`, and when set the
      concurrency gate is forced to 1: parallel agents with write access to one working tree
      corrupt each other, and the fan-out is the whole point of the fleet
- [ ] `--dangerously-bypass-approvals-and-sandbox` is never used, and never made reachable from a
      manifest field
- [ ] `--add-dir` is only ever populated from explicit manifest entries, never inferred
- [ ] Document the policy where a maintainer will see it before changing it

**Notes:**
> The user's example — `codex exec --sandbox workspace-write "Implement the feature described in
> <prompt>.md"` — is exactly the single-agent authoring case. It works under this policy as a
> one-agent run with `--write-repo`. What the policy prevents is eight of those running at once
> against one working tree.

## Chapter 5: Optionality — both runners absent must be silent
**Status:** pending
**Depends on:** Chapter 3

- [ ] `doctor` on a machine without the binary: exit **0**, `{"available": false, "reason": "..."}`
      — not found is a fact, not a failure
- [ ] `spawn` without the binary: structured refusal, non-zero, no stack traces, no stderr noise
- [ ] Session-start and PostToolUse hooks: never probe for either binary on the hot path, and
      print nothing when neither is present
- [ ] Statusline shows a runner only when it is actually there
- [ ] Skills state the fallback in one line: if no runner is available, Claude does the work itself
- [ ] `init.sh` installs both runners' scripts unconditionally — a script that no-ops cleanly costs
      nothing, and conditional installs are how partial states get created
- [ ] Explicit test: a fixture host with **neither** binary on PATH runs a normal session and a
      normal edit with zero output attributable to the fleet

## Chapter 6: Backend selection
**Status:** pending
**Depends on:** Chapters 3, 5

- [ ] `--backend grok|codex|auto` on `spawn`, default `auto`
- [ ] `auto`: use whichever is available; if both, prefer the one the manifest declares, then the
      one with remaining budget, then codex (WSL-native, fewer moving parts)
- [ ] Per-agent override in the manifest, so one run can mix backends
- [ ] `collect --brief` output is backend-agnostic: Claude reads verdicts and never learns which
      runner produced them
- [ ] Record the backend in `status.json` for diagnostics

## Chapter 7: Tests and gates
**Status:** pending
**Depends on:** Chapters 3-6

- [ ] `scripts/codex-verify.sh` mirroring `grok-verify.sh`, writing `reports/codex-fleet.json`
- [ ] Gates: doctor-when-absent is silent and exit 0; spawn refuses cleanly when absent; a live
      2-agent fan-out completes; stop actually stops and is not retried; resume re-dispatches only
      unfinished agents; a finished agent is never re-run or re-billed
- [ ] Sandbox gate: a `read-only` agent that attempts to write fails **and the run survives**
- [ ] Concurrency gate: `--write-repo` forces parallelism to 1 (asserted, not assumed)
- [ ] Neither-runner-installed gate: PATH stripped of both, full session simulation stays silent
- [ ] Existing grok gates still pass after the Chapter 2 refactor — this is the regression check

## Chapter 8: Skills and documentation
**Status:** pending
**Depends on:** Chapter 7

- [ ] `/grok-fleet` skill generalised, or a sibling `/codex-fleet` — decide once the command
      surface is settled, and do not ship two skills that say the same thing
- [ ] `.claude/docs/` runner guide covering both, including the sandbox policy
- [ ] `docs/orchestration.md` updated; `docs/scripts.md` regenerated so the docs gates stay green
- [ ] README: one line. This is an internal capability, not a product surface
- [ ] `SCRIPTS.md` entries, and the new scripts ship as `*.sh.txt` like every other shell asset

---

## Risks

- **Refactoring grok while it works.** Chapter 2 touches a runner with 8 passing gates and real
  usage. The refactor is only allowed if those gates still pass unchanged; otherwise leave
  `grok-fleet.sh` alone and duplicate the control plane.
- **Sandbox default too permissive.** `workspace-write` is the obvious default and the wrong one
  for fan-out. Chapter 4 exists because this is the failure that would be expensive.
- **Two runners, two auth stories.** Neither must ever prompt interactively from a hook.
- **Silence is testable but easy to lose.** One `echo` in a probe path breaks the optionality
  guarantee for every project that has neither runner. Chapter 5's gate is the guard.

## Open questions

- Does `codex exec --json` emit tool-call events? If it does, the fleet gets real progress
  reporting that the grok backend cannot provide, and `PROGRESS.md` becomes a fallback rather
  than the primary signal.
- Is codex spend metered per-run in a way worth accounting for, or is the budget guard
  grok-specific? The budget code stays shared either way; it simply reports zero.
