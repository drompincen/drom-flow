# drom-flow Script Generation Instructions

> This file contains the full source of all shell scripts used by drom-flow.
> GitHub ZIP downloads break when repos contain `.sh` files, so scripts are
> distributed as text and generated locally.
>
> **To generate all scripts, run:**
> ```
> claude "Read SCRIPTS.md and generate all scripts listed in it. Write each script to its specified path and make it executable with chmod +x."
> ```
>
> Or manually: copy each code block below into its target path, then `chmod +x` it.

---

## How it works

- Scripts under `.claude/hooks/` are the active hooks used by this project.
- Scripts under `template/.claude/hooks/` are **identical copies** installed into new projects by `init.sh`.
- `scripts/orchestrate.sh` and `template/scripts/orchestrate.sh` are also identical.
- `init.sh` lives at the repo root and bootstraps drom-flow into target projects.

When generating: create each file at its listed path, then copy the hooks into
`template/.claude/hooks/` and `scripts/orchestrate.sh` into `template/scripts/orchestrate.sh`.

---

## .claude/hooks/edit-log.sh

```bash
#!/bin/bash
# drom-flow edit logger — appends edit events to JSONL

DIR="${CLAUDE_PROJECT_DIR:-.}"
LOG="$DIR/.claude/edit-log.jsonl"

# Extract file_path from tool input (passed via stdin)
file_path="unknown"
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi

timestamp=$(date +%s)
echo "{\"type\":\"edit\",\"file\":\"$file_path\",\"timestamp\":$timestamp}" >> "$LOG"
```

---

## .claude/hooks/javaducker-check.sh

```bash
#!/bin/bash
# drom-flow — JavaDucker guard and lifecycle functions (sourced by other hooks)
# When .claude/.state/javaducker.conf does not exist, all functions return false.

JAVADUCKER_CONF="${CLAUDE_PROJECT_DIR:-.}/.claude/.state/javaducker.conf"
JAVADUCKER_SHARED=""

# Discover a shared JavaDucker instance from ancestor projects or running servers
javaducker_discover() {
  local dir
  dir="$(cd "${CLAUDE_PROJECT_DIR:-.}" && pwd)"

  # Phase 1: Walk up looking for an ancestor's javaducker.conf
  local parent
  parent="$(dirname "$dir")"
  while [ "$parent" != "/" ]; do
    if [ -f "$parent/.claude/.state/javaducker.conf" ]; then
      JAVADUCKER_CONF="$parent/.claude/.state/javaducker.conf"
      JAVADUCKER_SHARED="$parent"
      return 0
    fi
    parent="$(dirname "$parent")"
  done

  # Phase 2: Scan ports for a running JavaDucker (fast /dev/tcp pre-filter)
  # Use /api/info (returns app name) or /api/stats (returns artifact_count)
  # to positively identify JavaDucker and avoid false positives from other apps.
  local port resp
  for port in $(seq 8080 8180); do
    if (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
      resp=$(curl -sf "http://localhost:$port/api/info" 2>/dev/null)
      if echo "$resp" | grep -qi '"javaducker"'; then
        JAVADUCKER_HTTP_PORT="$port"
        JAVADUCKER_SHARED="localhost:$port"
        return 0
      fi
      # Fallback: /api/stats is JavaDucker-specific (has artifact_count)
      if curl -sf "http://localhost:$port/api/stats" 2>/dev/null | grep -q '"artifact_count"'; then
        JAVADUCKER_HTTP_PORT="$port"
        JAVADUCKER_SHARED="localhost:$port"
        return 0
      fi
    fi
  done

  return 1
}

# Check if using a shared (non-local) JavaDucker instance
javaducker_is_shared() {
  [ -n "$JAVADUCKER_SHARED" ]
}

javaducker_available() {
  # Check local config first
  if [ -f "$JAVADUCKER_CONF" ]; then
    . "$JAVADUCKER_CONF"
    [ -n "$JAVADUCKER_ROOT" ] && return 0
  fi
  # Try discovering a shared instance
  if javaducker_discover; then
    [ -f "$JAVADUCKER_CONF" ] && . "$JAVADUCKER_CONF"
    return 0
  fi
  return 1
}

javaducker_healthy() {
  javaducker_available || return 1
  curl -sf "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/health" >/dev/null 2>&1
}

# Find a free TCP port in the 8080-8180 range
javaducker_find_free_port() {
  for port in $(seq 8080 8180); do
    if ! (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
      echo "$port"
      return 0
    fi
  done
  echo "8080"
}

# Start the server with project-local data paths
javaducker_start() {
  javaducker_available || return 1
  javaducker_healthy && return 0

  # If using a shared instance, don't start — let the owning project handle it
  if javaducker_is_shared; then
    return 1
  fi

  local db="${JAVADUCKER_DB:-${CLAUDE_PROJECT_DIR:-.}/.claude/.javaducker/javaducker.duckdb}"
  local intake="${JAVADUCKER_INTAKE:-${CLAUDE_PROJECT_DIR:-.}/.claude/.javaducker/intake}"
  local port="${JAVADUCKER_HTTP_PORT:-8080}"

  mkdir -p "$(dirname "$db")" "$intake"

  # Check if the configured port is taken; if so, find a free one
  if (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
    # Port in use — check if it's our server
    if curl -sf "http://localhost:$port/api/health" >/dev/null 2>&1; then
      return 0  # Already running
    fi
    # Port taken by something else — find a free one
    port=$(javaducker_find_free_port)
    # Update config with new port
    sed -i "s/^JAVADUCKER_HTTP_PORT=.*/JAVADUCKER_HTTP_PORT=$port/" "$JAVADUCKER_CONF"
    export JAVADUCKER_HTTP_PORT="$port"
  fi

  DB="$db" HTTP_PORT="$port" INTAKE_DIR="$intake" \
    nohup bash "${JAVADUCKER_ROOT}/run-server.sh" >/dev/null 2>&1 &

  # Wait for startup
  for i in 1 2 3 4 5 6 7 8; do
    sleep 1
    if curl -sf "http://localhost:$port/api/health" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}
```

