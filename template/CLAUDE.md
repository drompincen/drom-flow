# drom-flow — Project Configuration

> **drom-flow** is active in this project. It provides workflows, parallel agent orchestration, closed-loop pipelines, persistent memory, and lifecycle hooks. The statusline shows drom-flow branding, git state, session time, edit/agent counts, and memory status.

## Behavioral Rules

- Do what has been asked; nothing more, nothing less
- NEVER create files unless absolutely necessary for the goal
- ALWAYS prefer editing an existing file to creating a new one
- NEVER proactively create documentation files unless explicitly requested
- NEVER save working files, tests, or docs to the root folder
- ALWAYS read a file before editing it
- Keep files under 500 lines
- NEVER commit secrets, credentials, or .env files

## File Organization

- Use `src/` for source code
- Use `tests/` for test files
- Use `docs/` for documentation
- Use `scripts/` for utility scripts and orchestration scripts
- Use `config/` for configuration files
- Use `drom-plans/` for execution plans (chapter-based, with progress tracking)

## Parallelism — ALWAYS parallel by default

- EVERY task must be analyzed for parallelism BEFORE execution
- Batch ALL related file reads in ONE message
- Batch ALL file edits in ONE message
- Batch ALL independent Bash commands in ONE message
- Spawn ALL independent Agent calls in ONE message with `run_in_background: true`
- After spawning background agents, STOP and wait for results — do NOT poll
- When a task has multiple independent fix targets, spawn one Agent per target in a single message
- When reviewing results from parallel agents, read ALL results before deciding next action
- Sequential steps run only when there is a true data dependency on a prior step

## Closed-Loop Execution

When a workflow specifies a loop (repeat-until-pass), follow this protocol:

1. **Read the workflow** to identify: steps, pass condition, max iterations, and what to capture per iteration
2. **Run the check/capture step** to establish baseline metrics
3. **Analyze results** — categorize issues, group by fix type
4. **Spawn parallel fix agents** — one Agent per independent issue category, ALL in one message
5. **Wait for all agents** — review ALL results together
6. **Re-run the check** — compare metrics to previous iteration
7. **Log iteration** — append to `context/MEMORY.md`: iteration number, pass/fail counts, key fixes, regressions
8. **Decide**:
   - All pass → exit loop, run final confirmation
   - Regression detected → revert, log what failed, try different approach
   - Issues remain and under max iterations → go to step 3
   - Max iterations reached → stop, report remaining issues
9. **On exit** — write final summary to `context/MEMORY.md`

### Regression handling
- If an iteration produces MORE issues than the previous one, it is a regression
- Revert the changes from that iteration immediately
- Log what was attempted and why it regressed
- Try a different fix approach in the next iteration
- Never repeat the same fix that caused a regression

## Security

- NEVER hardcode API keys, secrets, or credentials in source files
- NEVER commit .env files or any file containing secrets
- Always validate user input at system boundaries
- Always sanitize file paths to prevent directory traversal

## Memory Protocol

- At session start, read `context/MEMORY.md` for ongoing context
- Before session ends, update `context/MEMORY.md` with progress and findings
- Log important architectural decisions in `context/DECISIONS.md`
- Check `context/CONVENTIONS.md` for project-specific patterns before writing code
- During loops, append iteration results to `context/MEMORY.md` after each iteration

## Knowledge Protocol (when JavaDucker is available)

JavaDucker stores. Claude curates. You are responsible for keeping the knowledge base accurate.

### After every task
- **Record decisions** — any non-obvious choice you made → `javaducker_extract_decisions`
- **Tag new patterns** — new conventions or patterns introduced → `javaducker_tag`
- **Extract insights** — root causes found, risks identified → `javaducker_extract_points`

