#!/bin/bash
# drom-flow init — install or update drom-flow in a project
#
# Usage:
#   bash init.sh [target-dir]            # Fresh install (skip existing files)
#   bash init.sh --update [target-dir]   # Update drom-flow files, preserve user content
#   bash init.sh --check [target-dir]    # Show what would be updated (dry run)
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE_DIR="$SCRIPT_DIR/template"

# Parse flags
MODE="install"
TARGET_DIR=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --update) MODE="update"; shift ;;
    --check)  MODE="check"; shift ;;
    *)        TARGET_DIR="$1"; shift ;;
  esac
done
TARGET_DIR="${TARGET_DIR:-.}"

if [ ! -d "$TEMPLATE_DIR" ]; then
  echo "Error: template/ directory not found at $SCRIPT_DIR"
  exit 1
fi

# Files that belong to the user and should NEVER be overwritten on update.
# These may contain project-specific customizations.
USER_FILES=(
  "CLAUDE.md"
  "context/MEMORY.md"
  "context/DECISIONS.md"
  "context/CONVENTIONS.md"
  "scripts/orchestrate.sh"
)

is_user_file() {
  local rel="$1"
  for uf in "${USER_FILES[@]}"; do
    [ "$rel" = "$uf" ] && return 0
  done
  return 1
}

CURRENT_VERSION=""
if [ -f "$TARGET_DIR/VERSION" ]; then
  CURRENT_VERSION=$(tr -d '[:space:]' < "$TARGET_DIR/VERSION")
fi
NEW_VERSION=$(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION")

if [ "$MODE" = "check" ]; then
  echo "drom-flow update check for: $(cd "$TARGET_DIR" && pwd)"
  echo "  Installed version: ${CURRENT_VERSION:-none}"
  echo "  Available version: $NEW_VERSION"
  echo ""
  echo "Files that would be updated (--update):"
  would_update=0
  while IFS= read -r -d '' file; do
    rel="${file#$TEMPLATE_DIR/}"
    target="$TARGET_DIR/$rel"
    if is_user_file "$rel"; then
      continue
    fi
    if [ -f "$target" ]; then
      if ! diff -q "$file" "$target" > /dev/null 2>&1; then
        echo "  changed: $rel"
        would_update=$((would_update + 1))
      fi
    else
      echo "  new:     $rel"
      would_update=$((would_update + 1))
    fi
  done < <(find "$TEMPLATE_DIR" -type f -print0)
  echo ""
  echo "$would_update file(s) would be updated."
  echo ""
  echo "Protected (never overwritten):"
  for uf in "${USER_FILES[@]}"; do
    [ -f "$TARGET_DIR/$uf" ] && echo "  $uf"
  done
  exit 0
fi

if [ "$MODE" = "update" ]; then
  echo "Updating drom-flow in: $(cd "$TARGET_DIR" && pwd)"
  echo "  ${CURRENT_VERSION:-none} → $NEW_VERSION"
  echo ""
  echo "Protected files (will NOT be overwritten):"
  for uf in "${USER_FILES[@]}"; do
    [ -f "$TARGET_DIR/$uf" ] && echo "  $uf"
  done
  echo ""
else
  echo "Installing drom-flow into: $(cd "$TARGET_DIR" && pwd)"
  echo ""
fi

copied=0
updated=0
skipped=0

while IFS= read -r -d '' file; do
  rel="${file#$TEMPLATE_DIR/}"
  target="$TARGET_DIR/$rel"
  target_dir="$(dirname "$target")"

  mkdir -p "$target_dir"

  if [ -f "$target" ]; then
    if [ "$MODE" = "update" ]; then
      if is_user_file "$rel"; then
        echo "  protect: $rel"
        skipped=$((skipped + 1))
      elif diff -q "$file" "$target" > /dev/null 2>&1; then
        skipped=$((skipped + 1))
      else
        cp "$file" "$target"
        echo "  update:  $rel"
        updated=$((updated + 1))
      fi
    else
      echo "  skip: $rel (already exists)"
      skipped=$((skipped + 1))
    fi
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

# Copy VERSION file
if [ -f "$SCRIPT_DIR/VERSION" ] && ! [ "$SCRIPT_DIR/VERSION" -ef "$TARGET_DIR/VERSION" ]; then
  cp "$SCRIPT_DIR/VERSION" "$TARGET_DIR/VERSION"
  echo "  copy: VERSION"
fi

# Make scripts executable
chmod +x "$TARGET_DIR/.claude/hooks/"*.sh 2>/dev/null || true
chmod +x "$TARGET_DIR/scripts/"*.sh 2>/dev/null || true

echo ""
if [ "$MODE" = "update" ]; then
  echo "Done. Updated $updated files, copied $copied new, skipped $skipped unchanged/protected."
else
  echo "Done. Copied $copied files, skipped $skipped existing."
fi
echo ""
echo "What was installed:"
echo "  CLAUDE.md              — behavioral rules + parallelism + closed-loop + plan protocol"
echo "  .claude/settings.json  — hooks, statusline, permissions"
echo "  .claude/hooks/         — bash lifecycle hooks"
echo "  .claude/skills/        — 7 agent skills (/planner, /reviewer, /orchestrator, etc.)"
echo "  context/               — memory, decisions, conventions templates"
echo "  workflows/             — bug-fix, new-feature, refactor, code-review, closed-loop"
echo "  scripts/orchestrate.sh — template orchestration script for closed-loop pipelines"
echo "  drom-plans/            — chapter-based execution plans with progress tracking"
echo "  reports/               — iteration reports from orchestration runs"