---

## .claude/hooks/javaducker-index.sh

```bash
#!/bin/bash
# drom-flow — index modified files in JavaDucker after edits
# Triggered by PostToolUse on Write|Edit|MultiEdit
# Fire-and-forget: does not block the edit. Silently no-ops if JavaDucker is not configured.

DIR="${CLAUDE_PROJECT_DIR:-.}"
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
javaducker_healthy || exit 0

# Extract file_path from tool input
file_path=""
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi
[ -z "$file_path" ] && exit 0
[ -f "$file_path" ] || exit 0

# Index via REST API (background, fire-and-forget)
abs_path=$(realpath "$file_path" 2>/dev/null || echo "$file_path")
curl -sf -X POST "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/upload-file" \
  -H "Content-Type: application/json" \
  -d "{\"file_path\":\"$abs_path\"}" \
  >/dev/null 2>&1 &
```

---

## .claude/hooks/memory-sync.sh

```bash
#!/bin/bash
# drom-flow memory sync — inject session memory and check for in-progress plans on start

DIR="${CLAUDE_PROJECT_DIR:-.}"
MEMORY="$DIR/context/MEMORY.md"
STATE_DIR="$DIR/.claude/.state"
PLANS_DIR="$DIR/drom-plans"

# Initialize session state
mkdir -p "$STATE_DIR"
date +%s > "$STATE_DIR/session-start"
echo "0" > "$STATE_DIR/agent-count"
echo "0" > "$STATE_DIR/edit-count"

# Load session memory
if [ -s "$MEMORY" ]; then
  echo "[Session Memory Loaded]"
  echo "---"
  cat "$MEMORY"
  echo "---"
else
  echo "[No session memory found. Create context/MEMORY.md to persist context across sessions.]"
fi

# Check for in-progress plans
if [ -d "$PLANS_DIR" ]; then
  in_progress=""
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null; then
      title=$(grep "^title:" "$plan" 2>/dev/null | sed 's/^title: *//')
      chapter=$(grep "^current_chapter:" "$plan" 2>/dev/null | sed 's/^current_chapter: *//')
      basename=$(basename "$plan")
      in_progress="${in_progress}\n  - ${basename} — \"${title}\" (Chapter ${chapter:-?})"
    fi
  done
  if [ -n "$in_progress" ]; then
    echo ""
    echo "[In-Progress Plans Found]"
    echo -e "The following plans were stopped midway and can be resumed:${in_progress}"
    echo "Read the plan file to review progress and resume from the current chapter."
  fi
fi

# --- JavaDucker: auto-start and health check ---
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available; then
  if javaducker_healthy; then
    if javaducker_is_shared; then
      echo "[JavaDucker: connected to shared instance (port ${JAVADUCKER_HTTP_PORT:-8080}, from ${JAVADUCKER_SHARED})]"
    else
      echo "[JavaDucker: connected (port ${JAVADUCKER_HTTP_PORT:-8080})]"
    fi
  else
    if javaducker_is_shared; then
      echo "[JavaDucker: shared instance not running (from ${JAVADUCKER_SHARED}) — start it from the owning project]"
    else
      echo "[JavaDucker: starting server...]"
      if javaducker_start; then
        echo "[JavaDucker: connected (port ${JAVADUCKER_HTTP_PORT:-8080})]"
      else
        echo "[JavaDucker: server starting in background — will be available shortly]"
      fi
    fi
  fi
fi
```

