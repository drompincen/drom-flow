# drom-flow runbook — the operator's first hour

Terse and copy-pasteable. Full guide: <https://drompincen.github.io/drom-flow>

## 0. Preflight (always first)

```bash
bash scripts/grok-fleet.sh doctor --live
```

Exit 0 = grok reachable. If `win_visible` fails, this project is on a WSL-native path and grok
**cannot see it** — move it under `/mnt/<drive>`. There is no workaround.

## 1. Fan work out

```bash
# one agent
bash scripts/grok-fleet.sh spawn --run-id RUN --agent-id a1 --task-file /abs/task.md

# many, in parallel (preferred — one Claude turn, not N)
bash scripts/grok-fleet.sh spawn --manifest run.json
```

`run.json`:
```json
{"run_id":"RUN","budget_usd":0,"max_parallel":4,
 "agents":[{"id":"a1","task_file":"/abs/a1.md"},{"id":"a2","task_file":"/abs/a2.md"}]}
```

Generate task files instead of writing them by hand:
```bash
bash scripts/mk-task.sh audit out.md TARGET=/abs/file CHECKS="..." OUTFILE=findings.md TITLE=x
```

## 2. Watch it

```bash
bash scripts/grok-fleet.sh status --run-id RUN
```

Progress comes from agent-written `PROGRESS.md`, not the stream. An agent whose stream is idle past
`GROK_STALL_SECS` (180) shows `STALLED`.

## 3. Stop it

```bash
bash scripts/grok-fleet.sh stop --run-id RUN --agent-id a1
bash scripts/grok-fleet.sh stop --all
```

Never `pkill -f grok.exe` — it kills bystanders, including your own shell.

## 4. Read the result — verdicts, not artifacts

```bash
bash scripts/grok-fleet.sh collect --run-id RUN --brief
```

**Open an agent's `output/` only when a verdict says FAIL.** Reading artifacts into Claude is the
thing this whole system exists to avoid.

## 5. Research

```bash
bash scripts/df-research.sh run "<question>" --depth quick|deep
```

Then read **only** `reports/df-research-audit.json` and the report's `## Answer`.
The audit is a hard gate: ≥20 distinct sources, every citation resolving, ≥1 contradiction cluster,
zero uncited claims, and **no unsupported or fabricated citations**. If it fails, the report is not
shippable — say so rather than presenting it.

## 6. When Claude runs out of tokens

```bash
bash scripts/grok-fleet.sh drain --manifest run.json   # detached — finishes without Claude
bash scripts/limit-watch.sh status                     # usage, learned budget, reset time
bash scripts/grok-fleet.sh resume --run-id RUN         # continue; finished units never re-run
```

Resuming costs a ~230-byte `RESUME.md` read. The `PostToolUse` hook checks usage at ~0.2s and zero
Claude tokens; the statusline shows `armed` / `ping-due`.

## Rules that keep a session alive

- **Never read a large file into Claude to describe it to grok — pass the path.**
- One manifest of N agents beats N separate spawns. Never poll in a loop; each poll is a turn, and
  every turn re-reads the whole context.
- Let grok author drafts; review the diff.
- Statusline `C:` = Claude sub-agents, `G:` = grok agents. A healthy delegated run looks like `C:0 G:N`.

## Environment

| Var | Default | Purpose |
|---|---|---|
| `GROK_MODEL` | `grok-4.5` | pinned model (validate with `grok models`) |
| `GROK_MAX_PARALLEL` | 4 | concurrency gate (8 verified safe) |
| `GROK_BUDGET_USD` | 5 | run cap; **`0` = unlimited** |
| `GROK_MAX_ATTEMPTS` | 3 | grok-side retries before Claude sees a failure |
| `LIMIT_WATCH_PCT` | 97 | predictive usage threshold |

## Caveat

`--sandbox` does **not** confine writes — the working directory is a convention, not a boundary.
Don't point a fleet at a tree you can't afford to have modified.
