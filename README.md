# drom-flow

A lean, dependency-free enhancement kit for [Claude Code](https://docs.anthropic.com/en/docs/claude-code). It gives Claude Code structured workflows, parallel agent orchestration, closed-loop pipelines, persistent memory, chapter-based execution plans, and lifecycle hooks -- using only markdown files, bash scripts, and Claude Code's native features.

No MCP servers. No npm packages. No Node.js. Just plain text and bash.

## Why drom-flow?

### The problem

Out of the box, Claude Code is powerful but unstructured. Every session starts fresh. There's no memory of what happened last time, no standard way to break down complex tasks, no protocol for iterating on failures, and no mechanism to resume interrupted work. You end up re-explaining context, re-discovering the same things, and watching Claude work sequentially when it could parallelize.

### What drom-flow adds

| Capability | Without drom-flow | With drom-flow |
|---|---|---|
| **Task planning** | Ad-hoc, lives only in conversation | Chapter-based plans saved to `drom-plans/`, persist across sessions, auto-resume on next start |
| **Parallelism** | Claude sometimes parallelizes, sometimes doesn't | Enforced by default -- every workflow spawns independent work as parallel agents |
| **Iteration on failures** | Manual retry, no tracking | Closed-loop protocol: check -> fix (parallel) -> re-check -> loop, with regression detection and automatic revert |
| **Session memory** | Gone when session ends | `context/MEMORY.md` loaded at start, updated at end, carries focus/findings/decisions forward |
| **Progress tracking** | None | Plan chapters track completed/in-progress/pending status; statusline shows `plan:ch3/5(2check)` |
| **Consistent workflows** | Depends on how you prompt | Predefined workflows for bug fixes, features, refactoring, code review, and closed-loop QA |
| **Agent specialization** | Generic agents | 7 skill profiles (`/planner`, `/reviewer`, `/debugger`, etc.) with domain-specific instructions |
| **Observability** | No visibility into what's happening | Statusline showing git state, session time, edit count, agent count, memory status, and plan progress |
| **Resumability** | Start over every session | Session-start hook detects in-progress plans and surfaces them with current chapter |

### Real-world result

In a QA pipeline for BPMN process diagrams, drom-flow's closed-loop workflow took **134 visual issues to 0 in 15 automated iterations**, spawning parallel fix agents per issue category with automatic regression detection and revert. See `samples/qa-closed-loop.md` for the full case study.

## Install

### Step 1: Generate scripts (required once after download)

Scripts are distributed as text in `SCRIPTS.md` to keep ZIP downloads firewall-friendly. Generate them first:

```
claude "Read start-here.md and follow the setup instructions"
```

Or copy them manually -- see `start-here.md` for details.

### Step 2: Install into your project

Run from your project root:

```bash
bash /path/to/drom-flow/init.sh
```

Or specify a target directory:

```bash
bash /path/to/drom-flow/init.sh /path/to/my-project
```

Files that already exist won't be overwritten. Safe to re-run.

### Updating an existing installation

When drom-flow has a new version, update your projects without losing customizations.

If you downloaded a new ZIP, generate scripts first (see Step 1 above), then:

```bash
# Check what would change (dry run)
bash /path/to/drom-flow/init.sh --check /path/to/my-project

# Apply the update
bash /path/to/drom-flow/init.sh --update /path/to/my-project
```

`--update` overwrites drom-flow managed files (hooks, skills, workflows, settings) but **never touches** your project-specific files:

| Protected (never overwritten) | Updated (replaced with latest) |
|---|---|
| `CLAUDE.md` | `.claude/hooks/*` |
| `context/MEMORY.md` | `.claude/skills/*` |
| `context/DECISIONS.md` | `.claude/settings.json` |
| `context/CONVENTIONS.md` | `workflows/*` |
| `scripts/orchestrate.sh` | `VERSION` |

Your plans in `drom-plans/`, reports in `reports/`, and any other project files are also untouched.

### What gets installed

```
CLAUDE.md              -- Behavioral rules, parallelism, closed-loop protocol, plan protocol
.claude/settings.json  -- Hooks, statusline, permissions
.claude/hooks/         -- 5 bash lifecycle hooks
.claude/skills/        -- 7 agent skills (/planner, /reviewer, /orchestrator, etc.)
context/               -- Memory, decisions, conventions templates
workflows/             -- bug-fix, new-feature, refactor, code-review, closed-loop
scripts/orchestrate.sh -- Template orchestration script for closed-loop pipelines
drom-plans/            -- Chapter-based execution plans with progress tracking
reports/               -- Iteration reports from orchestration runs
```

## Features

### Chapter-based plans

Plans are broken into chapters, each representing a logical phase of work. Chapters contain steps (checkboxes), track status (`pending` -> `in-progress` -> `completed`), and persist across sessions.

```markdown
---
title: Add Auth Middleware
status: in-progress
created: 2025-03-28
updated: 2025-03-28
current_chapter: 2
---

# Plan: Add Auth Middleware

## Chapter 1: Research
**Status:** completed
- [x] Read existing middleware stack
- [x] Identify extension points

## Chapter 2: Implementation
**Status:** in-progress
- [x] Create auth middleware module
- [ ] Add token validation
- [ ] Wire into request pipeline

## Chapter 3: Testing
**Status:** pending
- [ ] Unit tests for token validation
- [ ] Integration tests for protected routes
```

When you start a new session, the memory-sync hook detects in-progress plans:

```
[In-Progress Plans Found]
The following plans were stopped midway and can be resumed:
  - add-auth-middleware.md -- "Add Auth Middleware" (Chapter 2)
Read the plan file to review progress and resume from the current chapter.
```

Use `/planner` to create new plans -- it handles the format and file placement automatically.

### Parallel by default

All workflows spawn independent work as parallel Agent calls in a single message. Steps only run sequentially when there's a true data dependency. This is enforced in `CLAUDE.md` as a behavioral rule, not a suggestion.

### Closed-loop iteration

The `closed-loop.md` workflow and `/orchestrator` skill implement a repeat-until-pass pattern:

```
Check -> Analyze -> Fix (parallel agents) -> Re-check -> Loop or Done
```

With automatic regression detection: if an iteration produces more issues than the previous one, changes are reverted immediately and a different approach is tried.

```
Follow workflows/closed-loop.md.
Check command: npm test -- --reporter=json
Pass condition: 0 failures
Max iterations: 10
```

### Lifecycle hooks

| Hook | Trigger | What it does |
|---|---|---|
| `memory-sync.sh` | Session start | Loads `context/MEMORY.md`, initializes session state, detects in-progress plans |
| `session-end.sh` | Session end | Reminds to update memory and plan progress |
| `edit-log.sh` | After file edit | Logs every edit with timestamp to `.claude/edit-log.jsonl` |
| `track-agents.sh` | After agent spawn | Increments background agent counter |
| `statusline.sh` | Continuous | Shows git state, session time, edits, agents, memory, plan progress |

### Agent skills

Invoke with slash commands to get specialized behavior:

| Command | Purpose |
|---|---|
| `/planner` | Decompose tasks into chapter-based plans, identify parallelism |
| `/implementer` | Write production code following project conventions |
| `/reviewer` | Code review with severity ratings (blocker/major/minor/nit) |
| `/debugger` | Systematic bug investigation |
| `/refactorer` | Safe incremental code restructuring |
| `/architect` | System design and architecture decisions |
| `/orchestrator` | Design and run closed-loop pipelines |

### Workflows

Step-by-step guides with parallel execution built in:

| Workflow | Pattern |
|---|---|
| `bug-fix.md` | Parallel investigate -> fix -> verify loop (max 3 attempts) |
| `new-feature.md` | Parallel explore -> implement -> test -> review |
| `refactor.md` | Parallel assess -> refactor batches -> verify loop |
| `code-review.md` | Read diff -> check dimensions -> rate severity -> verdict |
| `closed-loop.md` | Repeat-until-pass: check -> fix (parallel) -> re-check -> loop |

### Statusline

A live status bar showing everything at a glance:

```
drom-flow v0.1.0 -- main +2/-1/?0 up0down0 -- 12m30s -- edits:8 -- agents:3 -- mem:on -- plan:ch3/5(2check)
```

- Git branch, staged/unstaged/untracked counts, ahead/behind
- Session elapsed time
- Total file edits this session
- Background agents spawned
- Whether session memory is loaded
- Current plan progress: chapter X of Y, Z chapters completed

### Persistent memory

Three files in `context/` carry knowledge across sessions:

- **MEMORY.md** -- Current focus, recent findings, open questions, iteration logs
- **DECISIONS.md** -- Architecture decision records with rationale
- **CONVENTIONS.md** -- Project-specific patterns (naming, imports, testing style)

### Orchestration scripts

`scripts/orchestrate.sh` is a template for automated pipelines:

```bash
# Customize CHECK_CMD, then:
./scripts/orchestrate.sh --iteration 1 --max 10
```

- Accepts `--iteration N` to resume from any point
- Writes JSON reports to `reports/`
- Compares iterations for regression detection
- Exit 0 = all pass, exit 1 = issues remain

## Customizing

- Edit `CLAUDE.md` to add project-specific behavioral rules
- Add new skills in `.claude/skills/<name>/<name>.md`
- Add new workflows in `workflows/`
- Copy and customize `scripts/orchestrate.sh` for your pipeline
- Fill in `context/CONVENTIONS.md` with your project's patterns
- Plans are automatically created in `drom-plans/` by the `/planner` skill

## Design principles

1. **Zero dependencies** -- Only bash and markdown. Works anywhere Claude Code works.
2. **Plain text all the way** -- Everything is readable, editable, and version-controllable.
3. **Parallel by default** -- Sequential execution is the exception, not the rule.
4. **Fail fast, revert faster** -- Regressions are detected and reverted automatically.
5. **Resumable** -- Plans, memory, and orchestration scripts all support picking up where you left off.
6. **Non-destructive install** -- `init.sh` never overwrites existing files. Safe to re-run.

## License

MIT -- see [LICENSE](LICENSE).
