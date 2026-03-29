#!/bin/bash
# drom-flow init — install, update, or uninstall drom-flow in a project
#
# Usage:
#   bash init.sh [target-dir]              # Fresh install (skip existing files)
#   bash init.sh --update [target-dir]     # Update drom-flow files, preserve user content
#   bash init.sh --check [target-dir]      # Show what would be updated (dry run)
#   bash init.sh --uninstall [target-dir]  # Remove drom-flow, preserve user content
#   bash init.sh --uninstall-check [dir]   # Show what would be removed (dry run)
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
    --uninstall) MODE="uninstall"; shift ;;
    --uninstall-check) MODE="uninstall-check"; shift ;;
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

# --- Uninstall: collect managed files ---
# Managed files = everything from template/ that is NOT a user file, plus VERSION and .state/
collect_managed_files() {
  local target="$1"
  managed=()
  # Files from template
  while IFS= read -r -d '' file; do
    rel="${file#$TEMPLATE_DIR/}"
    if ! is_user_file "$rel" && [ -f "$target/$rel" ]; then
      managed+=("$rel")
    fi
  done < <(find "$TEMPLATE_DIR" -type f -print0)
  # Extra managed files not in template/
  [ -f "$target/VERSION" ] && managed+=("VERSION")
  # Ephemeral state
  [ -d "$target/.claude/.state" ] && managed+=(".claude/.state/")
  [ -f "$target/.claude/edit-log.jsonl" ] && managed+=(".claude/edit-log.jsonl")
  [ -d "$target/.claude/.javaducker" ] && managed+=(".claude/.javaducker/")
  true
}

# Directories that drom-flow created (remove only if empty after cleanup)
MANAGED_DIRS=(
  "workflows"
  "reports"
  "drom-plans"
  ".claude/skills/architect"
  ".claude/skills/debugger"
  ".claude/skills/implementer"
  ".claude/skills/orchestrator"
  ".claude/skills/planner"
  ".claude/skills/refactorer"
  ".claude/skills/reviewer"
  ".claude/skills/add-javaducker"
  ".claude/skills/remove-javaducker"
  ".claude/.javaducker"
  ".claude/skills"
  ".claude/hooks"
  ".claude"
  "context"
  "scripts"
)

if [ "$MODE" = "uninstall-check" ]; then
  echo "drom-flow uninstall check for: $(cd "$TARGET_DIR" && pwd)"
  echo "  Installed version: ${CURRENT_VERSION:-none}"
  echo ""
  collect_managed_files "$TARGET_DIR"
  echo "Files that would be REMOVED (--uninstall):"
  for rel in "${managed[@]}"; do
    echo "  remove: $rel"
  done
  echo ""
  echo "Directories that would be removed if empty:"
  for d in "${MANAGED_DIRS[@]}"; do
    [ -d "$TARGET_DIR/$d" ] && echo "  rmdir:  $d/"
  done
  echo ""
  echo "Protected (NEVER removed):"
  for uf in "${USER_FILES[@]}"; do
    [ -f "$TARGET_DIR/$uf" ] && echo "  keep:   $uf"
  done
  # Plans
  if [ -d "$TARGET_DIR/drom-plans" ]; then
    plan_count=$(find "$TARGET_DIR/drom-plans" -name '*.md' 2>/dev/null | wc -l | tr -d ' ')
    [ "$plan_count" -gt 0 ] && echo "  keep:   drom-plans/ ($plan_count plan file(s))"
  fi
  echo ""
  echo "Gitignore entries that would be cleaned:"
  for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/"; do
    if [ -f "$TARGET_DIR/.gitignore" ] && grep -qF "$pattern" "$TARGET_DIR/.gitignore"; then
      echo "  clean:  $pattern"
    fi
  done
  exit 0
fi

if [ "$MODE" = "uninstall" ]; then
  echo "Uninstalling drom-flow from: $(cd "$TARGET_DIR" && pwd)"
  echo "  Version: ${CURRENT_VERSION:-unknown}"
  echo ""

  collect_managed_files "$TARGET_DIR"
  removed=0
  kept=0

  # Remove managed files
  for rel in "${managed[@]}"; do
    target="$TARGET_DIR/$rel"
    if [ -d "$target" ]; then
      rm -rf "$target"
      echo "  remove: $rel"
      removed=$((removed + 1))
    elif [ -f "$target" ]; then
      rm -f "$target"
      echo "  remove: $rel"
      removed=$((removed + 1))
    fi
  done

  # Show protected files
  echo ""
  echo "Protected (kept):"
  for uf in "${USER_FILES[@]}"; do
    if [ -f "$TARGET_DIR/$uf" ]; then
      echo "  keep:   $uf"
      kept=$((kept + 1))
    fi
  done
  if [ -d "$TARGET_DIR/drom-plans" ]; then
    plan_count=$(find "$TARGET_DIR/drom-plans" -name '*.md' 2>/dev/null | wc -l | tr -d ' ')
    if [ "$plan_count" -gt 0 ]; then
      echo "  keep:   drom-plans/ ($plan_count plan file(s))"
      kept=$((kept + plan_count))
    fi
  fi

  # Clean up empty managed directories (order matters — children before parents)
  echo ""
  dir_removed=0
  for d in "${MANAGED_DIRS[@]}"; do
    target="$TARGET_DIR/$d"
    if [ -d "$target" ] && [ -z "$(ls -A "$target" 2>/dev/null)" ]; then
      rmdir "$target"
      echo "  rmdir:  $d/"
      dir_removed=$((dir_removed + 1))
    fi
  done

  # Clean gitignore entries added by drom-flow
  gitignore="$TARGET_DIR/.gitignore"
  if [ -f "$gitignore" ]; then
    cleaned=0
    for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/"; do
      if grep -qF "$pattern" "$gitignore"; then
        sed -i "\|^${pattern}$|d" "$gitignore"
        cleaned=$((cleaned + 1))
      fi
    done
    # Remove .gitignore if it's now empty (only whitespace left)
    if [ ! -s "$gitignore" ] || ! grep -q '[^[:space:]]' "$gitignore"; then
      rm -f "$gitignore"
      echo "  remove: .gitignore (was empty)"
    elif [ "$cleaned" -gt 0 ]; then
      echo "  clean:  .gitignore ($cleaned drom-flow entries removed)"
    fi
  fi

  echo ""
  echo "Done. Removed $removed files, $dir_removed directories. Kept $kept protected files."
  echo ""
  echo "To fully clean up, you may also want to remove:"
  echo "  - CLAUDE.md (your project config — kept in case you customized it)"
  echo "  - context/ (your memory, decisions, conventions — kept to preserve your notes)"
  echo "  - drom-plans/ (your execution plans — kept to preserve your work)"
  echo "  - scripts/orchestrate.sh (your orchestration script — kept if customized)"
  exit 0
