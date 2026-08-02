# Grok Sub-Agent Fleet

Fan out work to **grok CLI sub-agents** running as Windows processes, driven from WSL, with the
filesystem as the control plane. Works alongside Claude's own sub-agents.

> **This uses the grok CLI, not the xAI API.** Every agent is a `grok.exe` process invocation that
> reuses your existing `grok login` session. There is no API key to store, and no separate billing
> path — cost shows up in your normal grok usage.

---

## Why a filesystem control plane

A unit of work is a directory: `task.md` goes in, `status.json` / `PROGRESS.md` / `result.json` /
`output/` come out. That contract is deliberately model-agnostic, which buys three things:

- **Claude and grok agents are interchangeable** — the orchestrator routes each unit to whichever engine fits.
- **State survives** context compaction, session restarts, and crashes. Nothing lives only in memory.
- **Everything is inspectable** — you can `cat` any agent's progress or result without special tooling.

## Requirements

| Requirement | Why |
|---|---|
| grok CLI installed and logged in | `~/.grok/auth.json` must exist |
| **Project under `/mnt/<drive>/…`** | `grok.exe` is a *Windows* process and **cannot see WSL-native paths** — `/tmp`, `/home`, and the Claude scratchpad are invisible to it |
| WSL interop enabled | `tasklist.exe` / `taskkill.exe` are used for process observation and orphan cleanup |

`doctor` hard-fails with an explanation if the project is on a WSL-native path. There is no workaround
other than moving the project — it is a hard boundary of how the two filesystems interact.

---

## Quickstart

```bash
# 1. Always preflight first
bash scripts/grok-fleet.sh doctor --live

# 2. Write a task (self-contained — grok sees no conversation context)
cat > /tmp/task.md <<'EOF'
Audit every file in src/api for missing error handling.
Write your findings to findings.md in your working directory.
EOF

# 3. Run one agent
bash scripts/grok-fleet.sh spawn --run-id audit --agent-id api --task-file /tmp/task.md

# 4. Watch it / collect it
bash scripts/grok-fleet.sh status  --run-id audit
bash scripts/grok-fleet.sh collect --run-id audit
```

## Parallel fan-out with a manifest

```json
{
  "run_id": "audit",
  "budget_usd": 5,
  "max_parallel": 4,
  "agents": [
    { "id": "api",   "task_file": "/abs/path/api.md" },
    { "id": "db",    "task_file": "/abs/path/db.md" },
    { "id": "web",   "task_file": "/abs/path/web.md" },
    { "id": "judge", "task_file": "/abs/path/judge.md", "schema": "/abs/path/verdict.schema.json" }
  ]
}
```

```bash
bash scripts/grok-fleet.sh spawn --manifest run.json
```

The manifest runner enforces the concurrency gate, checks the budget **before each launch**, runs a
watchdog during the run, skips agents whose `task_file` is missing (marking them `FAILED` rather than
failing silently), and skips agents that already finished — so re-running is safe and never re-bills.

## Commands

| Command | Purpose |
|---|---|
| `doctor [--live]` | Preflight; `--live` adds a real round-trip. Writes `reports/grok-doctor.json` |
| `spawn --run-id R --agent-id A --task-file F [--schema S]` | Run one agent |
| `spawn --manifest M` | Parallel fan-out with concurrency + budget guards |
| `status --run-id R [--json]` | Live table; writes `reports/grok-fleet-R.json` |
| `stop --run-id R [--agent-id A]` / `stop --all` | Stop agents; `--all` also reaps Windows orphans |
| `collect --run-id R` | Markdown report; **non-zero exit if any agent failed** |
| `verify [--json]` | Six-gate self-test; writes `reports/grok-verify.json` |
| `clean` | Clear the fleet root |

## Layout

```
.claude/.grok-fleet/<run-id>/
  HALT                     # written when the budget guard trips
  agents/<agent-id>/
    task.md      cmd.txt          # prompt; exact command line (reproducible)
    pid          status.json      # QUEUED|RUNNING|DONE|FAILED|STOPPED|TIMEOUT|STALLED
    PROGRESS.md                   # agent-written checkpoints — the real progress signal
    stream.jsonl                  # raw streaming-json; mtime = liveness heartbeat
    result.json                   # end event + structured verdict
    output/                       # the agent's work product (its cwd)
```

---

## Routing — Claude vs grok

**Claude sub-agents** — repo context, project memory, hooks, multi-file coherence, final integration
and adjudication, and anything that writes to the real working tree.

**Grok sub-agents** — wide, independent, well-specified units with verifiable outputs: breadth
research, per-file mechanical transforms, test generation, N-way exploration, and **cross-model
second opinions**.

**Hard rule:** never let both engines write the same files in one phase. Grok writes only inside its
own `output/`; Claude reads those and integrates. This is what keeps a parallel fan-out safe.

`/planner` tags units `engine: claude|grok` when a chapter has ≥3 independent units; `/orchestrator`
can use grok fan-out as the fix stage of a closed loop.

