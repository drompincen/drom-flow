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
- `scripts/grok-fleet.sh` and `scripts/grok-verify.sh` (grok sub-agent fan-out) are likewise
  mirrored into `template/scripts/`. See `docs/grok-fleet.md` for usage.
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
collect_managed_files() {
  local target="$1"
  managed=()
  while IFS= read -r -d '' file; do
    rel="${file#$TEMPLATE_DIR/}"
    if ! is_user_file "$rel" && [ -f "$target/$rel" ]; then
      managed+=("$rel")
    fi
  done < <(find "$TEMPLATE_DIR" -type f -print0)
  [ -f "$target/VERSION" ] && managed+=("VERSION")
  [ -d "$target/.claude/.state" ] && managed+=(".claude/.state/")
  [ -f "$target/.claude/edit-log.jsonl" ] && managed+=(".claude/edit-log.jsonl")
  [ -d "$target/.claude/.javaducker" ] && managed+=(".claude/.javaducker/")
  [ -d "$target/.claude/.grok-fleet" ] && managed+=(".claude/.grok-fleet/")
  true
}

MANAGED_DIRS=(
  "workflows"
  "reports"
  "drom-plans"
  ".claude/skills/accessibility/references"
  ".claude/skills/core-web-vitals/references"
  ".claude/skills/web-quality-audit/scripts"
  ".claude/skills/customer-journey-map/examples"
  ".claude/skills/discovery-process/examples"
  ".claude/skills/jobs-to-be-done/examples"
  ".claude/skills/prd-development/examples"
  ".claude/skills/problem-statement/examples"
  ".claude/skills/roadmap-planning/examples"
  ".claude/skills/user-story-mapping/examples"
  ".claude/skills/user-story-splitting/examples"
  ".claude/skills/user-story/examples"
  ".claude/skills/user-story/scripts"
  ".claude/skills/accessibility"
  ".claude/skills/api-expert"
  ".claude/skills/architect"
  ".claude/skills/ascii-architect"
  ".claude/skills/best-practices"
  ".claude/skills/core-web-vitals"
  ".claude/skills/customer-journey-map"
  ".claude/skills/debugger"
  ".claude/skills/discovery-process"
  ".claude/skills/epic-breakdown-advisor"
  ".claude/skills/implementer"
  ".claude/skills/jobs-to-be-done"
  ".claude/skills/orchestrator"
  ".claude/skills/performance"
  ".claude/skills/planner"
  ".claude/skills/prd-development"
  ".claude/skills/prioritization-advisor"
  ".claude/skills/problem-statement"
  ".claude/skills/refactorer"
  ".claude/skills/reviewer"
  ".claude/skills/roadmap-planning"
  ".claude/skills/seo"
  ".claude/skills/user-story"
  ".claude/skills/user-story-mapping"
  ".claude/skills/user-story-splitting"
  ".claude/skills/web-quality-audit"
  ".claude/skills/add-javaducker"
  ".claude/skills/remove-javaducker"
  ".claude/skills/grok-fleet"
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
  if [ -d "$TARGET_DIR/drom-plans" ]; then
    plan_count=$(find "$TARGET_DIR/drom-plans" -name '*.md' 2>/dev/null | wc -l | tr -d ' ')
    [ "$plan_count" -gt 0 ] && echo "  keep:   drom-plans/ ($plan_count plan file(s))"
  fi
  echo ""
  echo "Gitignore entries that would be cleaned:"
  for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" ".claude/.grok-fleet/" "setup-backup/"; do
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

  gitignore="$TARGET_DIR/.gitignore"
  if [ -f "$gitignore" ]; then
    cleaned=0
    for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" ".claude/.grok-fleet/" "setup-backup/"; do
      if grep -qF "$pattern" "$gitignore"; then
        sed -i "\|^${pattern}$|d" "$gitignore"
        cleaned=$((cleaned + 1))
      fi
    done
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

mkdir -p "$TARGET_DIR/.claude/.state"
echo "DROM_FLOW_HOME=$SCRIPT_DIR" > "$TARGET_DIR/.claude/.state/drom-flow.conf"
mkdir -p "$TARGET_DIR/drom-plans"

gitignore="$TARGET_DIR/.gitignore"
for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" ".claude/.grok-fleet/" "setup-backup/"; do
  if [ ! -f "$gitignore" ] || ! grep -qF "$pattern" "$gitignore"; then
    echo "$pattern" >> "$gitignore"
  fi
done

if [ -f "$SCRIPT_DIR/VERSION" ] && ! [ "$SCRIPT_DIR/VERSION" -ef "$TARGET_DIR/VERSION" ]; then
  backup_file "VERSION"
  cp "$SCRIPT_DIR/VERSION" "$TARGET_DIR/VERSION"
  echo "  copy: VERSION"
fi

if [ "$MODE" = "update" ] && [ -f "$TARGET_DIR/CLAUDE.md" ]; then
  backup_file "CLAUDE.md"
fi
if [ "$MODE" = "update" ] && [ -f "$TARGET_DIR/CLAUDE.md" ] && [ -f "$TEMPLATE_DIR/CLAUDE.md" ]; then
  appended=0
  sections=(
    "## Plan Protocol"
    "## Updating drom-flow"
  )
  if ! grep -q "drom-plans/" "$TARGET_DIR/CLAUDE.md" 2>/dev/null; then
    if grep -q "## File Organization" "$TARGET_DIR/CLAUDE.md"; then
      sed -i '/## File Organization/,/^##/{/^- Use `config\//a\- Use `drom-plans/` for execution plans (chapter-based, with progress tracking)
}' "$TARGET_DIR/CLAUDE.md"
      echo "  merge:   CLAUDE.md — added drom-plans/ to File Organization"
      appended=$((appended + 1))
    fi
  fi

  for section_heading in "${sections[@]}"; do
    if ! grep -qF "$section_heading" "$TARGET_DIR/CLAUDE.md" 2>/dev/null; then
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

  if ! head -1 "$TARGET_DIR/CLAUDE.md" | grep -q "drom-flow" 2>/dev/null; then
    sed -i '1s/^# .*/# drom-flow — Project Configuration/' "$TARGET_DIR/CLAUDE.md"
    if ! grep -q "drom-flow.*is active" "$TARGET_DIR/CLAUDE.md" 2>/dev/null; then
      sed -i '1a\\n> **drom-flow** is active in this project. It provides workflows, parallel agent orchestration, closed-loop pipelines, persistent memory, chapter-based execution plans, and lifecycle hooks.' "$TARGET_DIR/CLAUDE.md"
    fi
    echo "  merge:   CLAUDE.md — added drom-flow branding"
    appended=$((appended + 1))
  fi

  [ "$appended" -gt 0 ] && echo "  ($appended section(s) merged into CLAUDE.md)"