---

## .claude/hooks/session-end.sh

```bash
#!/bin/bash
# drom-flow session end — remind to persist progress and update plans

DIR="${CLAUDE_PROJECT_DIR:-.}"
PLANS_DIR="$DIR/drom-plans"

echo "[Session ending. Update context/MEMORY.md with progress, findings, and next steps.]"

# Remind about in-progress plans
if [ -d "$PLANS_DIR" ]; then
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null; then
      title=$(grep "^title:" "$plan" 2>/dev/null | sed 's/^title: *//')
      echo "[Plan in progress: \"${title}\" — update chapter status and step checkboxes before ending.]"
    fi
  done
fi

# JavaDucker session-end hygiene
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available && javaducker_healthy; then
  edits=0
  [ -f "$DIR/.claude/edit-log.jsonl" ] && edits=$(wc -l < "$DIR/.claude/edit-log.jsonl" | tr -d ' ')
  if [ "$edits" -gt 10 ]; then
    echo "[JavaDucker: $edits files edited — run javaducker_index_health to check freshness.]"
  fi
  # Check for un-enriched artifacts
  queue=$(curl -sf "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/enrich-queue?limit=1" 2>/dev/null)
  if [ -n "$queue" ] && echo "$queue" | grep -q '"artifact_id"'; then
    echo "[JavaDucker: un-enriched artifacts detected — run workflows/javaducker-hygiene.md Phase 2 to classify, tag, and extract points.]"
  fi
fi
```

---

## .claude/hooks/statusline.sh

```bash
#!/bin/bash
# drom-flow statusline — git-aware status for Claude Code

DIR="${CLAUDE_PROJECT_DIR:-.}"
STATE_DIR="$DIR/.claude/.state"

# --- Version ---
DROMFLOW_VERSION=""
for vfile in "$DIR/VERSION" "$(dirname "${BASH_SOURCE[0]}")/../../../VERSION"; do
  if [ -f "$vfile" ]; then
    DROMFLOW_VERSION=$(tr -d '[:space:]' < "$vfile")
    break
  fi
done
DROMFLOW_VERSION="${DROMFLOW_VERSION:-dev}"

# --- Project root (bright cyan to pop) ---
PROJECT_ROOT="\033[1;36m$(basename "$(cd "$DIR" && pwd)")\033[0m"

# --- Session elapsed time ---
elapsed=""
if [ -f "$STATE_DIR/session-start" ]; then
  start=$(cat "$STATE_DIR/session-start")
  now=$(date +%s)
  diff=$((now - start))
  mins=$((diff / 60))
  secs=$((diff % 60))
  if [ $mins -ge 60 ]; then
    hrs=$((mins / 60))
    mins=$((mins % 60))
    elapsed="${hrs}h${mins}m"
  else
    elapsed="${mins}m${secs}s"
  fi
fi

# --- Plan progress (computed early so both git and no-git paths can use it) ---
plan_info=""
PLANS_DIR="$DIR/drom-plans"
if [ -d "$PLANS_DIR" ]; then
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    # Match plans that are in-progress OR have any in-progress chapter (fallback for bad frontmatter)
    if grep -q "^status: in-progress" "$plan" 2>/dev/null || grep -q '^\*\*Status:\*\* in-progress' "$plan" 2>/dev/null; then
      cur=$(grep "^current_chapter:" "$plan" 2>/dev/null | sed 's/^current_chapter: *//')
      total=$(grep -c "^## Chapter " "$plan" 2>/dev/null)
done_count=$(grep -c '^\*\*Status:\*\* completed' "$plan" 2>/dev/null)
      plan_info="plan:ch${cur:-?}/${total:-?}(${done_count:-0}✓)"
      break
    fi
  done
fi

# --- Git info ---
branch=$(git branch --show-current 2>/dev/null || echo "no-git")
if [ "$branch" = "no-git" ]; then
  nogit_status="drom-flow v$DROMFLOW_VERSION • $PROJECT_ROOT • [no-git] • ${elapsed:-0m0s}"
  [ -n "$plan_info" ] && nogit_status="$nogit_status • $plan_info"
  echo -e "$nogit_status"
  exit 0
fi

staged=$(git diff --cached --numstat 2>/dev/null | wc -l | tr -d ' ')
unstaged=$(git diff --numstat 2>/dev/null | wc -l | tr -d ' ')
untracked=$(git ls-files --others --exclude-standard 2>/dev/null | wc -l | tr -d ' ')

ahead=0
behind=0
upstream=$(git rev-list --left-right --count HEAD...@{upstream} 2>/dev/null)
if [ $? -eq 0 ]; then
  ahead=$(echo "$upstream" | awk '{print $1}')
  behind=$(echo "$upstream" | awk '{print $2}')
fi

# Compact git: +staged/-unstaged/?untracked
git_info="$branch +${staged}/-${unstaged}/?${untracked}"
[ "$ahead" -gt 0 ] || [ "$behind" -gt 0 ] && git_info="$git_info ↑${ahead}↓${behind}"

# --- Edit count (from edit-log) ---
edits=0
[ -f "$DIR/.claude/edit-log.jsonl" ] && edits=$(wc -l < "$DIR/.claude/edit-log.jsonl" | tr -d ' ')

# --- Background agents (tracked via hook) ---
agents=0
[ -f "$STATE_DIR/agent-count" ] && agents=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')

# --- Memory status ---
mem="off"
[ -s "$DIR/context/MEMORY.md" ] && mem="on"

# --- JavaDucker status ---
jd_icon=""
. "$DIR/.claude/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available; then
  if javaducker_healthy; then
    javaducker_is_shared && jd_icon="JD(shared)" || jd_icon="JD"
  else
    jd_icon="JD(off)"
  fi
fi

status="drom-flow v$DROMFLOW_VERSION • $PROJECT_ROOT • $git_info • ${elapsed:-0m0s} • edits:$edits • agents:$agents • mem:$mem"
[ -n "$jd_icon" ] && status="$status • $jd_icon"
[ -n "$plan_info" ] && status="$status • $plan_info"
echo -e "$status"
```

