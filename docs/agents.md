---
title: Agents
nav_order: 3
---

# Agents: Claude sub-agents vs grok workers

Two kinds of sub-agent, with different economics and different jobs.

| | Claude sub-agent | Grok worker |
|---|---|---|
| Spawned by | the `Agent` tool | `grok-fleet.sh spawn` |
| Context | inherits the session's world | its own fresh process |
| Cost | your Claude budget | grok's (often unmetered) |
| State | in the conversation | on disk, survives restarts |
| Good at | judgment, integration, working-tree edits | reading, fetching, drafting, critiquing, breadth |
| Parallelism | limited | **8 concurrent verified, 13s** |

## Defining a grok worker

A worker is a `task.md` plus a manifest entry. There is no agent-definition format to learn — the
prompt *is* the agent.

```json
{"id":"sweep1","task_file":"/abs/sweep1.md"}
```

Generate the prompt from a template rather than writing prose each time:

```bash
bash scripts/mk-task.sh audit out.md TARGET=/abs/file CHECKS="..." OUTFILE=findings.md TITLE=x
```

Templates live in `scripts/task-templates/` (`audit`, plus the `research/` set used by `/df-research`:
decompose, sweep, audit, draft, critique, patch, citecheck, remediate).

Every worker gets a **fleet preamble** injected: write `PROGRESS.md` checkpoints, keep output inside
your working directory, finish with a `RESULT:` line.

## Choosing

Route by the [rules in the orchestration guide](orchestration.md#routing-who-gets-the-work). The short
version: if the unit needs judgment or touches the real working tree, it is Claude's. If it needs
reading, fetching, or drafting, it is grok's.

Never let both write the same files in one phase.