fi

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
echo "  .claude/skills/        — 27 agent skills: code (/planner, /reviewer, /orchestrator, /api-expert, /architect, etc.), web-QA (/accessibility, /seo, /performance, /core-web-vitals, /best-practices, /web-quality-audit), PM (/discovery-process, /problem-statement, /jobs-to-be-done, /customer-journey-map, /user-story-mapping, /epic-breakdown-advisor, /user-story, /user-story-splitting, /prd-development, /roadmap-planning, /prioritization-advisor)"
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

## template/.claude/skills/web-quality-audit/scripts/analyze.sh

> Generate this file **only** at `template/.claude/skills/web-quality-audit/scripts/analyze.sh` (no copy in `.claude/skills/`). It ships to target projects via `init.sh`.

```bash
#!/bin/bash
# Read-only HTML quality analyzer (v2). No filesystem mutations.
# stderr = human logs, stdout = structured JSON.
set -euo pipefail

MAX_FINDINGS=100
MAX_PER_CATEGORY_PER_FILE=20  # cap per high-volume check per file so one category can't fill MAX_FINDINGS

fail() {
  local type="$1" msg="$2" suggestion="$3"
  if command -v jq >/dev/null 2>&1; then
    jq -n \
      --arg type "$type" \
      --arg msg "$msg" \
      --arg suggestion "$suggestion" \
      '{success: false, error: {type: $type, message: $msg, retryable: false, suggestion: $suggestion}}'
  else
    printf '{"success":false,"error":{"type":"%s","message":"%s","suggestion":"%s","retryable":false}}\n' \
      "$type" "$msg" "$suggestion"
  fi
  exit 1
}

command -v jq >/dev/null 2>&1 || \
  fail "missing_dependency" "jq is required for safe JSON output" "Install: brew install jq"

[ $# -ge 1 ] || fail "invalid_input" "No target provided" "Usage: $0 <file_or_directory>"
TARGET="$1"
[ -e "$TARGET" ] || fail "invalid_input" "Target not found: $TARGET" "Pass an existing file or directory path"

ISSUES=()
WARNINGS=()

analyze_html() {
  local file="$1"
  echo "Analyzing: $file" >&2

  grep -qi "<!doctype html>"     "$file" || ISSUES+=("$file:0: Missing HTML5 doctype")
  grep -qi 'charset.*utf-8'      "$file" || WARNINGS+=("$file:0: Missing or non-UTF-8 charset")
  grep -qi 'name="viewport"'     "$file" || ISSUES+=("$file:0: Missing viewport meta tag")
  grep -qi '<html[^>]*lang='     "$file" || ISSUES+=("$file:0: Missing lang attribute on <html>")
  grep -qi '<title>'             "$file" || ISSUES+=("$file:0: Missing <title> tag")

  # <img> without alt — two-pass replaces broken PCRE lookahead
  local alt_count=0
  while IFS=: read -r ln tag; do
    if grep -qE 'alt=' <<<"$tag"; then continue; fi
    if [ "$alt_count" -ge "$MAX_PER_CATEGORY_PER_FILE" ]; then
      WARNINGS+=("$file:0: <img>-without-alt findings truncated (>${MAX_PER_CATEGORY_PER_FILE} in this file)")
      break
    fi
    WARNINGS+=("$file:$ln: <img> without alt attribute")
    alt_count=$((alt_count + 1))
  done < <(grep -noE '<img[^>]*>' "$file" || true)

  # Non-HTTPS URLs with line numbers
  local http_count=0
  while IFS=: read -r ln _; do
    if [ "$http_count" -ge "$MAX_PER_CATEGORY_PER_FILE" ]; then
      WARNINGS+=("$file:0: Non-HTTPS URL findings truncated (>${MAX_PER_CATEGORY_PER_FILE} in this file)")
      break
    fi
    WARNINGS+=("$file:$ln: Non-HTTPS URL")
    http_count=$((http_count + 1))
  done < <(grep -noE 'http://[^"'\''[:space:]>]*' "$file" || true)
}

# Process substitution keeps arrays in main shell (fixes v1 subshell bug)
if [ -d "$TARGET" ]; then
  while IFS= read -r -d '' file; do
    analyze_html "$file"
  done < <(find "$TARGET" \( -name "*.html" -o -name "*.htm" \) -print0)
elif [ -f "$TARGET" ]; then
  analyze_html "$TARGET"
else
  fail "invalid_input" "Target is not a regular file or directory: $TARGET" "Pass a path to an .html/.htm file or a directory"
fi

issue_total=${#ISSUES[@]}
warning_total=${#WARNINGS[@]}

to_json_array() {
  printf '%s\n' "$@" | jq -Rs 'split("\n") | map(select(length > 0))'
}

if [ "$issue_total" -gt 0 ]; then
  issues_json=$(to_json_array "${ISSUES[@]:0:$MAX_FINDINGS}")
else
  issues_json='[]'
fi

if [ "$warning_total" -gt 0 ]; then
  warnings_json=$(to_json_array "${WARNINGS[@]:0:$MAX_FINDINGS}")
else
  warnings_json='[]'
fi

echo "Scanned. $issue_total issues, $warning_total warnings." >&2

jq -n \
  --argjson issues "$issues_json" \
  --argjson warnings "$warnings_json" \
  --argjson issue_total "$issue_total" \
  --argjson warning_total "$warning_total" \
  --argjson max "$MAX_FINDINGS" \
  '{
    success: true,
    issues: $issues,
    warnings: $warnings,
    issueCount: $issue_total,
    warningCount: $warning_total,
    truncated: (($issue_total > $max) or ($warning_total > $max))
  }'
```

