---
title: ZIP-Safe Bootstrap + Shared JavaDucker Tree Discovery
status: in-progress
created: 2026-03-29
updated: 2026-03-29
current_chapter: 5
---

# Plan: ZIP-Safe Bootstrap + Shared JavaDucker Tree Discovery

GitHub routes ZIP downloads through `codeload.github.com` when repos contain `.sh` executables, which gets blocked by corporate firewalls. Another session already removed all scripts from git and created `SCRIPTS.md` with embedded sources on the `no-scripts` branch. This plan completes the bootstrap flow and adds tree-aware JavaDucker sharing so nested Claude projects reuse a single instance.

## Chapter 1: Bootstrap Flow — `start-here.md`
**Status:** completed
**Depends on:** none (builds on existing `no-scripts` branch)

- [x] Switch to `no-scripts` branch
- [x] Create `start-here.md` at repo root — the entry point for ZIP users:
  - One-line explanation of why scripts are text-only
  - Command: `claude "Read start-here.md and follow the setup instructions"`
  - Step-by-step instructions Claude follows: read SCRIPTS.md, write each script to its path, chmod +x, copy to template/ per the table
  - Manual copy-paste alternative
- [x] Update `.gitignore` — add `*.sh` and `*.bat` to prevent re-tracking generated scripts
- [x] Update `README.md` — replace `bash init.sh` install instructions with ZIP-safe bootstrap path
- [x] Update `template/CLAUDE.md` — add prerequisite note to "Updating" and "Uninstalling" sections: generate scripts first if not present
- [ ] Commit all changes

**Notes:**
> `start-here.md` must be deterministic enough for Claude to follow without ambiguity. Reference the "Template copies" table at the bottom of SCRIPTS.md for the duplication step.

## Chapter 2: Shared JavaDucker — Tree Discovery
**Status:** completed
**Depends on:** none (can run in parallel with Chapter 1)

- [ ] Edit `javaducker-check.sh` section in SCRIPTS.md — add `javaducker_discover()` function:
  - Walk UP from `$CLAUDE_PROJECT_DIR` looking for ancestor's `.claude/.state/javaducker.conf`
  - If not found, scan ports 8080-8180 using `/dev/tcp` pre-filter + `curl /api/health`
  - Set `JAVADUCKER_SHARED` flag when using a discovered instance
  - Add `javaducker_is_shared()` helper function
- [ ] Modify `javaducker_available()` — try local conf first, then `javaducker_discover()` as fallback
- [ ] Modify `javaducker_start()` — skip starting a new server if shared instance is discovered but down (let owning project handle it)

**Notes:**
> Priority: local conf → ancestor tree walk → port scan. Port scan only runs when tree walk finds nothing (performance). `/dev/tcp` check is fast; `curl` health check only for ports that respond.

## Chapter 3: Shared JavaDucker — Status Reporting
**Status:** completed
**Depends on:** Chapter 2

- [ ] Edit `memory-sync.sh` section in SCRIPTS.md — report shared vs local:
  - Shared: `[JavaDucker: connected to shared instance (port 8080, from /path/to/root)]`
  - Local: `[JavaDucker: connected (port 8080)]` (unchanged)
- [ ] Edit `statusline.sh` section in SCRIPTS.md — show `JD(shared)` vs `JD` vs `JD(off)`

**Notes:**
> The shared path shown in memory-sync.sh should be the `$JAVADUCKER_SHARED` value set by `javaducker_discover()`.

## Chapter 4: Shared JavaDucker — Skills Update
**Status:** completed
**Depends on:** Chapter 2

- [ ] Edit `.claude/skills/add-javaducker/add-javaducker.md` — add Step 0: tree-aware discovery
  - Walk up tree for existing `javaducker.conf`
  - Scan ports for running instance
  - If found, ask user to reuse → create local conf pointing to shared instance, skip server start/DB creation
- [ ] Edit `template/.claude/skills/add-javaducker/add-javaducker.md` — same changes (keep in sync)
- [ ] Edit `.claude/skills/remove-javaducker/remove-javaducker.md` — add shared-instance guard:
  - If DB path is outside current project, do NOT stop the server
  - Only remove local conf and MCP registration
- [ ] Edit `template/.claude/skills/remove-javaducker/remove-javaducker.md` — same changes

**Notes:**
> Edge cases: race condition on start (existing port-conflict logic handles it), parent uninstalls (child degrades gracefully), concurrent indexing (DuckDB handles concurrent reads, REST API serializes writes).

## Chapter 5: Merge, Test & Tag
**Status:** in-progress
**Depends on:** Chapters 1-4

- [ ] Regenerate local `.sh` files from SCRIPTS.md to test changes
- [ ] Test bootstrap: extract ZIP → run `claude "Read start-here.md..."` → verify all scripts generated
- [ ] Test shared JavaDucker: root project with JD → child project discovers it → statusline shows `JD(shared)`
- [ ] Test removal from child: server keeps running for root
- [ ] Merge `no-scripts` into `main`
- [ ] Push to GitHub, verify ZIP download has no CDN redirect
- [ ] Bump VERSION and tag