### Why cross-model review earns its keep

During verification, a grok reviewer was asked to judge the claim *"a closed-loop pipeline re-runs its
check after each fix round and stops on regression."* It returned `fail`, correctly catching that on
regression the loop **reverts and tries a different approach**, and stops only on all-pass or
max-iterations. That is exactly the kind of plausible-sounding error a single-model pipeline tends to
wave through.

---

## Monitoring

`streaming-json` carries **no tool-call events** — only token deltas and a final `end`. So progress
comes from two independent places:

- **`PROGRESS.md`** — checkpoints the agent writes itself. This is the real signal, and the injected
  fleet preamble requires at least two per agent.
- **`stream.jsonl` mtime** — liveness only. A `RUNNING` agent whose stream hasn't moved in
  `--stall-secs` (default 180) is reported `STALLED`.

## Stopping

`stop` escalates: `STOP` sentinel → grace period (10s) → `kill` → `kill -9` → `taskkill.exe /F`.
Killing the WSL-side PID propagates to the Windows process; this is verified in the `stop` gate by
confirming the process leaves `tasklist.exe` and the stream stops growing across two samples.

> **Never `pkill -f grok.exe`.** `pkill -f` matches the entire command line, so any process merely
> *mentioning* the string — a `tasklist.exe` filter, a log tail, an editor — gets killed too. This bit
> us during development and is why `stop --all` uses tracked PIDs plus `taskkill.exe /IM` for orphans.

## Cost

Roughly **$0.02–0.10 per agent** depending on turns. `GROK_BUDGET_USD` (default 5) caps a run: the
manifest runner checks spend before each launch and a watchdog polls during the run. On breach it
writes `HALT`, stops running agents, and exits non-zero. Costs of stopped agents are preserved so the
total stays honest.

## Environment

| Var | Default | Purpose |
|---|---|---|
| `GROK_BIN` | auto-resolved | path to `grok.exe` |
| `GROK_MODEL` | `grok-4.5` | pinned model |
| `GROK_MAX_PARALLEL` | 4 | concurrency gate |
| `GROK_BUDGET_USD` | 5 | run budget cap |
| `GROK_FLEET_ROOT` | `<project>/.claude/.grok-fleet` | control plane location |
| `GROK_STALL_SECS` | 180 | stall threshold |
| `GROK_AGENT_TIMEOUT` | 600 | per-agent timeout (seconds) |

---

## Security — read this before pointing a fleet at a real repo

**`--sandbox` does not confine writes on this platform.** We tested it directly: an agent launched
with `--sandbox` and a working directory of `…\.sbx\inner` was asked to write into the parent, and it
did — `escape.txt` landed in `…\.sbx\`. The flag also accepts invented profile names without error.

Consequently, **the working directory is a convention, not a boundary**. The fleet enforces scope
through the injected preamble and per-agent cwd, which is sufficient for cooperative agents and *not*
a security control. In practice:

- Don't point a fleet at a tree you cannot afford to have modified.
- Prefer `--permission-mode` narrower than `bypassPermissions`, plus `--deny` rules, for untrusted tasks.
- Treat fleet output as untrusted input until reviewed — the run artifacts are gitignored for this reason.

## Verified behaviour

| Property | Measured |
|---|---|
| Headless round-trip | ~4–10s |
| **8 concurrent agents** | all `DONE` in **13s**, no degradation — safe ceiling ≥8 |
| Stop propagation | process leaves `tasklist.exe`; stream frozen across samples |
| Self-test | 6/6 gates PASS, ~$0.18–0.24, ~83s |
| Host-project install | fresh repo → `init.sh` → live agent `DONE` with cost tracked |

## Troubleshooting

| Symptom | Cause |
|---|---|
| `unknown model id` | The name in `modelUsage` (e.g. `grok-4.5-build`) is **not** selectable. Run `grok models`; use `grok-4.5` |
| `doctor` fails `win_visible` | Project is on a WSL-native path — grok cannot see it. Move it under `/mnt/<drive>` |
| Agent `DONE` but no output | The runner downgrades this to `FAILED` by design — an agent that wrote nothing did not do the work |
| Empty schema verdict | `--json-schema` results land in `result.json` under **`structuredOutput`**, not `text` |
| Agent `STALLED` | Stream idle beyond `GROK_STALL_SECS`; inspect `stream.jsonl` and `PROGRESS.md` |
| Silent exit 141 | SIGPIPE — you piped a long stream into `head`. Capture to a file instead |

## Self-test

```bash
bash scripts/grok-fleet.sh verify
```

Six gates: `feasibility`, `work_done`, `monitor`, `stop`, `combined`, `control`. The `combined` gate
requires **Claude** to read the grok outputs and write `reports/grok-claude-merge.md` citing each
agent's marker — deliberately, since it proves both engines actually cooperated rather than just
that grok ran.