fi

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

# Save drom-flow source location so Claude can find it for future updates
echo "DROM_FLOW_HOME=$SCRIPT_DIR" > "$TARGET_DIR/.claude/.state/drom-flow.conf"

# Create plans directory
mkdir -p "$TARGET_DIR/drom-plans"

# Add .state, edit-log, and .mcp.json to .gitignore if not already present
gitignore="$TARGET_DIR/.gitignore"
for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/"; do
  if [ ! -f "$gitignore" ] || ! grep -qF "$pattern" "$gitignore"; then
    echo "$pattern" >> "$gitignore"
  fi
done

# Copy VERSION file
if [ -f "$SCRIPT_DIR/VERSION" ] && ! [ "$SCRIPT_DIR/VERSION" -ef "$TARGET_DIR/VERSION" ]; then
  cp "$SCRIPT_DIR/VERSION" "$TARGET_DIR/VERSION"
  echo "  copy: VERSION"
fi

# --- Merge missing sections into CLAUDE.md on update ---
if [ "$MODE" = "update" ] && [ -f "$TARGET_DIR/CLAUDE.md" ] && [ -f "$TEMPLATE_DIR/CLAUDE.md" ]; then
  appended=0
  # Each entry: "heading to grep for" | "section content to append"
  # We check if the heading exists in the user's CLAUDE.md; if not, extract and append it
  sections=(
    "## Plan Protocol"
    "## Updating drom-flow"
  )
  # Also check that drom-plans is in File Organization
  if ! grep -q "drom-plans/" "$TARGET_DIR/CLAUDE.md" 2>/dev/null; then
    # Find the File Organization section and append the line
    if grep -q "## File Organization" "$TARGET_DIR/CLAUDE.md"; then
      sed -i '/## File Organization/,/^##/{/^- Use `config\//a\- Use `drom-plans/` for execution plans (chapter-based, with progress tracking)
}' "$TARGET_DIR/CLAUDE.md"
      echo "  merge:   CLAUDE.md — added drom-plans/ to File Organization"
      appended=$((appended + 1))
    fi
  fi

  for section_heading in "${sections[@]}"; do
    if ! grep -qF "$section_heading" "$TARGET_DIR/CLAUDE.md" 2>/dev/null; then
      # Extract section from template: from heading to next ## or EOF
      section_content=$(awk -v h="$section_heading" '
        $0 == h { found=1 }
        found && /^## / && $0 != h { exit }
        found { print }
      ' "$TEMPLATE_DIR/CLAUDE.md")
      if [ -n "$section_content" ]; then
        printf "\n%s\n" "$section_content" >> "$TARGET_DIR/CLAUDE.md"
        echo "  merge:   CLAUDE.md — added $section_heading"
        appended=$((appended + 1))
      fi
    fi
  done

  # Ensure the drom-flow branding is in the title line
  if ! head -1 "$TARGET_DIR/CLAUDE.md" | grep -q "drom-flow" 2>/dev/null; then
    sed -i '1s/^# .*/# drom-flow — Project Configuration/' "$TARGET_DIR/CLAUDE.md"
    # Add description line after title if not present
    if ! grep -q "drom-flow.*is active" "$TARGET_DIR/CLAUDE.md" 2>/dev/null; then
      sed -i '1a\\n> **drom-flow** is active in this project. It provides workflows, parallel agent orchestration, closed-loop pipelines, persistent memory, chapter-based execution plans, and lifecycle hooks.' "$TARGET_DIR/CLAUDE.md"
    fi
    echo "  merge:   CLAUDE.md — added drom-flow branding"
    appended=$((appended + 1))
  fi

  [ "$appended" -gt 0 ] && echo "  ($appended section(s) merged into CLAUDE.md)"
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
echo "  .claude/skills/        — 9 agent skills (/planner, /reviewer, /orchestrator, /add-javaducker, etc.)"
echo "  context/               — memory, decisions, conventions templates"
echo "  workflows/             — bug-fix, new-feature, refactor, code-review, closed-loop"
echo "  scripts/orchestrate.sh — template orchestration script for closed-loop pipelines"
echo "  drom-plans/            — chapter-based execution plans with progress tracking"
echo "  reports/               — iteration reports from orchestration runs"
