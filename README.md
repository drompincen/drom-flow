# drom-flow

A lean, dependency-free enhancement kit for [Claude Code](https://docs.anthropic.com/en/docs/claude-code). It gives Claude Code structured workflows, parallel agent orchestration, closed-loop pipelines, persistent memory, chapter-based execution plans, and lifecycle hooks -- using only markdown files, bash scripts, and Claude Code's native features.

It also lets Claude **delegate work to grok CLI sub-agents** running in parallel, which cuts Claude token usage sharply and keeps work moving even when Claude hits its usage limit.

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
| **Agent specialization** | Generic agents | 28 skill profiles (`/planner`, `/reviewer`, `/debugger`, `/grok-fleet`, etc.) with domain-specific instructions |
| **Observability** | No visibility into what's happening | Statusline showing git state, session time, edit count, Claude/grok agent counts, memory status, and plan progress |
| **Resumability** | Start over every session | Session-start hook detects in-progress plans and surfaces them with current chapter |
| **Extra parallelism** | Limited to Claude's own sub-agents | Fan out to **grok CLI sub-agents** — 8 concurrent verified in 13s — with progress, stop control, and stall detection |
| **Token cost** | Every file read and draft burns Claude tokens | Delegate to grok: measured **−64% turns, −65% Claude output, −97% context bytes**, quality held |
| **Hitting the usage limit** | Session dies mid-task, work is stranded | Checkpoint, hand off to detached grok agents that keep running, arm an hourly ping, resume from a ~230-byte record |

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
.claude/hooks/         -- 8 bash lifecycle hooks
.claude/skills/        -- 28 agent skills (/planner, /reviewer, /orchestrator, /grok-fleet, etc.)
context/               -- Memory, decisions, conventions templates
workflows/             -- bug-fix, new-feature, refactor, code-review, closed-loop
.claude/docs/          -- grok fan-out, token economy, df-research guides (gitignored)
scripts/orchestrate.sh -- Template orchestration script for closed-loop pipelines
scripts/grok-fleet.sh  -- Grok sub-agent fan-out (spawn/status/stop/collect/resume)
scripts/df-research.sh -- Deep research pipeline on the grok fleet
scripts/limit-watch.sh -- Usage-limit watcher + hourly wake-up loop
scripts/token-audit.sh -- Measure Claude token usage from the session transcript
scripts/mk-task.sh     -- Generate agent prompts from templates
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

### Grok sub-agent fan-out

Beyond Claude's own sub-agents, drom-flow can fan work out to **grok CLI sub-agents** running in
parallel — the grok CLI binary (not the xAI API), driven from WSL, using the filesystem as the control
plane: `task.md` in, `status.json` / `PROGRESS.md` / `result.json` / `output/` out.

```bash
bash scripts/grok-fleet.sh doctor --live          # preflight
bash scripts/grok-fleet.sh spawn --manifest run.json   # parallel fan-out
bash scripts/grok-fleet.sh status --run-id RUN    # live progress
bash scripts/grok-fleet.sh stop --all             # stop everything
```

Claude keeps the work needing repo context, memory, and final integration; grok takes wide independent
units and cross-model second opinions. Verified at 8 concurrent agents (13s, no degradation), with
budget caps, stall detection, and mid-flight stop. Requires the project to live under `/mnt/<drive>`,
since grok runs as a Windows process. **See [`docs/grok-fleet.md`](docs/grok-fleet.md).**

### Token economy — spend grok, save Claude

Delegating aggressively cuts Claude token usage. Measured on one benchmark (audit 3 skill files),
Claude-only vs delegated to grok:

| Metric | Claude-only | Delegated | Cut |
|---|---|---|---|
| Turns | 11 | 4 | **−64%** |
| Claude output tokens | 7,514 | 2,657 | **−65%** |
| Context bytes | 14,096 | 465 | **−97%** |
| Billable tokens | 38,300 | 5,427 | **−86%** |

Quality held — grok found every defect, with more precise line references than the Claude-only pass.

The wins come from measurement, not intuition: cache reads (turns x resident context) dominate at
36.4M tokens per session versus ~27K for all tool results combined, so the fix is **collapse turns,
stop authoring, keep context small** — not "shorten tool output".

```bash
bash scripts/token-audit.sh mark before   # ... do work ...
bash scripts/token-audit.sh measure before
```

**See [`docs/token-economy.md`](docs/token-economy.md).**

### Surviving the Claude usage limit

Claude tokens are finite; grok's may not be. drom-flow treats **Claude as the interruptible
component**:

```bash
bash scripts/grok-fleet.sh drain --manifest run.json  # detached; finishes without Claude
bash scripts/limit-watch.sh status                    # window usage, budget, reset time
bash scripts/grok-fleet.sh resume --run-id RUN        # pick up exactly where it stopped
```

When Claude nears its limit, `limit-watch.sh` checkpoints every in-flight run, hands queued work to
detached grok agents that keep running, and arms an **hourly wake-up ping** that resumes once quota
returns. Resuming costs a ~230-byte read, not a re-explanation. Finished units are never re-run.

Honest about what is measurable: Claude Code exposes **no live quota meter**. The limit *event* is
exact — a transcript message carrying the reset time — so that trigger is reliable. The **97% figure
is an estimate** against a learned budget, and is suppressed (reported as `null`) until trustworthy,
so it never shows a fabricated percentage. The check runs in a hook: ~0.2s, **zero Claude tokens**.

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

Invoke with slash commands to get specialized behavior.

**Code & engineering:**

| Command | Purpose |
|---|---|
| `/planner` | Decompose tasks into chapter-based plans, identify parallelism |
| `/implementer` | Write production code following project conventions |
| `/reviewer` | Code review with severity ratings (blocker/major/minor/nit) |
| `/debugger` | Systematic bug investigation |
| `/refactorer` | Safe incremental code restructuring |
| `/architect` | System design and architecture decisions |
| `/orchestrator` | Design and run closed-loop pipelines |
| `/ascii-architect` | Convert thoughts, architectures, and processes into ASCII art diagrams |
| `/api-expert` | Contract-first REST APIs (OpenAPI 3.1, Spring Boot, security, rate limiting) |
| `/grok-fleet` | Fan out parallel grok CLI sub-agents from WSL — filesystem progress, monitoring, stop control |
| `/df-research` | Deep research on the grok fleet — sweep, contradiction audit, adversarial critics, cite-check gate |

**Web platform quality** (from [addyosmani/web-quality-skills](https://github.com/addyosmani/web-quality-skills), MIT):

| Command | Purpose |
|---|---|
| `/web-quality-audit` | Comprehensive web quality audit (orchestrates the QA skills below) |
| `/accessibility` | WCAG 2.2 audit and fixes (screen reader, keyboard nav) |
| `/seo` | Search engine visibility (meta, structured data, sitemap) |
| `/performance` | Web performance optimization (load time, bundle size) |
| `/core-web-vitals` | Optimize LCP, INP, CLS for page experience |
| `/best-practices` | Modern web best practices (security, code quality, modernization) |

**Product management** (from [deanpeters/product-manager-skills](https://github.com/deanpeters/product-manager-skills)):

| Command | Purpose |
|---|---|
| `/discovery-process` | Full discovery cycle from problem hypothesis to validated solution |
| `/problem-statement` | User-centered problem statement (who, what, why, how it feels) |
| `/jobs-to-be-done` | Uncover customer jobs, pains, and gains (JTBD framework) |
| `/customer-journey-map` | Map customer experience across stages, touchpoints, emotions |
| `/user-story-mapping` | Build a user story map (activities → steps → tasks → release slices) |
| `/epic-breakdown-advisor` | Split epics into stories using Humanizing Work patterns |
| `/user-story` | Write user stories (Mike Cohn format with Gherkin acceptance criteria) |
| `/user-story-splitting` | Break large stories into deliverable slices |
| `/prd-development` | Build structured PRDs (problem → users → solution → success criteria) |
| `/roadmap-planning` | Strategic roadmap (prioritization, epics, sequencing) |
| `/prioritization-advisor` | Choose the right prioritization framework (RICE, ICE, value/effort) |

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
drom-flow v0.8.0 -- main +2/-1/?0 -- 12m30s -- edits:8 -- C:1 G:8 -- mem:on -- plan:ch3/5(2check)
```

- Git branch, staged/unstaged/untracked counts, ahead/behind
- Session elapsed time
- Total file edits this session
- **`C:` Claude sub-agents and `G:` grok agents, counted separately** -- the delegation split at a glance
- Whether session memory is loaded
- Current plan progress: chapter X of Y, Z chapters completed
- `armed` / `ping-due` when the usage-limit watcher is waiting on a quota reset

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

Other installed scripts:

| Script | Purpose |
|---|---|
| `grok-fleet.sh` | Grok sub-agent fan-out: `doctor`, `spawn`, `status`, `stop`, `collect`, `drain`, `checkpoint`, `resume`, `verify` |
| `limit-watch.sh` | Usage-limit watcher: `status`, `check`, `arm`, `ping`, `verify` |
| `token-audit.sh` | Measure Claude turns/output/context from the live session transcript |
| `mk-task.sh` | Generate grok task prompts from `scripts/task-templates/` |
| `bench-audit.sh` | Worked example: encapsulated fan-out in one command |

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
