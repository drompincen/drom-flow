# drom-flow -- Bootstrap Setup

drom-flow distributes scripts as embedded text in `SCRIPTS.md` rather than as executable files. This avoids GitHub CDN redirect issues that cause ZIP downloads to fail behind corporate firewalls.

## Quick start

Run this in the drom-flow directory:

```
claude "Read start-here.md and follow the setup instructions"
```

Claude will generate all scripts automatically. Then use `init.sh` to install drom-flow into your projects.

## Setup instructions (for Claude to follow)

When Claude reads this file, it must execute these steps in order:

1. Read `SCRIPTS.md` in this same directory.
2. For each section heading that specifies a file path (e.g., `## .claude/hooks/edit-log.sh`, `## init.sh`, `## scripts/orchestrate.sh`), extract the bash code block content and write it to that exact path relative to this directory.
3. Run `chmod +x` on every `.sh` file that was generated.
4. Copy each file from `.claude/hooks/` to `template/.claude/hooks/` (same filename).
5. Copy `scripts/orchestrate.sh` to `template/scripts/orchestrate.sh`.
6. Print a summary listing every file that was generated and every file that was copied.

Do not skip any section. Do not modify the code block content. Write files exactly as they appear in `SCRIPTS.md`.

## Manual alternative

If you prefer to set up manually without Claude:

1. Open `SCRIPTS.md` in a text editor.
2. For each `## <file-path>` section, copy the fenced code block and save it to that path relative to this directory.
3. Make all `.sh` files executable: `find . -name "*.sh" -exec chmod +x {} \;`
4. Copy hook scripts to the template directory:
   - `cp .claude/hooks/*.sh template/.claude/hooks/`
   - `cp scripts/orchestrate.sh template/scripts/orchestrate.sh`
