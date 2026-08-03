# JavaDucker — tool catalog and knowledge protocol

Loaded only when JavaDucker is actually configured for this project. The session-start hook
surfaces this file when `javaducker_available()` returns true; otherwise none of it enters context.

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