---

## .claude/hooks/track-agents.sh

```bash
#!/bin/bash
# drom-flow — track background agent count

STATE_DIR="${CLAUDE_PROJECT_DIR:-.}/.claude/.state"
mkdir -p "$STATE_DIR"

count=0
[ -f "$STATE_DIR/agent-count" ] && count=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')
echo $((count + 1)) > "$STATE_DIR/agent-count"
```

---

## .claude/hooks/validate-plan.sh

```bash
#!/bin/bash
# drom-flow — validate plan files written to drom-plans/

DIR="${CLAUDE_PROJECT_DIR:-.}"
PLANS_DIR="$DIR/drom-plans"

# Extract file_path from tool input
file_path=""
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi

# Only validate files in drom-plans/
case "$file_path" in
  */drom-plans/*.md|drom-plans/*.md) ;;
  *) exit 0 ;;
esac

[ ! -f "$file_path" ] && exit 0

errors=""

# Check frontmatter exists
if ! head -1 "$file_path" | grep -q "^---"; then
  errors="${errors}\n  - Missing YAML frontmatter (must start with ---)"
fi

# Check required frontmatter fields
for field in title status created updated current_chapter; do
  if ! grep -q "^${field}:" "$file_path"; then
    errors="${errors}\n  - Missing frontmatter field: ${field}"
  fi
done

# Check status value
status=$(grep "^status:" "$file_path" | head -1 | sed 's/^status: *//')
case "$status" in
  in-progress|completed|pending|abandoned) ;;
  *) errors="${errors}\n  - Invalid status: '${status}' (must be: in-progress, completed, pending, or abandoned)" ;;
esac

# Check for at least one chapter
chapter_count=$(grep -c "^## Chapter " "$file_path" 2>/dev/null | tr -d '[:space:]')
chapter_count=${chapter_count:-0}
if [ "$chapter_count" -eq 0 ]; then
  errors="${errors}\n  - No chapters found (need at least one '## Chapter N: Title')"
fi

# Check chapters have Status lines
chapters_without_status=0
while IFS= read -r line; do
  chapter_num=$(echo "$line" | grep -o "Chapter [0-9]*" | grep -o "[0-9]*")
  if ! grep -A2 "^## Chapter ${chapter_num}:" "$file_path" | grep -q '^\*\*Status:\*\*'; then
    chapters_without_status=$((chapters_without_status + 1))
    errors="${errors}\n  - Chapter ${chapter_num} missing **Status:** line"
  fi
done < <(grep "^## Chapter " "$file_path")

# Check chapters have at least one step (checkbox)
while IFS= read -r line; do
  chapter_num=$(echo "$line" | grep -o "Chapter [0-9]*" | grep -o "[0-9]*")
  # Get content between this chapter and the next (or end of file)
  next_section=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^## /{print NR; exit}" "$file_path")
  if [ -n "$next_section" ]; then
    step_count=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^## /{exit} found && /^- \[/" "$file_path" | wc -l)
  else
    step_count=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^- \[/" "$file_path" | wc -l)
  fi
  if [ "$step_count" -eq 0 ]; then
    errors="${errors}\n  - Chapter ${chapter_num} has no steps (need at least one '- [ ] ...')"
  fi
done < <(grep "^## Chapter " "$file_path")

# Check current_chapter points to a valid chapter
current=$(grep "^current_chapter:" "$file_path" | head -1 | sed 's/^current_chapter: *//')
if [ -n "$current" ] && [ "$chapter_count" -gt 0 ]; then
  if ! grep -q "^## Chapter ${current}:" "$file_path"; then
    errors="${errors}\n  - current_chapter: ${current} does not match any chapter heading"
  fi
fi

if [ -n "$errors" ]; then
  echo "PLAN VALIDATION FAILED: $(basename "$file_path")"
  echo -e "Issues:${errors}"
  echo ""
  echo "Expected format: see /planner skill or drom-plans/ docs in CLAUDE.md"
  exit 1
fi
```

