---
title: Add JavaDucker as Optional Companion Tool
status: completed
created: 2026-03-28
updated: 2026-03-28
current_chapter: 7
---

# Plan: Add JavaDucker as Optional Companion Tool

JavaDucker is a semantic code indexing and search system (Spring Boot + DuckDB + MCP). This plan integrates it as an optional companion to drom-flow, so both amplify Claude Code: drom-flow orchestrates *how* work happens; JavaDucker provides deep *codebase memory* with semantic search, dependency analysis, and project mapping.

Integration is 100% optional — everything guards on `.claude/.state/javaducker.conf` existing.

## Chapter 1: Foundation — Guard Pattern and Init
**Status:** completed
**Depends on:** none

- [x] Create `template/.claude/hooks/javaducker-check.sh` — sourced helper with `javaducker_available()` (checks config exists) and `javaducker_healthy()` (curls REST health endpoint)
- [x] Update `init.sh` gitignore loop (~line 143) to include `.mcp.json` pattern
- [x] Update `init.sh` install summary (~line 221) to mention JavaDucker skills

**Notes:**
> Config stored in `.claude/.state/javaducker.conf` (bash key=value: `JAVADUCKER_ROOT`, `JAVADUCKER_HTTP_PORT`). Gitignored, machine-specific. Every hook sources the guard — when config missing, all JavaDucker behavior is a no-op.

## Chapter 2: `/add-javaducker` and `/remove-javaducker` Skills
**Status:** completed
**Depends on:** Chapter 1

- [x] Create `template/.claude/skills/add-javaducker/add-javaducker.md` (~90 lines) — skill that: accepts root path, validates (`run-mcp.sh` + `JavaDuckerMcpServer.java` exist), writes `.claude/.state/javaducker.conf`, creates/merges `.mcp.json` with jbang MCP server entry, optionally indexes current project
- [x] Create `template/.claude/skills/remove-javaducker/remove-javaducker.md` (~40 lines) — full uninstall: deletes `.claude/.state/javaducker.conf`, removes `javaducker` entry from `.mcp.json` (or deletes `.mcp.json` if only entry), stops `javaducker_watch` if active, prints confirmation of what was removed and what user data remains

