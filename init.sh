#!/bin/bash
# drom-flow init — install drom-flow into the current project
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE_DIR="$SCRIPT_DIR/template"
TARGET_DIR="${1:-.}"

if [ ! -d "$TEMPLATE_DIR" ]; then
  echo "Error: template/ directory not found at $SCRIPT_DIR"
  exit 1
fi

echo "Installing drom-flow into: $(cd "$TARGET_DIR" && pwd)"
echo ""

copied=0
skipped=0

# Copy template files, skip existing ones
while IFS= read -r -d '' file; do
  rel="${file#$TEMPLATE_DIR/}"
  target="$TARGET_DIR/$rel"
  target_dir="$(dirname "$target")"

  mkdir -p "$target_dir"

  if [ -f "$target" ]; then
    echo "  skip: $rel (already exists)"
    skipped=$((skipped + 1))
  else
    cp "$file" "$target"
    echo "  copy: $rel"
    copied=$((copied + 1))
  fi
done < <(find "$TEMPLATE_DIR" -type f -print0)

# Create session state directory (ephemeral, not committed)
mkdir -p "$TARGET_DIR/.claude/.state"

# Create plans directory
mkdir -p "$TARGET_DIR/drom-plans"

# Add .state and edit-log to .gitignore if not already present
gitignore="$TARGET_DIR/.gitignore"
for pattern in ".claude/.state/" ".claude/edit-log.jsonl"; do
  if [ ! -f "$gitignore" ] || ! grep -qF "$pattern" "$gitignore"; then
    echo "$pattern" >> "$gitignore"
  fi
done

# Copy VERSION file (skip if same file)
if [ -f "$SCRIPT_DIR/VERSION" ] && ! [ "$SCRIPT_DIR/VERSION" -ef "$TARGET_DIR/VERSION" ]; then
  cp "$SCRIPT_DIR/VERSION" "$TARGET_DIR/VERSION"
  echo "  copy: VERSION"
fi

# Make scripts executable
chmod +x "$TARGET_DIR/.claude/hooks/"*.sh 2>/dev/null || true
chmod +x "$TARGET_DIR/scripts/"*.sh 2>/dev/null || true

echo ""
echo "Done. Copied $copied files, skipped $skipped existing."
echo ""
echo "What was installed:"
echo "  CLAUDE.md              — behavioral rules + parallelism + closed-loop protocol"
echo "  .claude/settings.json  — hooks, statusline, permissions"
echo "  .claude/hooks/         — bash lifecycle hooks"
echo "  .claude/skills/        — 7 agent skills (/planner, /reviewer, /orchestrator, etc.)"
echo "  context/               — memory, decisions, conventions templates"
echo "  workflows/             — bug-fix, new-feature, refactor, code-review, closed-loop"
echo "  scripts/orchestrate.sh — template orchestration script for closed-loop pipelines"
echo "  drom-plans/            — chapter-based execution plans with progress tracking"
echo "  reports/               — iteration reports from orchestration runs"
