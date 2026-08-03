---
title: drom-flow
nav_order: 1
---

# drom-flow

A lean, dependency-free enhancement kit for [Claude Code](https://docs.anthropic.com/en/docs/claude-code):
structured workflows, chapter-based plans, closed-loop pipelines, persistent memory, lifecycle hooks —
and **parallel grok CLI sub-agents** that do the expensive work so Claude doesn't.

No MCP servers. No npm packages. No Node.js. Markdown and bash.

## Install

```bash
# once, after download (scripts ship as text so ZIPs stay firewall-friendly)
claude "Read start-here.md and follow the setup instructions"

# then, from your project root
bash /path/to/drom-flow/init.sh
```

Update later with `init.sh --update .`, remove with `init.sh --uninstall .`. Your `CLAUDE.md`,
`context/*`, plans, and reports are never overwritten.

## The idea in one table

| | Without | With drom-flow |
|---|---|---|
| Planning | Lives in the conversation | Chapter-based plans in `drom-plans/`, resumable across sessions |
| Parallelism | Occasional | Enforced — plus fan-out to **grok workers**, 8 concurrent verified |
| Failure loops | Manual retry | Closed loop: check → fix in parallel → re-check, with regression revert |
| Memory | Gone at session end | `context/MEMORY.md`, capped so it doesn't tax every turn |
| Token cost | Every file read burns Claude | Delegate: **−64% turns, −65% output, −97% context** |
| Hitting the usage limit | Session dies, work stranded | Checkpoint, grok keeps working detached, resume from ~230 bytes |

## Start here

- **[Claude orchestrator, grok workers](orchestration.md)** — the model, the measurements behind it,
  routing rules, lifecycle, and what happens when Claude runs out. *Read this one first.*
- [Skills](skills.md) — every skill, generated from source so it can't drift
- [Scripts](scripts.md) — every script and subcommand
- [Hooks](hooks.md) · [Workflows](workflows.md) · [Plans and closed loops](plans.md)
- [Install and updates](install.md) — including what host projects actually receive

## Operator runbook

Every project that installs drom-flow gets `.claude/docs/runbook.md` — the terse, copy-pasteable
version of the above. It is deliberately host-only: this site is for reading, the runbook is for
working.

## Honest limits

drom-flow is opinionated and young. Three things worth knowing before you rely on it:

- **`--sandbox` does not confine grok's writes.** The working directory is a convention, not a
  security boundary. Don't point a fleet at a tree you can't afford to have modified.
- **grok runs as a Windows process**, so the project must live under `/mnt/<drive>` in WSL.
- **The 97% usage warning is an estimate.** Claude Code exposes no live quota meter, so the figure is
  learned from past limit events and is suppressed until it's trustworthy. The 100% trigger is exact.

[Source on GitHub](https://github.com/drompincen/drom-flow)
