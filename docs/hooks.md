---
title: Hooks
nav_order: 6
---

# Lifecycle hooks

Hooks run **outside Claude's context** — they cost zero Claude tokens.

| Hook | Purpose |
|---|---|
| `edit-log.sh` | drom-flow edit logger — appends edit events to JSONL |
| `javaducker-check.sh` | drom-flow — JavaDucker guard and lifecycle functions (sourced by other hooks) |
| `javaducker-index.sh` | drom-flow — index modified files in JavaDucker after edits |
| `memory-sync.sh` | drom-flow memory sync — inject session memory and check for in-progress plans on start |
| `repo-intel-mark.sh` | drom-flow — PostToolUse dirty marker for repository intelligence. |
| `repo-intel-path.sh` | drom-flow — where repository-intelligence state lives. Sourced, never run directly. |
| `repo-intel-session.sh` | drom-flow — SessionStart check for repository intelligence. |
| `session-end.sh` | drom-flow session end — remind to persist progress and update plans |
| `statusline.sh` | drom-flow statusline — git-aware status for Claude Code |
| `track-agents.sh` | drom-flow — track background agent count |
| `validate-plan.sh` | drom-flow — validate plan files written to drom-plans/ |