---

## init.sh

```bash
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
  ".claude/skills/ascii-architect"
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
  for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" "setup-backup/"; do
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
    for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" "setup-backup/"; do
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
backed_up=0

# Backup directory for files that will be overwritten
BACKUP_DIR="$TARGET_DIR/setup-backup/$(date +%Y%m%d-%H%M%S)"

backup_file() {
  local rel="$1"
  local src="$TARGET_DIR/$rel"
  [ -f "$src" ] || return 0
  local dest="$BACKUP_DIR/$rel"
  mkdir -p "$(dirname "$dest")"
  cp "$src" "$dest"
  backed_up=$((backed_up + 1))
}

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
        backup_file "$rel"
        cp "$file" "$target"
        echo "  update:  $rel (backed up)"
        updated=$((updated + 1))
      fi
    else
      # Fresh install — back up existing file before overwriting
      backup_file "$rel"
      cp "$file" "$target"
      echo "  replace: $rel (backed up)"
      copied=$((copied + 1))
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
for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" "setup-backup/"; do
  if [ ! -f "$gitignore" ] || ! grep -qF "$pattern" "$gitignore"; then
    echo "$pattern" >> "$gitignore"
  fi
done

# Copy VERSION file (back up existing first)
if [ -f "$SCRIPT_DIR/VERSION" ] && ! [ "$SCRIPT_DIR/VERSION" -ef "$TARGET_DIR/VERSION" ]; then
  backup_file "VERSION"
  cp "$SCRIPT_DIR/VERSION" "$TARGET_DIR/VERSION"
  echo "  copy: VERSION"
fi

# --- Merge missing sections into CLAUDE.md on update ---
# Back up CLAUDE.md before any modifications
if [ "$MODE" = "update" ] && [ -f "$TARGET_DIR/CLAUDE.md" ]; then
  backup_file "CLAUDE.md"
fi
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
if [ "$backed_up" -gt 0 ]; then
  echo "Backed up $backed_up file(s) to: $BACKUP_DIR"
fi
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
echo "  .claude/skills/        — 10 agent skills (/planner, /reviewer, /orchestrator, /ascii-architect, etc.)"
echo "  context/               — memory, decisions, conventions templates"
echo "  workflows/             — bug-fix, new-feature, refactor, code-review, closed-loop"
echo "  scripts/orchestrate.sh — template orchestration script for closed-loop pipelines"
echo "  drom-plans/            — chapter-based execution plans with progress tracking"
echo "  reports/               — iteration reports from orchestration runs"
```

---

## scripts/orchestrate.sh