### When you change something that invalidates prior knowledge
- **Check for contradicted decisions** — `javaducker_find_points` with `DECISION` type in the affected area
- **Supersede stale artifacts** — `javaducker_set_freshness` → `superseded` on the old artifact
- **Synthesize** — `javaducker_synthesize` to compress the old artifact into a summary reference (what it said, why it's obsolete, what replaced it)
- **Link concepts** — `javaducker_link_concepts` to connect old concepts to new artifacts

### What to never do
- Don't run enrichment mechanically — always read the content before classifying or extracting points
- Don't supersede artifacts that are still valid just because they're old
- Don't skip decision recording — the decision chain is the most valuable thread in the knowledge base

### Maintenance
- Follow `workflows/javaducker-hygiene.md` for periodic index maintenance
- The session-end hook will prompt when un-enriched artifacts are detected

## Plan Protocol

- All plans are created in `drom-plans/` as markdown files with YAML frontmatter
- Plans are broken into **chapters** — each chapter is a logical phase of work with its own steps
- Chapter status tracks progress: `pending` → `in-progress` → `completed`
- At session start, the memory-sync hook checks for `status: in-progress` plans and surfaces them
- When resuming a plan, read the plan file, find the current chapter, and continue from the first unchecked step
- Update step checkboxes (`[ ]` → `[x]`) and chapter status as work progresses
- When all chapters are done, set the plan's frontmatter `status: completed`
- Use `/planner` to create new plans — it handles the format and file creation

## Orchestration Scripts

- Orchestration scripts live in `scripts/` and automate multi-step pipelines
- Scripts should be idempotent — safe to re-run from any iteration
- Scripts must accept `--iteration N` to resume from a specific point
- Scripts must write machine-readable output (JSON) for Claude to parse
- Scripts must exit with code 0 on success, non-zero on failure
- Use `scripts/orchestrate.sh` as the template for new orchestration scripts

## Workflows

When the task matches a common pattern, follow the corresponding workflow:

- Bug fixes: follow `workflows/bug-fix.md`
- New features: follow `workflows/new-feature.md`
- Refactoring: follow `workflows/refactor.md`
- Code reviews: follow `workflows/code-review.md`
- Closed-loop QA: follow `workflows/closed-loop.md`
- JavaDucker index maintenance: follow `workflows/javaducker-hygiene.md`

## Skills

Use these agent profiles when the task calls for a specialized role:

- `/planner` — Task decomposition, parallel execution planning
- `/implementer` — Writing production code
- `/reviewer` — Code review with severity ratings
- `/debugger` — Systematic bug investigation
- `/refactorer` — Safe code restructuring
- `/architect` — System design and architecture decisions
- `/orchestrator` — Design and run closed-loop pipelines
- `/ascii-architect` — Convert thoughts, architectures, and processes into ASCII art diagrams
- `/add-javaducker` — Set up JavaDucker companion tool for semantic code search
- `/remove-javaducker` — Remove JavaDucker integration

## JavaDucker Integration (optional)

When JavaDucker is configured (via `/add-javaducker`), 48 MCP tools become available:

**Core search & indexing:**
- `javaducker_search` — semantic/hybrid/exact search across all indexed code
- `javaducker_explain` — comprehensive file context (summary, deps, dependents, blame)
- `javaducker_index_directory` / `javaducker_index_file` — index code into JavaDucker
- `javaducker_map` — project structure overview
- `javaducker_watch` — auto-index on file changes

**Impact analysis:**
- `javaducker_dependencies` / `javaducker_dependents` — import/dependency graph
- `javaducker_related` — co-changed files (git history)
- `javaducker_blame` — git blame with grouping

**Content intelligence:**
- `javaducker_classify` — classify documents (ADR, DESIGN_DOC, PLAN, etc.)
- `javaducker_tag` / `javaducker_find_by_tag` — tag and search by tag
- `javaducker_find_by_type` — find artifacts by document type
- `javaducker_extract_points` / `javaducker_find_points` — extract and search salient points (DECISION, RISK, ACTION, etc.)
- `javaducker_concepts` / `javaducker_concept_timeline` — concept map and evolution
- `javaducker_latest` — most current artifact on a topic
- `javaducker_synthesize` / `javaducker_synthesis` — compress stale artifacts into summaries
- `javaducker_link_concepts` — cross-document concept links
- `javaducker_set_freshness` — mark artifacts current/stale/superseded

**Session memory:**
- `javaducker_index_sessions` — index past Claude Code conversations
- `javaducker_search_sessions` — search past conversations
- `javaducker_session_context` — full historical context for a topic
- `javaducker_extract_decisions` / `javaducker_recent_decisions` — record and recall decisions from sessions

**Health & monitoring:**
- `javaducker_index_health` — overall index freshness with recommendations
- `javaducker_concept_health` — concept graph health (active/fading/cold)
- `javaducker_stale` / `javaducker_stale_content` — detect out-of-date files
- `javaducker_stats` — aggregate indexing statistics

**Reladomo ORM (Java projects):**
- `javaducker_reladomo_relationships` / `_graph` / `_path` — object model navigation
- `javaducker_reladomo_schema` / `_object_files` / `_finders` — DDL, files, query patterns
- `javaducker_reladomo_deepfetch` / `_temporal` / `_config` — eager loading, temporal, runtime config

The integration is seamless:
- The server auto-starts on session start
- Edited files are auto-indexed via post-edit hooks
- All skills and workflows automatically use JavaDucker when available
- The statusline shows `JD` when active

To set up: `/add-javaducker`
To remove: `/remove-javaducker`

## Updating drom-flow

**Prerequisite:** If the drom-flow source directory does not contain `init.sh` (e.g., after downloading a new ZIP), generate scripts first by running `claude "Read start-here.md and follow the setup instructions"` in the drom-flow directory.

When the user asks to update drom-flow (e.g., "update to latest drom-flow", "update drom-flow"):

1. **Find the drom-flow source** — read `.claude/.state/drom-flow.conf` to get `DROM_FLOW_HOME`
2. **Pull latest** — run `git -C "$DROM_FLOW_HOME" pull` to fetch the newest version
3. **Preview changes** — run `bash "$DROM_FLOW_HOME/init.sh" --check .` and show the user what would change
4. **Apply the update** — run `bash "$DROM_FLOW_HOME/init.sh" --update .`

```bash
# Read the saved drom-flow location
source .claude/.state/drom-flow.conf

# Pull latest
git -C "$DROM_FLOW_HOME" pull

# Check what would change (dry run)
bash "$DROM_FLOW_HOME/init.sh" --check .

# Apply the update
bash "$DROM_FLOW_HOME/init.sh" --update .
```

`--update` overwrites drom-flow managed files (hooks, skills, workflows, settings) but **never touches** project-specific files: `CLAUDE.md`, `context/MEMORY.md`, `context/DECISIONS.md`, `context/CONVENTIONS.md`, `scripts/orchestrate.sh`. Plans in `drom-plans/` and reports are also preserved.

## Uninstalling drom-flow

**Prerequisite:** If the drom-flow source directory does not contain `init.sh` (e.g., after downloading a new ZIP), generate scripts first by running `claude "Read start-here.md and follow the setup instructions"` in the drom-flow directory.

When the user asks to uninstall drom-flow:

1. **Find the drom-flow source** — read `.claude/.state/drom-flow.conf` to get `DROM_FLOW_HOME`
2. **Preview** — run `bash "$DROM_FLOW_HOME/init.sh" --uninstall-check .`
3. **Uninstall** — run `bash "$DROM_FLOW_HOME/init.sh" --uninstall .`

`--uninstall` removes all drom-flow managed files (hooks, skills, workflows, settings, VERSION) and cleans up empty directories and gitignore entries. It **never removes** user-owned files: `CLAUDE.md`, `context/MEMORY.md`, `context/DECISIONS.md`, `context/CONVENTIONS.md`, `scripts/orchestrate.sh`, or any plans in `drom-plans/`.
