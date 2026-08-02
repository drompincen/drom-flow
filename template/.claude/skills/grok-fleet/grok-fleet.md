---
name: grok-fleet
description: Fan out work to grok CLI sub-agents in parallel from WSL, with filesystem-based progress, monitoring, and stop control — combined with Claude sub-agents
user-invocable: true
---

# Grok Fleet

You orchestrate **grok CLI sub-agents** alongside Claude's own sub-agents. Grok agents are real
`grok.exe` processes (the CLI, **not** the API — no key, it uses the user's `grok login` session),
driven entirely through files: `task.md` in, `status.json` / `PROGRESS.md` / `result.json` / `output/` out.

## Prerequisite — always run first

```bash
bash scripts/grok-fleet.sh doctor --live
```

Exit 0 means grok is reachable and the project layout works. If `win_visible` fails, the project is
on a WSL-native path and **grok cannot see it** — grok.exe is a Windows process, so the project must
live under `/mnt/<drive>/…`. Say so plainly and stop; no fan-out is possible.

## Routing — which engine gets the work

**Give it to Claude sub-agents when the work needs:**
- Repo context, project memory, hooks, or conventions
- Coherent edits spanning multiple files
- Final integration, merging, and adjudication
- Any write to the real working tree

**Give it to grok sub-agents when the work is:**
- Wide and independent — many units that don't touch each other
- Well-specified with a verifiable output (a marker, a schema, a file that must exist)
- Breadth research, per-file mechanical transforms, test generation, N-way exploration
- A **cross-model second opinion** on Claude's own output

**Hard rule:** never let both engines write the same files in the same phase. Grok writes only inside
its own `output/`; Claude reads those and integrates. This is what makes the fan-out safe.

## Running a fleet

```bash
# one agent
bash scripts/grok-fleet.sh spawn --run-id RUN --agent-id alpha --task-file /path/task.md

# structured verdict (cross-model review)
bash scripts/grok-fleet.sh spawn --run-id RUN --agent-id reviewer \
  --task-file review.md --schema verdict.schema.json

# watch (safe to call repeatedly)
bash scripts/grok-fleet.sh status --run-id RUN

# stop one, or everything
bash scripts/grok-fleet.sh stop --run-id RUN --agent-id alpha
bash scripts/grok-fleet.sh stop --all

# gather into one report (non-zero exit if any agent failed)
bash scripts/grok-fleet.sh collect --run-id RUN
```

For parallel fan-out, background each `spawn` and `wait`, respecting `GROK_MAX_PARALLEL` (default 4).

## Writing a good task.md

Each task must be self-contained — grok has **no** conversation context and cannot see Claude's memory.
Include: the goal, the exact output file expected, and a verifiable success marker. The fleet preamble
(auto-appended) already tells the agent to write `PROGRESS.md` checkpoints and stay inside its directory.

Verify outputs by content, not by exit code alone — the runner already downgrades an agent to `FAILED`
when it exits 0 but produced nothing.

## Monitoring

`streaming-json` carries **no tool-call events** — only token deltas and a final `end`. So progress
comes from two places:
- `PROGRESS.md` — checkpoints the agent writes itself (the real signal)
- `stream.jsonl` mtime — liveness; older than `--stall-secs` (180) marks the agent `STALLED`

## Budget

Roughly **$0.02–0.10 per agent** depending on turns. `GROK_BUDGET_USD` (default 5) caps a run;
per-agent cost is recorded in `status.json` and totalled by `status` and `collect`.

## Environment

| Var | Default | Purpose |
|---|---|---|
| `GROK_BIN` | auto-resolved | path to `grok.exe` |
| `GROK_MODEL` | `grok-4.5` | pinned model — validate with `grok models`; the name in `modelUsage` is **not** a selectable id |
| `GROK_MAX_PARALLEL` | 4 | concurrency gate |
| `GROK_BUDGET_USD` | 5 | run budget cap |
| `GROK_FLEET_ROOT` | `<project>/.claude/.grok-fleet` | control plane |

## Self-test

`bash scripts/grok-fleet.sh verify` runs six gates — feasibility, work_done, monitor, stop, combined,
control — and writes `reports/grok-verify.json`. The `combined` gate requires **you** (Claude) to read
the grok outputs and write `reports/grok-claude-merge.md` citing each agent's marker; that is
deliberate, since it proves both engines actually cooperated.