```bash
#!/bin/bash
# drom-flow orchestration script template
# Copy and customize this for your project's pipeline.
#
# Usage:
#   ./scripts/orchestrate.sh [--iteration N] [--max N] [--check-only]
#
# Output:
#   Writes JSON report to ./reports/iteration-N.json
#   Exit 0 = all pass, Exit 1 = issues remain, Exit 2 = error

set -euo pipefail

# --- Configuration (customize these) ---
CHECK_CMD="echo 'Override CHECK_CMD with your test/check command'"
REPORT_DIR="./reports"
MAX_ITERATIONS=10
# ----------------------------------------

# Parse arguments
ITERATION=1
CHECK_ONLY=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --iteration) ITERATION="$2"; shift 2 ;;
    --max) MAX_ITERATIONS="$2"; shift 2 ;;
    --check-only) CHECK_ONLY=true; shift ;;
    *) echo "Unknown arg: $1"; exit 2 ;;
  esac
done

mkdir -p "$REPORT_DIR"

run_check() {
  local iter=$1
  local report="$REPORT_DIR/iteration-${iter}.json"
  local start_time=$(date +%s)

  echo "[orchestrate] Iteration $iter — running check..."

  # Run the check command, capture output
  local exit_code=0
  local output
  output=$(eval "$CHECK_CMD" 2>&1) || exit_code=$?

  local end_time=$(date +%s)
  local duration=$((end_time - start_time))

  # Write report
  cat > "$report" <<EOF
{
  "iteration": $iter,
  "timestamp": "$(date -Iseconds)",
  "durationSeconds": $duration,
  "exitCode": $exit_code,
  "output": $(echo "$output" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo "\"$output\"")
}
EOF

  echo "[orchestrate] Report written to $report (exit code: $exit_code, ${duration}s)"
  return $exit_code
}

compare_iterations() {
  local prev="$REPORT_DIR/iteration-$(($1 - 1)).json"
  local curr="$REPORT_DIR/iteration-$1.json"

  if [ ! -f "$prev" ]; then
    echo "[orchestrate] No previous iteration to compare"
    return 0
  fi

  local prev_exit=$(python3 -c "import json; print(json.load(open('$prev'))['exitCode'])" 2>/dev/null || echo "1")
  local curr_exit=$(python3 -c "import json; print(json.load(open('$curr'))['exitCode'])" 2>/dev/null || echo "1")

  echo "[orchestrate] Previous exit: $prev_exit → Current exit: $curr_exit"

  if [ "$curr_exit" -gt "$prev_exit" ]; then
    echo "[orchestrate] WARNING: Possible regression detected"
    return 1
  fi
  return 0
}

# --- Main ---

if [ "$CHECK_ONLY" = true ]; then
  run_check "$ITERATION"
  exit $?
fi

echo "[orchestrate] Starting closed loop: iteration $ITERATION, max $MAX_ITERATIONS"

while [ "$ITERATION" -le "$MAX_ITERATIONS" ]; do
  if run_check "$ITERATION"; then
    echo "[orchestrate] ALL CHECKS PASSED at iteration $ITERATION"
    exit 0
  fi

  if [ "$ITERATION" -gt 1 ]; then
    if ! compare_iterations "$ITERATION"; then
      echo "[orchestrate] Regression at iteration $ITERATION — stopping for review"
      exit 1
    fi
  fi

  echo "[orchestrate] Issues remain. Report: $REPORT_DIR/iteration-${ITERATION}.json"
  echo "[orchestrate] Waiting for fixes before next iteration..."
  # Script exits here — Claude reads the report, spawns fix agents,
  # then re-runs: ./scripts/orchestrate.sh --iteration $((ITERATION+1))
  exit 1

done

echo "[orchestrate] Max iterations ($MAX_ITERATIONS) reached"
exit 1
```

---

## Template copies

The following files are **identical** to their counterparts above. After generating the scripts above, copy them to these locations:

| Source | Copy to |
|---|---|
| `.claude/hooks/edit-log.sh` | `template/.claude/hooks/edit-log.sh` |
| `.claude/hooks/javaducker-check.sh` | `template/.claude/hooks/javaducker-check.sh` |
| `.claude/hooks/javaducker-index.sh` | `template/.claude/hooks/javaducker-index.sh` |
| `.claude/hooks/memory-sync.sh` | `template/.claude/hooks/memory-sync.sh` |
| `.claude/hooks/session-end.sh` | `template/.claude/hooks/session-end.sh` |
| `.claude/hooks/statusline.sh` | `template/.claude/hooks/statusline.sh` |
| `.claude/hooks/track-agents.sh` | `template/.claude/hooks/track-agents.sh` |
| `.claude/hooks/validate-plan.sh` | `template/.claude/hooks/validate-plan.sh` |
| `scripts/orchestrate.sh` | `template/scripts/orchestrate.sh` |
