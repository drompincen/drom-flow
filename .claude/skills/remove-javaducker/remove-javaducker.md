---
name: remove-javaducker
description: Remove JavaDucker companion tool configuration from this project
user-invocable: true
---

# Remove JavaDucker

You are removing the JavaDucker companion tool integration from this project.

## Removal Process

1. **Stop watch** — if the `javaducker_watch` MCP tool is available, call it with `action: "stop"` to stop any active file watchers. Ignore errors if the server is not running.

2. **Remove config** — delete `.claude/.state/javaducker.conf` if it exists.

3. **Clean MCP registration** — read `.mcp.json` in the project root:
   - If it contains only the `javaducker` entry, delete the entire `.mcp.json` file
   - If it contains other MCP servers too, remove only the `javaducker` key from `mcpServers` and write the file back

4. **Confirm removal** — print what was removed:
   ```
   JavaDucker removed:
     Deleted: .claude/.state/javaducker.conf
     Cleaned: .mcp.json (javaducker entry removed)
   ```

## What is preserved

- The JavaDucker installation itself (at its own root directory) is untouched
- Any indexed data in JavaDucker's DuckDB database is preserved
- drom-flow hooks and skills gracefully degrade — they check for the config file and skip JavaDucker features when it's absent

## To re-add later

Run `/add-javaducker` with the JavaDucker root path.
