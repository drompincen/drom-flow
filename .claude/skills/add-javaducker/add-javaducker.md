---
name: add-javaducker
description: Configure JavaDucker as an optional companion tool for semantic code search and indexing
user-invocable: true
---

# Add JavaDucker

You are setting up JavaDucker as a companion tool for this project. JavaDucker provides semantic code indexing, search, dependency analysis, and project mapping via MCP tools.

## What you need

The user must provide the **JavaDucker root folder** — the directory containing `JavaDuckerMcpServer.java` and `run-mcp.sh`.

If the user doesn't provide a path, look for it in sibling directories:
- `../code-helper`
- `../javaducker`

## Setup Process

1. **Get the path** — ask the user or auto-detect from sibling directories
2. **Validate** — confirm these files exist at the root:
   - `JavaDuckerMcpServer.java`
   - `run-mcp.sh`
   - `run-server.sh`
   If any are missing, stop and report the error.

3. **Write config** — create `.claude/.state/javaducker.conf`:
   ```
   JAVADUCKER_ROOT=/absolute/path/to/javaducker
   JAVADUCKER_HTTP_PORT=8080
   ```

4. **Register MCP server** — create or merge `.mcp.json` in the project root:
   ```json
   {
     "mcpServers": {
       "javaducker": {
         "command": "jbang",
         "args": ["JAVADUCKER_ROOT/JavaDuckerMcpServer.java"],
         "env": {
           "PROJECT_ROOT": "JAVADUCKER_ROOT",
           "HTTP_PORT": "8080"
         }
       }
     }
   }
   ```
   Replace `JAVADUCKER_ROOT` with the actual absolute path.

   **If `.mcp.json` already exists**, read it first and merge the `javaducker` key into the existing `mcpServers` object. Do not overwrite other MCP servers.

5. **Start the server** — launch the JavaDucker server in the background:
   ```bash
   nohup bash <JAVADUCKER_ROOT>/run-server.sh >/dev/null 2>&1 &
   ```
   Wait up to 10 seconds for it to become healthy (poll `/api/health`). The server auto-starts on future sessions via the memory-sync hook, so the user never needs to start it manually.

6. **Index the project** — once the server is healthy, index the current project:
   ```bash
   bash <JAVADUCKER_ROOT>/run-client.sh upload-dir --root <PROJECT_DIR> --ext .java,.xml,.md,.yml,.yaml,.json,.properties,.gradle,.kt,.py,.go,.rs,.ts,.js
   ```
   This runs in the background. The user can start working immediately — results become searchable as they're indexed.

7. **Index past sessions** (optional) — ask the user if they want to index past Claude Code sessions for this project. If yes, use `javaducker_index_sessions` with the project's sessions path (`~/.claude/projects/<hash>/`). This makes prior conversations searchable via `javaducker_search_sessions` and `javaducker_session_context`.

8. **Confirm setup** — print a short confirmation:
   ```
   JavaDucker ready. Look for "JD" in the statusline.
     Root:   /path/to/javaducker
     Server: running (port 8080)
     Index:  started for current project
   ```

## How it works for the user

After setup, JavaDucker is invisible:
- **Statusline** shows `JD` when active, `JD(off)` if the server is down
- **Server auto-starts** on each session via the memory-sync hook
- **Edited files auto-index** via the post-edit hook
- **All drom-flow skills** automatically use JavaDucker for deeper search when available
- **No CLI commands needed** — everything happens through MCP tools and hooks

To remove: use `/remove-javaducker`

## Important notes

- First MCP connection may take 10-20 seconds (jbang compiles the Java file on first run)
- The `.mcp.json` file is gitignored (contains absolute paths)
- The config is machine-specific — each developer runs `/add-javaducker` once