After generating, make it executable:
```
chmod +x template/.claude/skills/web-quality-audit/scripts/analyze.sh
```

---

## scripts/grok-fleet.sh

```bash
#!/bin/bash
# drom-flow — grok sub-agent fleet: filesystem-controlled fan-out from WSL to grok CLI (Windows).
#
# Subcommands: doctor | spawn | status | stop | collect | verify | clean
# Exit: 0 = ok, 1 = gate/agent failure, 2 = usage/env error
#
# Control plane lives on the Windows-visible disk because grok.exe is a Windows
# process and cannot see WSL-native paths (/tmp is invisible to it).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLEET_ROOT="${GROK_FLEET_ROOT:-$REPO_ROOT/.claude/.grok-fleet}"
REPORT_DIR="$REPO_ROOT/reports"
# NB: `grok models` lists the *selectable* ids. The name that appears in the `end`
# event's modelUsage (e.g. grok-4.5-build) is an internal resolved name and is NOT
# a valid -m value. Verify with `grok models` before changing this.
GROK_MODEL="${GROK_MODEL:-grok-4.5}"
GROK_MAX_PARALLEL="${GROK_MAX_PARALLEL:-4}"
GROK_BUDGET_USD="${GROK_BUDGET_USD:-5}"
STALL_SECS="${GROK_STALL_SECS:-180}"
AGENT_TIMEOUT="${GROK_AGENT_TIMEOUT:-600}"
GRACE_SECS="${GROK_GRACE_SECS:-10}"

log() { echo "[grok-fleet] $*" >&2; }
die() { echo "[grok-fleet] ERROR: $*" >&2; exit 2; }

# --- binary resolution -------------------------------------------------------
resolve_grok() {
  if [[ -n "${GROK_BIN:-}" && -x "$GROK_BIN" ]]; then echo "$GROK_BIN"; return 0; fi
  local c; c="$(command -v grok 2>/dev/null)"; [[ -n "$c" ]] && { echo "$c"; return 0; }
  local p="/mnt/c/Users/$USER/.grok/bin/grok.exe"; [[ -x "$p" ]] && { echo "$p"; return 0; }
  local up; up="$(cmd.exe /c echo %USERPROFILE% 2>/dev/null | tr -d '\r')"
  if [[ -n "$up" ]]; then
    p="$(wslpath -u "$up" 2>/dev/null)/.grok/bin/grok.exe"
    [[ -x "$p" ]] && { echo "$p"; return 0; }
  fi
  return 1
}

winpath() { wslpath -w "$1" 2>/dev/null; }

json_escape() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

# Guard: everything grok touches must live on the Windows-visible mount.
assert_win_visible() {
  case "$1" in /mnt/[a-z]/*) return 0 ;; *) return 1 ;; esac
}

# --- doctor ------------------------------------------------------------------
cmd_doctor() {
  local live=false; [[ "${1:-}" == "--live" ]] && live=true
  mkdir -p "$REPORT_DIR"
  local checks=() ok=true bin="" ver=""

  add() { checks+=("{\"name\":\"$1\",\"ok\":$2,\"detail\":$(json_escape "$3")}"); [[ "$2" == "true" ]] || ok=false; }

  if bin="$(resolve_grok)"; then add binary true "$bin"; else add binary false "grok.exe not found (set GROK_BIN)"; fi

  if [[ -n "$bin" ]]; then
    ver="$(timeout 60 "$bin" --version 2>/dev/null | head -1)"
    [[ -n "$ver" ]] && add version true "$ver" || add version false "--version produced no output"
  else add version false "skipped, no binary"; fi

  local auth="/mnt/c/Users/$USER/.grok/auth.json"
  [[ -f "$auth" ]] && add auth true "auth.json present" || add auth false "not authenticated: run 'grok login' on Windows"

  if timeout 30 tasklist.exe /NH >/dev/null 2>&1; then add interop true "tasklist.exe responds"
  else add interop false "WSL->Windows interop unavailable"; fi

  local w; w="$(winpath "$REPO_ROOT")"
  [[ -n "$w" ]] && add wslpath true "$REPO_ROOT -> $w" || add wslpath false "wslpath -w failed"

  if assert_win_visible "$REPO_ROOT"; then add win_visible true "repo on Windows-visible mount"
  else add win_visible false "repo at $REPO_ROOT is WSL-native; grok.exe cannot see it. Move the repo under /mnt/c."; fi

  local live_json='{"ran":false,"ok":false,"latency_ms":0}'
  if $live && [[ -n "$bin" ]] && $ok; then
    local t0 t1 out rc
    t0=$(date +%s%3N)
    out="$(timeout 60 "$bin" --cwd "$(winpath "$REPO_ROOT")" --permission-mode dontAsk --max-turns 2 \
           -p 'Reply with exactly the single word: PONG' 2>/dev/null)"; rc=$?
    t1=$(date +%s%3N)
    if [[ $rc -eq 0 && -n "${out// /}" ]]; then live_json="{\"ran\":true,\"ok\":true,\"latency_ms\":$((t1-t0))}"
    else live_json="{\"ran\":true,\"ok\":false,\"latency_ms\":$((t1-t0))}"; ok=false; fi
  fi

  local IFS=,
  cat > "$REPORT_DIR/grok-doctor.json" <<EOF
{"ok":$ok,"binary":$(json_escape "$bin"),"version":$(json_escape "$ver"),
 "fleet_root":$(json_escape "$FLEET_ROOT"),"model":$(json_escape "$GROK_MODEL"),
 "checks":[${checks[*]}],"live_test":$live_json}
EOF
  python3 -m json.tool "$REPORT_DIR/grok-doctor.json" >/dev/null 2>&1 || log "warn: doctor json malformed"
  $ok && { log "doctor: OK"; return 0; } || { log "doctor: FAILED (see $REPORT_DIR/grok-doctor.json)"; return 1; }
}

# --- agent helpers -----------------------------------------------------------
agent_dir()  { echo "$FLEET_ROOT/$1/agents/$2"; }
set_status() { # dir state [extra-json]
  local d="$1" s="$2" extra="${3:-}"
  printf '{"state":"%s","ts":"%s"%s}\n' "$s" "$(date -Iseconds)" "${extra:+,$extra}" > "$d/status.json"
}
get_state() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['state'])" "$1/status.json" 2>/dev/null || echo UNKNOWN; }

FLEET_PREAMBLE='
--- FLEET PROTOCOL (mandatory) ---
1. You are a fleet sub-agent. Your working directory is yours alone.
2. After each meaningful step, APPEND one line to PROGRESS.md in the PARENT of your
   working directory (../PROGRESS.md), formatted: [HH:MM:SS] <what you just did>.
   Write at least two such checkpoints before finishing.
3. Write all work product INTO your current working directory.
4. NEVER write outside your working directory or its parent PROGRESS.md.
5. Finish with a one-line summary starting with RESULT:
--- END FLEET PROTOCOL ---
'

# spawn one agent (blocking wrapper, meant to be backgrounded)
run_agent() {
  local d="$1" bin="$2" schema="${3:-}"
  local rc; local -a args
  args=( --cwd "$(winpath "$d/output")" -m "$GROK_MODEL"
         --prompt-file "$(winpath "$d/task.md")"
         --output-format streaming-json --permission-mode bypassPermissions
         --no-memory --max-turns 30 )
  [[ -n "$schema" ]] && args+=( --json-schema "$(cat "$schema")" )
  printf '%q ' "$bin" "${args[@]}" > "$d/cmd.txt"

  timeout "$AGENT_TIMEOUT" "$bin" "${args[@]}" > "$d/stream.jsonl" 2> "$d/stream.err" &
  local pid=$!
  printf '{"wsl_pid":%d}\n' "$pid" > "$d/pid"
  set_status "$d" RUNNING "\"wsl_pid\":$pid"
  wait $pid; rc=$?

  # distil the terminal `end` event
  python3 - "$d" <<'PY' 2>/dev/null
import json,sys,os
d=sys.argv[1]; end=None
try:
    for line in open(os.path.join(d,'stream.jsonl'),errors='replace'):
        line=line.strip()
        if not line: continue
        try: o=json.loads(line)
        except Exception: continue
        if o.get('type')=='end': end=o
except FileNotFoundError: pass
json.dump(end or {}, open(os.path.join(d,'result.json'),'w'), indent=2)
PY

  local state
  case $rc in
    0)   state=DONE ;;
    124) state=TIMEOUT ;;
    143|137) state=STOPPED ;;
    *)   state=FAILED ;;
  esac
  # An agent that produced nothing did not do the work, whatever the exit code says.
  if [[ "$state" == DONE ]] && [[ -z "$(ls -A "$d/output" 2>/dev/null)" ]]; then state=FAILED; fi
  local cost; cost="$(python3 -c "import json;print(json.load(open('$d/result.json')).get('total_cost_usd',0))" 2>/dev/null || echo 0)"
  set_status "$d" "$state" "\"exit\":$rc,\"cost_usd\":$cost"
}

run_total_cost() { # run_id -> total USD across all agents
  python3 - "$FLEET_ROOT/$1/agents" <<'PY'
import json,os,sys
b=sys.argv[1]; t=0.0
if os.path.isdir(b):
    for a in os.listdir(b):
        try: t+=float(json.load(open(os.path.join(b,a,'status.json'))).get('cost_usd') or 0)
        except Exception: pass
print(round(t,6))
PY
}

over_budget() { python3 -c "import sys;sys.exit(0 if float('$1')>float('$2') else 1)"; }

# Polls spend while a fan-out runs and halts the whole run if it breaches the cap.
budget_watchdog() {
  local run="$1" cap="$2" t
  while [[ ! -f "$FLEET_ROOT/$run/DONE" ]]; do
    sleep 8
    t="$(run_total_cost "$run")"
    if over_budget "$t" "$cap"; then
      log "BUDGET EXCEEDED: \$$t > \$$cap — halting run $run"
      echo "{\"state\":\"BUDGET_EXCEEDED\",\"spent\":$t,\"cap\":$cap}" > "$FLEET_ROOT/$run/HALT"
      cmd_stop --run-id "$run" >/dev/null 2>&1
      return 0
    fi
  done
}

# spawn --manifest: fan out N agents with a concurrency gate + budget guard
cmd_spawn_manifest() {
  local mf="$1"
  [[ -f "$mf" ]] || die "manifest not found: $mf"
  local run_id cap par
  run_id="$(python3 -c "import json;print(json.load(open('$mf'))['run_id'])")"
  cap="$(python3 -c "import json;print(json.load(open('$mf')).get('budget_usd',$GROK_BUDGET_USD))")"
  par="$(python3 -c "import json;print(json.load(open('$mf')).get('max_parallel',$GROK_MAX_PARALLEL))")"
  mkdir -p "$FLEET_ROOT/$run_id"; rm -f "$FLEET_ROOT/$run_id/HALT" "$FLEET_ROOT/$run_id/DONE"
  log "manifest run=$run_id parallel=$par budget=\$$cap"

  budget_watchdog "$run_id" "$cap" &
  local wd=$!

  local -a pids=()
  # Count only agent jobs — `jobs -rp` would also count the budget watchdog and
  # silently shrink the gate by one.
  alive_agents() { local n=0 p; for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && ((n++)); done; echo $n; }

  while IFS=$'\t' read -r aid tf schema; do
    [[ -z "$aid" ]] && continue
    [[ -f "$FLEET_ROOT/$run_id/HALT" ]] && { log "halted, not launching $aid"; break; }
    if [[ ! -f "$tf" ]]; then
      log "manifest: agent '$aid' task_file missing: $tf — skipping"
      mkdir -p "$FLEET_ROOT/$run_id/agents/$aid"
      set_status "$FLEET_ROOT/$run_id/agents/$aid" FAILED "\"reason\":\"task_file missing\""
      continue
    fi
    while (( $(alive_agents) >= par )); do sleep 1; done
    # Synchronous budget check at the launch point — the watchdog alone races with
    # the gate and can let another agent start after the cap is already breached.
    local spent; spent="$(run_total_cost "$run_id")"
    if over_budget "$spent" "$cap"; then
      log "BUDGET EXCEEDED: \$$spent > \$$cap — not launching $aid or any remaining agent"
      echo "{\"state\":\"BUDGET_EXCEEDED\",\"spent\":$spent,\"cap\":$cap}" > "$FLEET_ROOT/$run_id/HALT"
      break
    fi
    ( cmd_spawn --run-id "$run_id" --agent-id "$aid" --task-file "$tf" ${schema:+--schema "$schema"} >/dev/null 2>&1 ) &
    pids+=($!)
  done < <(python3 -c "
import json
for a in json.load(open('$mf'))['agents']:
    print('\t'.join([a['id'],a['task_file'],a.get('schema','')]))")

  (( ${#pids[@]} > 0 )) && wait "${pids[@]}" 2>/dev/null
  touch "$FLEET_ROOT/$run_id/DONE"; kill $wd 2>/dev/null; wait $wd 2>/dev/null

  local total; total="$(run_total_cost "$run_id")"
  if [[ -f "$FLEET_ROOT/$run_id/HALT" ]]; then
    log "run $run_id BUDGET_EXCEEDED (\$$total > \$$cap)"; return 1
  fi
  log "run $run_id complete, \$$total"
  cmd_status --run-id "$run_id" >/dev/null 2>&1
  return 0
}

cmd_spawn() {
  local run_id="" agent_id="" task_file="" schema="" bin
  [[ "${1:-}" == "--manifest" ]] && { cmd_spawn_manifest "$2"; return $?; }
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --agent-id) agent_id="$2"; shift 2 ;;
    --task-file) task_file="$2"; shift 2 ;;
    --schema) schema="$2"; shift 2 ;;
    --wait) shift ;;
    *) die "spawn: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" && -n "$agent_id" && -n "$task_file" ]] || die "spawn needs --run-id --agent-id --task-file"
  [[ -f "$task_file" ]] || die "task file not found: $task_file"
  bin="$(resolve_grok)" || die "grok binary not found"
  assert_win_visible "$FLEET_ROOT" || die "fleet root $FLEET_ROOT is not Windows-visible"

  local d; d="$(agent_dir "$run_id" "$agent_id")"
  # Idempotent resume: an agent that already finished is never re-run (and never re-billed).
  if [[ -f "$d/status.json" && "$(get_state "$d")" == DONE ]]; then
    log "spawn: $agent_id already DONE, skipping"; return 0
  fi
  mkdir -p "$d/output"
  { cat "$task_file"; printf '%s' "$FLEET_PREAMBLE"; } > "$d/task.md"
  : > "$d/PROGRESS.md"
  set_status "$d" QUEUED
  run_agent "$d" "$bin" "$schema"
  [[ "$(get_state "$d")" == DONE ]]
}

# --- status ------------------------------------------------------------------
cmd_status() {
  local run_id="" as_json=false
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;; --json) as_json=true; shift ;; *) die "status: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" ]] || die "status needs --run-id"
  local base="$FLEET_ROOT/$run_id/agents"
  [[ -d "$base" ]] || die "no such run: $run_id"
  mkdir -p "$REPORT_DIR"

  python3 - "$base" "$STALL_SECS" "$REPORT_DIR/grok-fleet-$run_id.json" "$as_json" <<'PY'
import json,os,sys,time
base,stall,out,as_json=sys.argv[1],int(sys.argv[2]),sys.argv[3],sys.argv[4]=='true'
agents=[];total=0.0
for a in sorted(os.listdir(base)):
    d=os.path.join(base,a)
    if not os.path.isdir(d): continue
    st={}
    try: st=json.load(open(os.path.join(d,'status.json')))
    except Exception: pass
    state=st.get('state','UNKNOWN'); cost=float(st.get('cost_usd') or 0); total+=cost
    sp=os.path.join(d,'stream.jsonl')
    age=int(time.time()-os.path.getmtime(sp)) if os.path.exists(sp) else -1
    if state=='RUNNING' and age>stall: state='STALLED'
    prog=[l.strip() for l in open(os.path.join(d,'PROGRESS.md'),errors='replace')] if os.path.exists(os.path.join(d,'PROGRESS.md')) else []
    prog=[p for p in prog if p]
    agents.append({'agent':a,'state':state,'cost_usd':cost,'stream_age_s':age,
                   'checkpoints':len(prog),'last_progress':prog[-1] if prog else ''})
roll={'running':sum(1 for x in agents if x['state']in('RUNNING','STALLED')),
      'done':sum(1 for x in agents if x['state']=='DONE'),
      'failed':sum(1 for x in agents if x['state']in('FAILED','TIMEOUT')),
      'stopped':sum(1 for x in agents if x['state']=='STOPPED'),
      'total_cost_usd':round(total,4)}
json.dump({'agents':agents,'rollup':roll},open(out,'w'),indent=2)
if as_json: print(json.dumps({'agents':agents,'rollup':roll}))
else:
    print(f"{'AGENT':<22}{'STATE':<10}{'CKPT':>5}{'AGE':>6}{'COST':>9}  LAST")
    for x in agents:
        print(f"{x['agent']:<22}{x['state']:<10}{x['checkpoints']:>5}{x['stream_age_s']:>6}{x['cost_usd']:>9.4f}  {x['last_progress'][:48]}")
    print(f"-- {roll['done']} done / {roll['running']} running / {roll['failed']} failed / {roll['stopped']} stopped, ${roll['total_cost_usd']}")
PY
}

# --- stop --------------------------------------------------------------------
kill_agent() {
  local d="$1"
  local pid; pid="$(python3 -c "import json;print(json.load(open('$d/pid'))['wsl_pid'])" 2>/dev/null)" || return 0
  [[ -z "$pid" ]] && return 0
  touch "$d/STOP"
  local waited=0
  while kill -0 "$pid" 2>/dev/null && (( waited < GRACE_SECS )); do sleep 1; ((waited++)); done
  kill -0 "$pid" 2>/dev/null && { kill "$pid" 2>/dev/null; sleep 2; }
  kill -0 "$pid" 2>/dev/null && { kill -9 "$pid" 2>/dev/null; sleep 1; }
  # Preserve spend already incurred — a stopped agent still cost money, and dropping
  # it here would under-report the run total that the budget guard depends on.
  local prior; prior="$(python3 -c "
import json
try: print(json.load(open('$d/result.json')).get('total_cost_usd') or json.load(open('$d/status.json')).get('cost_usd') or 0)
except Exception: print(0)" 2>/dev/null || echo 0)"
  set_status "$d" STOPPED "\"stopped_by\":\"fleet\",\"cost_usd\":${prior:-0}"
}

cmd_stop() {
  local run_id="" agent_id="" all=false
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;; --agent-id) agent_id="$2"; shift 2 ;;
    --all) all=true; shift ;; *) die "stop: unknown arg $1" ;;
  esac; done

  if $all; then
    for d in "$FLEET_ROOT"/*/agents/*; do [[ -d "$d" ]] || continue
      [[ "$(get_state "$d")" == RUNNING ]] && kill_agent "$d"; done
    # NB: never `pkill -f grok.exe` — it matches any process whose command line merely
    # mentions the string (including the caller's own shell) and kills bystanders.
    # Tracked PIDs above, then the Windows side for orphans, is sufficient.
    sleep 2
    taskkill.exe /IM grok.exe /F >/dev/null 2>&1
    log "stop --all complete"; return 0
  fi
  [[ -n "$run_id" ]] || die "stop needs --run-id or --all"
  if [[ -n "$agent_id" ]]; then kill_agent "$(agent_dir "$run_id" "$agent_id")"
  else for d in "$FLEET_ROOT/$run_id/agents"/*; do [[ -d "$d" ]] && kill_agent "$d"; done; fi
  log "stop complete"
}

# --- collect -----------------------------------------------------------------
cmd_collect() {
  local run_id=""
  while [[ $# -gt 0 ]]; do case $1 in --run-id) run_id="$2"; shift 2 ;; *) die "collect: unknown arg $1" ;; esac; done
  [[ -n "$run_id" ]] || die "collect needs --run-id"
  local base="$FLEET_ROOT/$run_id/agents" out="$REPORT_DIR/grok-fleet-$run_id.md" failed=0
  mkdir -p "$REPORT_DIR"
  { echo "# Grok fleet run: $run_id"; echo; echo "_$(date -Iseconds)_"; echo; } > "$out"
  local total=0
  for d in "$base"/*; do [[ -d "$d" ]] || continue
    local a s c; a="$(basename "$d")"; s="$(get_state "$d")"
    c="$(python3 -c "import json;print(json.load(open('$d/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)"
    total="$(python3 -c "print(round($total+${c:-0},4))")"
    [[ "$s" == DONE ]] || failed=1
    { echo "## $a — **$s** (\$$c)"; echo '```'; head -c 1200 "$d/output"/* 2>/dev/null || echo '(no output)'; echo '```'; echo; } >> "$out"
  done
  echo "**Total cost: \$$total**" >> "$out"
  log "collect -> $out (total \$$total)"
  return $failed
}

cmd_clean() { rm -rf "${FLEET_ROOT:?}"/*; log "fleet root cleared"; }

[[ $# -eq 0 ]] && die "usage: grok-fleet.sh {doctor|spawn|status|stop|collect|verify|clean}"
SUB="$1"; shift
case "$SUB" in
  doctor)  cmd_doctor "$@" ;;
  spawn)   cmd_spawn "$@" ;;
  status)  cmd_status "$@" ;;
  stop)    cmd_stop "$@" ;;
  collect) cmd_collect "$@" ;;
  clean)   cmd_clean "$@" ;;
  verify)  source "$REPO_ROOT/scripts/grok-verify.sh"; cmd_verify "$@" ;;
  *) die "unknown subcommand: $SUB" ;;
esac
```