**Notes:**
> MCP registration uses `.mcp.json` (standard Claude Code project-level MCP config). Entry: `{ "mcpServers": { "javaducker": { "command": "jbang", "args": ["<ROOT>/JavaDuckerMcpServer.java"], "env": { "PROJECT_ROOT": "<ROOT>", "HTTP_PORT": "8080" } } } }`. Must merge if `.mcp.json` already exists. `/remove-javaducker` mirrors the drom-flow `--uninstall` philosophy: remove all managed artifacts, preserve user data (indexed content in DuckDB stays at JavaDucker's location, not in the project).

## Chapter 3: Hook Enhancements
**Status:** completed
**Depends on:** Chapter 1

- [x] Enhance `template/.claude/hooks/memory-sync.sh` — add ~12 lines after plan-check block: source guard, if available check health, print `[JavaDucker: connected]` or `[JavaDucker: configured but server not reachable]`
- [x] Enhance `template/.claude/hooks/statusline.sh` — add ~8 lines before final echo: source guard, set `jd_status="jd:on"` or `"jd:off"`, append to status line
- [x] Create `template/.claude/hooks/javaducker-index.sh` (~25 lines) — PostToolUse hook: source guard, extract `file_path` from `$CLAUDE_TOOL_USE_INPUT`, fire-and-forget `curl -sf -X POST` to REST upload endpoint
- [x] Enhance `template/.claude/hooks/session-end.sh` — add ~5 lines reminding about JavaDucker index freshness if many edits made
- [x] Register `javaducker-index.sh` in `template/.claude/settings.json` PostToolUse array for `Write|Edit|MultiEdit`

**Notes:**
> Hook-based indexing uses REST API (curl) not MCP — hooks run in bash with 3s timeout and can't invoke MCP tools. Fire-and-forget (`&` background) so it doesn't block edits.

## Chapter 4: Skill Enhancements
**Status:** completed
**Depends on:** Chapter 2

- [x] Enhance `implementer.md` — add step: "If JavaDucker available, `javaducker_search` for related patterns before writing code"
- [x] Enhance `debugger.md` — add step: "`javaducker_search` for error messages, `javaducker_explain` on suspect files"
- [x] Enhance `reviewer.md` — add step: "`javaducker_dependents` on changed files for impact analysis"
- [x] Enhance `architect.md` — add step: "`javaducker_search` for existing patterns, `javaducker_map` for orientation"
- [x] Enhance `planner.md` — add step: "search JavaDucker to identify all affected files for accurate chapters"
- [x] Enhance `refactorer.md` — add step: "`javaducker_dependents` to find all callers before restructuring"
- [x] Enhance `orchestrator.md` — add step: "`javaducker_stale` to verify index freshness in loops"

**Notes:**
> Each enhancement is 2-4 lines, conditional ("If JavaDucker is available..."). Skills remain fully functional without it.

## Chapter 5: Workflow and CLAUDE.md Enhancements
**Status:** completed
**Depends on:** Chapter 2

- [x] Enhance `template/workflows/new-feature.md` — add optional parallel JavaDucker search agent in Step 1
- [x] Enhance `template/workflows/bug-fix.md` — add semantic search for error messages in Step 1
- [x] Enhance `template/workflows/code-review.md` — add dependency-aware review step
- [x] Enhance `template/workflows/refactor.md` — add dependents check in Step 1
- [x] Add `## JavaDucker Integration (optional)` section to `template/CLAUDE.md` listing available tools and `/add-javaducker` / `/remove-javaducker` commands

**Notes:**
> Workflow enhancements are 2-3 lines each, always phrased as "if JavaDucker available". CLAUDE.md section goes after Skills section.

## Chapter 6: Version Bump and Verification
**Status:** completed
**Depends on:** Chapter 5

- [x] Bump `VERSION` to 0.3.0
- [x] Test `init.sh` fresh install on clean directory — verify new skill files copied
- [x] Test `init.sh --update` on existing project — verify new files added, user files preserved
- [x] Test `init.sh --check` dry run — verify new files listed
- [x] Verify hooks no-op gracefully when JavaDucker not configured
- [x] Verify statusline shows `jd:on` when configured, omitted when not

**Notes:**
> Fresh install: 29 files (was 25). Statusline: no JD when unconfigured, `JD(off)` when configured but server down, `JD` when running. All hooks exit 0 when unconfigured. Uninstall correctly handles new files.

## Chapter 7: Full JavaDucker Tool Coverage
**Status:** completed
**Depends on:** Chapter 4

All 48 MCP tools mapped to skills/workflows:

- [x] **Session/Decision tools** → planner, architect, debugger: `javaducker_session_context`, `javaducker_search_sessions`, `javaducker_recent_decisions`, `javaducker_extract_decisions`, `javaducker_index_sessions`
- [x] **Content Intelligence tools** → orchestrator, reviewer, architect: `javaducker_classify`, `javaducker_tag`, `javaducker_extract_points`, `javaducker_find_points`, `javaducker_find_by_type`, `javaducker_find_by_tag`, `javaducker_latest`, `javaducker_concepts`, `javaducker_concept_timeline`, `javaducker_synthesize`, `javaducker_link_concepts`, `javaducker_set_freshness`, `javaducker_enrich_queue`, `javaducker_mark_enriched`
- [x] **Health/monitoring tools** → orchestrator, statusline: `javaducker_index_health`, `javaducker_concept_health`, `javaducker_stale_content`, `javaducker_synthesis`, `javaducker_stats`
- [x] **Reladomo ORM tools** → implementer (Java/Spring), refactorer: `javaducker_reladomo_relationships`, `_graph`, `_path`, `_schema`, `_object_files`, `_finders`, `_deepfetch`, `_temporal`, `_config`
- [x] **Artifact lifecycle tools** → add-javaducker, orchestrator, debugger: `javaducker_get_file_text`, `javaducker_get_artifact_status`, `javaducker_wait_for_indexed`, `javaducker_related`, `javaducker_blame`, `javaducker_summarize`
- [x] **Update CLAUDE.md** — documented all 48 tools in 7 categories

**Notes:**
>

---

## Agent Spawn Plan
- Chapter 1, all 3 steps → sequential (init.sh edits depend on guard file)
- Chapter 2, step 1 + step 2 → parallel agents (independent skills)
- Chapter 3, steps 1-4 → parallel agents (independent hook files), then step 5 sequential (settings.json depends on hook file existing)
- Chapter 4, all 7 steps → parallel agents (independent skill files)
- Chapter 5, all 5 steps → parallel agents (independent files)
- Chapter 6 → sequential verification

## Risks
- **MCP server startup time**: First jbang invocation compiles Java (~10-20s). `/add-javaducker` skill should warn about this.
- **`.mcp.json` merge**: Must handle existing `.mcp.json` files — read + merge, not overwrite.
- **REST API availability in hooks**: If server not running, `javaducker-index.sh` must fail silently (already handled by `javaducker_healthy` guard).
- **Path portability**: Both `.mcp.json` and `javaducker.conf` contain absolute paths — fine since both are gitignored. Each dev runs `/add-javaducker` once.

## Open Questions
- Should `/add-javaducker` auto-start the JavaDucker server, or just configure and let the user start it?
- Should `javaducker_watch` be auto-enabled for the project directory on setup?