---

## scripts/grok-verify.sh

```bash
#!/bin/bash
# drom-flow — closed-loop verifier for the grok sub-agent fleet.
# Sourced by grok-fleet.sh; implements the six exit-criteria gates.
# Writes reports/grok-verify.json. Exit 0 only when every gate PASSes.

GATES_JSON=()
GATE_FAIL=0

gate() { # id status detail evidence
  local id="$1" st="$2" detail="$3" ev="${4:-}"
  GATES_JSON+=("{\"id\":\"$id\",\"status\":\"$st\",\"detail\":$(json_escape "$detail"),\"evidence\":$(json_escape "$ev")}")
  [[ "$st" == PASS ]] || GATE_FAIL=1
  log "gate $id: $st — $detail"
}

mk_task() { mkdir -p "$(dirname "$1")"; cat > "$1"; }

cmd_verify() {
  local as_json=false iteration="${GROK_ITERATION:-0}"
  while [[ $# -gt 0 ]]; do case $1 in
    --json) as_json=true; shift ;;
    --iteration) iteration="$2"; shift 2 ;;
    *) shift ;;
  esac; done

  mkdir -p "$REPORT_DIR" "$FLEET_ROOT"
  local t_start; t_start=$(date +%s)
  local RUN="verify-$(date +%H%M%S)"
  local TASKS="$FLEET_ROOT/_tasks"; mkdir -p "$TASKS"

  # ---------- Gate 1: feasibility ----------
  if cmd_doctor --live >/dev/null 2>&1; then
    gate feasibility PASS "doctor --live ok" "$REPORT_DIR/grok-doctor.json"
  else
    gate feasibility FAIL "doctor --live failed" "$REPORT_DIR/grok-doctor.json"
    finish_verify "$RUN" "$t_start" "$iteration" "$as_json"; return $?
  fi

  # ---------- Gates 2+3: work_done + monitor (one fan-out) ----------
  local -a AGENTS=(alpha bravo charlie)
  local i=1
  for a in "${AGENTS[@]}"; do
    mk_task "$TASKS/$a.md" <<EOF
You are fleet agent "$a" (unit $i of 3).

TASK: Write a file named findings.md in your working directory. It must contain:
  - line 1 exactly: MARKER_${a^^}
  - then 3 bullet points describing what a "closed-loop QA pipeline" is.

Do the work in at least two steps, appending a PROGRESS.md checkpoint after each.
EOF
    ((i++))
  done

  local -a pids=()
  for a in "${AGENTS[@]}"; do
    ( cmd_spawn --run-id "$RUN" --agent-id "$a" --task-file "$TASKS/$a.md" >/dev/null 2>&1 ) &
    pids+=($!)
  done

  # monitor while they run: sample progress checkpoints
  local max_ckpt=0 samples=0 saw_running=0
  while :; do
    local alive=0
    for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && alive=1; done
    local s; s="$(cmd_status --run-id "$RUN" --json 2>/dev/null | tail -1)"
    if [[ -n "$s" ]]; then
      local c r
      c="$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(max([a['checkpoints'] for a in d['agents']]+[0]))" "$s" 2>/dev/null || echo 0)"
      r="$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(d['rollup']['running'])" "$s" 2>/dev/null || echo 0)"
      (( c > max_ckpt )) && max_ckpt=$c
      (( r > 0 )) && saw_running=1
      ((samples++))
    fi
    [[ $alive -eq 0 ]] && break
    sleep 3
  done
  wait "${pids[@]}" 2>/dev/null

  # work_done: every agent DONE and output carries its marker
  local wd_ok=true wd_detail=""
  for a in "${AGENTS[@]}"; do
    local d; d="$(agent_dir "$RUN" "$a")"
    local st; st="$(get_state "$d")"
    local marker="MARKER_${a^^}"
    if [[ "$st" != DONE ]]; then wd_ok=false; wd_detail+="$a=$st "; continue; fi
    if ! grep -rqs "$marker" "$d/output" 2>/dev/null; then wd_ok=false; wd_detail+="$a=no-marker "; fi
  done
  if $wd_ok; then gate work_done PASS "3/3 agents DONE with correct markers" "$FLEET_ROOT/$RUN"
  else gate work_done FAIL "agent problems: $wd_detail" "$FLEET_ROOT/$RUN"; fi

  # monitor: live status sampled + >=2 checkpoints seen on some agent
  if (( samples > 0 )) && (( max_ckpt >= 2 )) && (( saw_running == 1 )); then
    gate monitor PASS "sampled $samples times, max $max_ckpt checkpoints, live RUNNING observed" "$REPORT_DIR/grok-fleet-$RUN.json"
  else
    gate monitor FAIL "samples=$samples max_checkpoints=$max_ckpt saw_running=$saw_running" "$REPORT_DIR/grok-fleet-$RUN.json"
  fi

  # ---------- Gate 4: stop ----------
  mk_task "$TASKS/longrun.md" <<'EOF'
Count from 1 to 400. For EVERY number write a full sentence reflecting on it into
notes.md in your working directory, appending as you go. Work slowly and thoroughly.
Append a PROGRESS.md checkpoint every 10 numbers.
EOF
  ( cmd_spawn --run-id "$RUN" --agent-id longrun --task-file "$TASKS/longrun.md" >/dev/null 2>&1 ) &
  local lp=$!
  local ld; ld="$(agent_dir "$RUN" longrun)"
  local waited=0
  while (( waited < 60 )); do
    [[ -s "$ld/stream.jsonl" ]] && break
    sleep 2; ((waited+=2))
  done

  local stop_ok=false stop_detail="agent never started streaming"
  if [[ -s "$ld/stream.jsonl" ]]; then
    local before after tl
    before=$(stat -c%s "$ld/stream.jsonl")
    cmd_stop --run-id "$RUN" --agent-id longrun >/dev/null 2>&1
    sleep 5
    after=$(stat -c%s "$ld/stream.jsonl"); sleep 4
    local after2; after2=$(stat -c%s "$ld/stream.jsonl")
    tl="$(tasklist.exe /FI "IMAGENAME eq grok.exe" 2>/dev/null | grep -c grok.exe)"
    local st; st="$(get_state "$ld")"
    if [[ "$st" == STOPPED ]] && (( after == after2 )); then
      stop_ok=true; stop_detail="state=STOPPED, stream frozen at $after2 bytes (was $before growing), grok.exe procs=$tl"
    else
      stop_detail="state=$st stream $after->$after2 procs=$tl"
    fi
  fi
  kill $lp 2>/dev/null; wait $lp 2>/dev/null
  $stop_ok && gate stop PASS "$stop_detail" "$ld" || gate stop FAIL "$stop_detail" "$ld"

  # ---------- Gate 5: combined claude + grok ----------
  # (a) cross-model schema verdict from grok
  local schema="$TASKS/verdict.schema.json"
  cat > "$schema" <<'EOF'
{"type":"object","properties":{"verdict":{"type":"string","enum":["pass","fail"]},"reason":{"type":"string"}},"required":["verdict","reason"]}
EOF
  mk_task "$TASKS/review.md" <<EOF
Review this statement for correctness and answer with the required schema:
"A closed-loop pipeline re-runs its check after each fix round and stops on regression."
EOF
  cmd_spawn --run-id "$RUN" --agent-id reviewer --task-file "$TASKS/review.md" --schema "$schema" >/dev/null 2>&1
  local rd; rd="$(agent_dir "$RUN" reviewer)"
  local verdict=""
  # --json-schema results land in `structuredOutput`; fall back to parsing `text`.
  verdict="$(python3 -c "
import json,re
d=json.load(open('$rd/result.json'))
so=d.get('structuredOutput')
if isinstance(so,dict) and so.get('verdict'):
    print(so['verdict'])
else:
    m=re.search(r'\{.*\}',d.get('text','') or '',re.S)
    print(json.loads(m.group(0)).get('verdict','') if m else '')" 2>/dev/null)"

  # (b) Claude-side merged artifact consuming grok outputs
  local merged="$REPORT_DIR/grok-claude-merge.md"
  local merge_ok=false
  if [[ -f "$merged" ]]; then
    merge_ok=true
    for a in "${AGENTS[@]}"; do grep -qs "MARKER_${a^^}" "$merged" || merge_ok=false; done
  fi
  if [[ "$verdict" =~ ^(pass|fail)$ ]] && $merge_ok; then
    gate combined PASS "grok schema verdict='$verdict'; Claude merged all 3 grok outputs" "$merged"
  else
    gate combined FAIL "schema verdict='$verdict' merged_artifact=$merge_ok (needs $merged citing every MARKER_*)" "$merged"
  fi

  # ---------- Gate 6: control (failure honesty, budget guard, idempotent resume) ----------
  local ctl_ok=true ctl=""

  # failure honesty: an agent that cannot produce output must NOT be DONE
  mk_task "$TASKS/impossible.md" <<'EOF'
Do not create any file. Do not write anything to disk. Simply reply with the word SKIP and stop immediately.
EOF
  cmd_spawn --run-id "$RUN" --agent-id impossible --task-file "$TASKS/impossible.md" >/dev/null 2>&1
  local ist; ist="$(get_state "$(agent_dir "$RUN" impossible)")"
  [[ "$ist" == DONE ]] && { ctl_ok=false; ctl+="no-output-agent reported DONE; "; } || ctl+="failure-honesty=$ist ok; "
  if cmd_collect --run-id "$RUN" >/dev/null 2>&1; then ctl_ok=false; ctl+="collect exited 0 despite failure; "
  else ctl+="collect non-zero ok; "; fi

  # budget guard
  local spent; spent="$(python3 -c "import json;print(json.load(open('$REPORT_DIR/grok-fleet-$RUN.json'))['rollup']['total_cost_usd'])" 2>/dev/null || echo 0)"
  local over; over="$(python3 -c "print('yes' if float('$spent')>0.0001 else 'no')")"
  [[ "$over" == yes ]] && ctl+="budget-accounting ok (\$$spent tracked); " || { ctl_ok=false; ctl+="no cost tracked; "; }

  # idempotent resume: re-spawning a DONE agent must skip, not re-bill
  local before_cost; before_cost="$(python3 -c "import json;print(json.load(open('$(agent_dir "$RUN" alpha)/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)"
  cmd_spawn --run-id "$RUN" --agent-id alpha --task-file "$TASKS/alpha.md" >/dev/null 2>&1
  local after_cost; after_cost="$(python3 -c "import json;print(json.load(open('$(agent_dir "$RUN" alpha)/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)"
  if [[ "$before_cost" == "$after_cost" ]]; then ctl+="idempotent-resume ok (no re-bill); "
  else ctl_ok=false; ctl+="resume re-ran agent ($before_cost -> $after_cost); "; fi

  $ctl_ok && gate control PASS "$ctl" "$FLEET_ROOT/$RUN" || gate control FAIL "$ctl" "$FLEET_ROOT/$RUN"

  cmd_stop --all >/dev/null 2>&1
  finish_verify "$RUN" "$t_start" "$iteration" "$as_json"
}

finish_verify() {
  local run="$1" t0="$2" iter="$3" as_json="$4"
  local spent; spent="$(python3 -c "import json;print(json.load(open('$REPORT_DIR/grok-fleet-$run.json'))['rollup']['total_cost_usd'])" 2>/dev/null || echo 0)"
  local ok=true; [[ $GATE_FAIL -eq 0 ]] || ok=false
  local IFS=,
  cat > "$REPORT_DIR/grok-verify.json" <<EOF
{"ok":$ok,"iteration":$iter,"run_id":"$run","gates":[${GATES_JSON[*]}],
 "cost_usd":$spent,"wall_clock_s":$(( $(date +%s) - t0 )),"ts":"$(date -Iseconds)"}
EOF
  $as_json && cat "$REPORT_DIR/grok-verify.json"
  local passed=$(( ${#GATES_JSON[@]} - $(grep -o '"status":"FAIL"' "$REPORT_DIR/grok-verify.json" | wc -l) ))
  log "verify: $passed/${#GATES_JSON[@]} gates PASS, \$$spent, $(( $(date +%s) - t0 ))s"
  return $GATE_FAIL
}
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
| `scripts/grok-fleet.sh` | `template/scripts/grok-fleet.sh` |
| `scripts/grok-verify.sh` | `template/scripts/grok-verify.sh` |
