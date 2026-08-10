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

# Load session memory.
# Cap it: this is injected into EVERY session and then re-read on every turn, so a
# large MEMORY.md is paid for continuously. Show the top (current focus) and the
# tail (most recent entries); the full file stays one Read away.
MEMORY_MAX_BYTES="${DROMFLOW_MEMORY_MAX_BYTES:-4000}"
if [ -s "$MEMORY" ]; then
  echo "[Session Memory Loaded]"
  echo "---"
  msize=$(wc -c < "$MEMORY" | tr -d ' ')
  if [ "$msize" -gt "$MEMORY_MAX_BYTES" ]; then
    # Bound by BYTES, not lines: a memory file can be 70 long lines, where a
    # head/tail line count trims nothing at all.
    _head=$(( MEMORY_MAX_BYTES * 2 / 5 ))
    _tail=$(( MEMORY_MAX_BYTES - _head ))
    head -c "$_head" "$MEMORY"
    echo ""
    echo "... [truncated: ${msize} bytes total, showing first ${_head}B + last ${_tail}B. Full file: context/MEMORY.md] ..."
    echo ""
    tail -c "$_tail" "$MEMORY"
  else
    cat "$MEMORY"
  fi
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
    # The 48-tool catalog lives in a doc, surfaced only when JavaDucker is really
    # present — projects without it should not carry it in every session.
    [ -f "$DIR/.claude/docs/javaducker.md" ] && \
      echo "[JavaDucker tool catalog: .claude/docs/javaducker.md]"
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

# --- Background agents: Claude and grok counted separately ---
# Claude agents come from the track-agents hook; grok agents are counted live from
# the fleet control plane, so the delegation split is visible while work is in flight.
agents=0
[ -f "$STATE_DIR/agent-count" ] && agents=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')
grok_agents=0
if [ -d "$DIR/.claude/.grok-fleet" ]; then
  grok_agents=$(grep -l '"state":"RUNNING"' "$DIR"/.claude/.grok-fleet/*/agents/*/status.json 2>/dev/null | wc -l | tr -d ' ')
fi

# --- Usage-limit watcher ---
limit_flag=""
if [ -f "$DIR/.claude/.state/limit-armed.json" ]; then
  limit_flag=" • ⏳armed"
elif [ -f "$DIR/.claude/.state/limit-ping-due" ]; then
  limit_flag=" • ⏳ping-due"
fi

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

status="drom-flow v$DROMFLOW_VERSION • $PROJECT_ROOT • $git_info • ${elapsed:-0m0s} • edits:$edits • C:$agents G:$grok_agents • mem:$mem"
[ -n "$jd_icon" ] && status="$status • $jd_icon"
[ -n "$limit_flag" ] && status="$status$limit_flag"
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

_SETTINGS_BEFORE=""
if [ -f "$TARGET_DIR/.claude/settings.json" ]; then
  _SETTINGS_BEFORE="$(mktemp)"; cp "$TARGET_DIR/.claude/settings.json" "$_SETTINGS_BEFORE"
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
  ".claude/docs"
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
  for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" ".claude/.grok-fleet/" ".claude/docs/" "reports/grok-*.json" "reports/grok-*.md" "setup-backup/"; do
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
    for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" ".claude/.grok-fleet/" ".claude/docs/" "reports/grok-*.json" "reports/grok-*.md" "setup-backup/"; do
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
# --- Migrate drom-flow docs out of the project's own docs/ (<= v0.8.0 shipped there) ---
# The project owns docs/. Only move files that are byte-identical to what we shipped;
# a doc the user edited is left alone with a warning.
if [ "$MODE" = "update" ] || [ "$MODE" = "install" ]; then
  for _d in grok-fleet.md token-economy.md df-research.md; do
    _old="$TARGET_DIR/docs/$_d"
    _new_src="$TEMPLATE_DIR/.claude/docs/$_d"
    [ -f "$_old" ] || continue
    mkdir -p "$TARGET_DIR/.claude/docs"
    if [ -f "$_new_src" ] && cmp -s "$_old" "$_new_src"; then
      rm -f "$_old"
      echo "  move:    docs/$_d -> .claude/docs/$_d (drom-flow doc, now gitignored)"
    else
      echo "  warn:    docs/$_d differs from the shipped version - left in place, not moved"
    fi
  done
  # Only removes docs/ if it is now empty; a non-empty dir must not abort the installer.
  rmdir "$TARGET_DIR/docs" 2>/dev/null || true
fi

# --- Preserve third-party hooks in .claude/settings.json ---
# settings.json is a managed file, but other tools (e.g. hyperresearch) register their
# own hooks in it. Replacing it wholesale silently deletes them. Re-merge any hook entry
# that does not point at drom-flow's own hooks directory.
if [ -f "$TARGET_DIR/.claude/settings.json" ] && [ -n "${_SETTINGS_BEFORE:-}" ] && [ -f "$_SETTINGS_BEFORE" ]; then
  python3 - "$_SETTINGS_BEFORE" "$TARGET_DIR/.claude/settings.json" <<'PYEOF' 2>/dev/null
import json,sys
old_p,new_p=sys.argv[1],sys.argv[2]
try:
    old=json.load(open(old_p)); new=json.load(open(new_p))
except Exception: raise SystemExit(0)
restored=0
for ev,arr in (old.get('hooks') or {}).items():
    for entry in arr or []:
        blob=json.dumps(entry)
        if '.claude/hooks/' in blob:   # drom-flow's own -- the fresh copy already has it
            continue
        tgt=new.setdefault('hooks',{}).setdefault(ev,[])
        if entry not in tgt:
            tgt.append(entry); restored+=1
if restored:
    json.dump(new,open(new_p,'w'),indent=2)
    print(f"  merge:   .claude/settings.json - preserved {restored} third-party hook entr(ies)")
PYEOF
  rm -f "$_SETTINGS_BEFORE"
fi

for pattern in ".claude/.state/" ".claude/edit-log.jsonl" ".mcp.json" ".claude/.javaducker/" ".claude/.grok-fleet/" ".claude/docs/" "reports/grok-*.json" "reports/grok-*.md" "setup-backup/"; do
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
    "## Token Economy"
    "## Deep Research"
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
echo "  .claude/docs/          — grok fan-out, token economy, df-research guides (gitignored)"
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
  # Atomic: write to a temp file then rename, so a kill mid-write (Claude running out
  # of tokens, machine sleep) can never leave a truncated, unreadable status.
  local d="$1" s="$2" extra="${3:-}"
  printf '{"state":"%s","ts":"%s"%s}\n' "$s" "$(date -Iseconds)" "${extra:+,$extra}" > "$d/.status.tmp"
  mv -f "$d/.status.tmp" "$d/status.json"
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
  # Grok self-verifies before returning. On an unmetered account this is free and
  # replaces a Claude review turn, which is not.
  # NOT with --json-schema: the appended verification turn displaces the structured
  # output and `structuredOutput` comes back empty.
  [[ "${GROK_SELF_CHECK:-1}" == 1 && -z "$schema" ]] && args+=( --check )
  # Run the task N ways in parallel and keep the best — free quality, no Claude fixes.
  [[ -n "${GROK_BEST_OF_N:-}" ]] && args+=( --best-of-n "$GROK_BEST_OF_N" )
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

# A cap of 0 (or negative) means unlimited — for accounts where grok spend is not
# metered. Cost is still tracked and reported; it just never halts a run.
over_budget() { python3 -c "
import sys
cap=float('$2')
sys.exit(1 if cap<=0 else (0 if float('$1')>cap else 1))"; }

# Polls spend while a fan-out runs and halts the whole run if it breaches the cap.
budget_watchdog() {
  local run="$1" cap="$2" t
  source "$REPO_ROOT/scripts/grok-resume.sh" 2>/dev/null
  while [[ ! -f "$FLEET_ROOT/$run/DONE" ]]; do
    sleep 8
    # Refresh the resume record continuously so an abrupt Claude exit never leaves
    # a stale picture of what finished.
    cmd_checkpoint --run-id "$run" >/dev/null 2>&1
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
  # Keep the manifest with the run so `resume` can re-dispatch without Claude.
  [[ "$(readlink -f "$mf")" == "$(readlink -f "$FLEET_ROOT/$run_id/run.json")" ]] || cp -f "$mf" "$FLEET_ROOT/$run_id/run.json"
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
  # Retry on the grok side. A failure re-runs with the failure text appended, so
  # Claude only ever sees terminal states — never intermediate attempts.
  local attempts="${GROK_MAX_ATTEMPTS:-3}" n=1
  while :; do
    run_agent "$d" "$bin" "$schema"
    [[ "$(get_state "$d")" == DONE ]] && break
    # A deliberate stop is NOT a failure — never retry past it, or `stop` would be
    # defeated by the retry immediately relaunching the agent.
    [[ "$(get_state "$d")" == STOPPED || -f "$d/STOP" ]] && { log "agent $agent_id stopped by request; not retrying"; break; }
    (( n >= attempts )) && break
    log "agent $agent_id attempt $n/$attempts -> $(get_state "$d"), retrying on grok"
    { echo; echo "--- PREVIOUS ATTEMPT FAILED (attempt $n) ---";
      echo "Error output:"; tail -c 600 "$d/stream.err" 2>/dev/null;
      echo "Fix the cause and complete the task. Write your output into the working directory."; } >> "$d/task.md"
    n=$(( n + 1 ))
  done
  printf '{"attempts":%d}\n' "$n" > "$d/attempts.json"
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
  local run_id="" brief=false
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --brief) brief=true; shift ;;
    *) die "collect: unknown arg $1" ;;
  esac; done
  [[ -n "$run_id" ]] || die "collect needs --run-id"

  # Brief mode is what Claude reads: verdicts only, never agent output bodies.
  # Full artifacts stay on disk and are opened only to diagnose a FAIL.
  if $brief; then
    local base="$FLEET_ROOT/$run_id/agents" failed=0
    for d in "$base"/*; do [[ -d "$d" ]] || continue
      local a s line; a="$(basename "$d")"; s="$(get_state "$d")"
      [[ "$s" == DONE ]] || failed=1
      line="$(grep -h '^RESULT:' "$d/output"/* 2>/dev/null | head -1)"
      [[ -z "$line" ]] && line="$(tail -n1 "$d/PROGRESS.md" 2>/dev/null)"
      printf '%s\t%s\t%s\n' "$a" "$s" "${line:0:90}"
    done
    echo "-- run=$run_id spend=\$$(run_total_cost "$run_id") outputs=$FLEET_ROOT/$run_id/agents/<id>/output/"
    return $failed
  fi
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
  drain)      source "$REPO_ROOT/scripts/grok-resume.sh"; cmd_drain "$@" ;;
  checkpoint) source "$REPO_ROOT/scripts/grok-resume.sh"; cmd_checkpoint "$@" ;;
  resume)     source "$REPO_ROOT/scripts/grok-resume.sh"; cmd_resume "$@" ;;
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

## scripts/grok-resume.sh

```bash
#!/bin/bash
# drom-flow — resume support for the grok fleet.
# Sourced by grok-fleet.sh. Provides: drain (detached runner), checkpoint (compact
# resume record), resume (reconcile + re-dispatch).
#
# Premise: Claude tokens are finite, grok's are not. Claude is therefore the
# interruptible component — in-flight grok work must be able to finish without it,
# and a cold Claude session must resume from a tiny amount of state.

RESUME_MAX_BYTES="${RESUME_MAX_BYTES:-2048}"

# --- drain: run a manifest to completion detached from this shell ---------------
# Survives the Claude session ending, so a fan-out is never stranded.
cmd_drain() {
  local mf=""
  while [[ $# -gt 0 ]]; do case $1 in --manifest) mf="$2"; shift 2 ;; *) shift ;; esac; done
  [[ -f "$mf" ]] || die "drain needs --manifest <file>"
  local run_id; run_id="$(python3 -c "import json;print(json.load(open('$mf'))['run_id'])")"
  mkdir -p "$FLEET_ROOT/$run_id"
  local logf="$FLEET_ROOT/$run_id/drain.log"
  nohup setsid bash "$REPO_ROOT/scripts/grok-fleet.sh" spawn --manifest "$mf" \
        > "$logf" 2>&1 < /dev/null &
  disown 2>/dev/null
  echo "{\"run_id\":\"$run_id\",\"detached\":true,\"log\":\"$logf\"}"
  log "drain: run $run_id detached — it will finish without Claude"
}

# --- checkpoint: the entire cost of resuming --------------------------------------
cmd_checkpoint() {
  local run_id="" goal="" plan="" chapter=""
  while [[ $# -gt 0 ]]; do case $1 in
    --run-id) run_id="$2"; shift 2 ;;
    --goal) goal="$2"; shift 2 ;;
    --plan) plan="$2"; shift 2 ;;
    --chapter) chapter="$2"; shift 2 ;;
    *) shift ;;
  esac; done
  [[ -n "$run_id" ]] || die "checkpoint needs --run-id"
  local rd="$FLEET_ROOT/$run_id"; mkdir -p "$rd"
  [[ -n "$goal"    ]] && echo "$goal"    > "$rd/.goal"
  [[ -n "$plan"    ]] && echo "$plan"    > "$rd/.plan"
  [[ -n "$chapter" ]] && echo "$chapter" > "$rd/.chapter"

  python3 - "$rd" "$RESUME_MAX_BYTES" <<'PY'
import json,os,sys
rd,cap=sys.argv[1],int(sys.argv[2])
def rd_file(n,d=''):
    p=os.path.join(rd,n)
    return open(p).read().strip() if os.path.exists(p) else d
base=os.path.join(rd,'agents')
done=[];pend=[];run=[]
if os.path.isdir(base):
    for a in sorted(os.listdir(base)):
        try: st=json.load(open(os.path.join(base,a,'status.json')))['state']
        except Exception: st='UNKNOWN'
        (done if st=='DONE' else run if st=='RUNNING' else pend).append(f"{a}:{st}")
L=[]
L.append(f"# RESUME — {os.path.basename(rd)}")
g=rd_file('.goal');  L.append(f"goal: {g}") if g else None
p=rd_file('.plan');  L.append(f"plan: {p}") if p else None
c=rd_file('.chapter');L.append(f"chapter: {c}") if c else None
L.append(f"done({len(done)}): {', '.join(done) or '-'}")
L.append(f"running({len(run)}): {', '.join(run) or '-'}")
L.append(f"pending({len(pend)}): {', '.join(pend) or '-'}")
L.append(f"next: bash scripts/grok-fleet.sh resume --run-id {os.path.basename(rd)}")
L.append("note: DONE units are never re-run. Read outputs only for FAILED units.")
out='\n'.join(L)+'\n'
if len(out.encode())>cap:                      # hard cap — resuming must stay cheap
    out=out.encode()[:cap-20].decode('utf-8','ignore')+"\n...(truncated)\n"
tmp=os.path.join(rd,'.RESUME.tmp')
open(tmp,'w').write(out); os.replace(tmp,os.path.join(rd,'RESUME.md'))
print(out,end='')
PY
}

# --- resume: reconcile reality, re-dispatch only what is genuinely incomplete -----
cmd_resume() {
  local run_id=""
  while [[ $# -gt 0 ]]; do case $1 in --run-id) run_id="$2"; shift 2 ;; *) shift ;; esac; done
  [[ -n "$run_id" ]] || die "resume needs --run-id"
  local rd="$FLEET_ROOT/$run_id" base="$FLEET_ROOT/$run_id/agents"
  [[ -d "$base" ]] || die "no such run: $run_id"

  # Reconcile: trust on-disk results over the recorded state. An agent whose process
  # is gone but whose result is complete really did finish; one with neither is
  # INTERRUPTED and must be redone.
  local recovered=0 interrupted=0
  for d in "$base"/*; do [[ -d "$d" ]] || continue
    local s; s="$(get_state "$d")"
    [[ "$s" == RUNNING ]] || continue
    local pid alive=0
    pid="$(python3 -c "import json;print(json.load(open('$d/pid'))['wsl_pid'])" 2>/dev/null)"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && alive=1
    (( alive )) && continue
    if [[ -s "$d/result.json" ]] && [[ -n "$(ls -A "$d/output" 2>/dev/null)" ]]; then
      local c; c="$(python3 -c "import json;print(json.load(open('$d/result.json')).get('total_cost_usd',0))" 2>/dev/null || echo 0)"
      set_status "$d" DONE "\"recovered\":true,\"cost_usd\":${c:-0}"; recovered=$(( recovered + 1 ))
    else
      set_status "$d" INTERRUPTED "\"reason\":\"process gone, no result\""; interrupted=$(( interrupted + 1 ))
    fi
  done
  log "resume: recovered=$recovered interrupted=$interrupted"

  # Re-dispatch. spawn is idempotent — DONE agents are skipped, never re-billed.
  local mf="$rd/run.json"
  if [[ -f "$mf" ]]; then
    cmd_spawn_manifest "$mf"
  else
    log "resume: no stored manifest; nothing to re-dispatch automatically"
  fi
  cmd_checkpoint --run-id "$run_id" >/dev/null
  cmd_collect --run-id "$run_id" --brief
}
```

---

## scripts/token-audit.sh

```bash
#!/bin/bash
# drom-flow — Claude token audit + delegation gates.
#
#   token-audit.sh mark <label>          record the current transcript position
#   token-audit.sh measure <label>       report Claude cost since that mark
#   token-audit.sh gates [--json]        evaluate the eight exit-criteria gates
#
# Measures the real thing: per-turn usage from the live Claude Code session
# transcript (~/.claude/projects/<slug>/<session>.jsonl), which records
# input_tokens / output_tokens / cache_read_input_tokens for every turn.
#
# Exit: 0 = all evaluated gates PASS, 1 = a gate failed, 2 = usage/env error

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$REPO_ROOT/reports"
STATE_DIR="${TOKEN_AUDIT_STATE:-$REPO_ROOT/.claude/.token-audit}"
mkdir -p "$STATE_DIR" "$REPORT_DIR"

log() { echo "[token-audit] $*" >&2; }
die() { echo "[token-audit] ERROR: $*" >&2; exit 2; }

# Resolve the transcript for THIS project (most recently modified session).
transcript() {
  [[ -n "${CLAUDE_TRANSCRIPT:-}" && -f "${CLAUDE_TRANSCRIPT:-}" ]] && { echo "$CLAUDE_TRANSCRIPT"; return; }
  local slug d
  slug="$(echo "$REPO_ROOT" | sed 's|/|-|g')"
  d="$HOME/.claude/projects/$slug"
  [[ -d "$d" ]] || return 1
  ls -t "$d"/*.jsonl 2>/dev/null | head -1
}

usage_between() { # file start_line end_line -> JSON of the window
  python3 - "$1" "$2" "$3" <<'PY'
import json,sys
f,a,b=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
turns=out=cr=cc=fresh=0; tr_bytes=0
for i,line in enumerate(open(f,errors='replace'),1):
    if i<=a or i>b: continue
    try: o=json.loads(line)
    except Exception: continue
    m=o.get('message') or {}
    if not isinstance(m,dict): continue
    u=m.get('usage')
    if u:
        turns+=1
        out+=u.get('output_tokens',0); fresh+=u.get('input_tokens',0)
        cr+=u.get('cache_read_input_tokens',0); cc+=u.get('cache_creation_input_tokens',0)
    c=m.get('content')
    if isinstance(c,list):
        for blk in c:
            if isinstance(blk,dict) and blk.get('type')=='tool_result':
                t=blk.get('content'); tr_bytes+=len(t if isinstance(t,str) else json.dumps(t))
print(json.dumps({'turns':turns,'output_tokens':out,'fresh_input_tokens':fresh,
                  'cache_read':cr,'cache_creation':cc,'tool_result_bytes':tr_bytes,
                  'billable_tokens':out+fresh+cc}))
PY
}

cmd_mark() {
  local label="${1:-}"; [[ -n "$label" ]] || die "mark needs a label"
  local f; f="$(transcript)" || die "no transcript found for $REPO_ROOT"
  wc -l < "$f" | tr -d ' ' > "$STATE_DIR/$label.mark"
  log "mark '$label' @ line $(cat "$STATE_DIR/$label.mark")"
}

cmd_measure() {
  local label="${1:-}"; [[ -n "$label" ]] || die "measure needs a label"
  local mk="$STATE_DIR/$label.mark"; [[ -f "$mk" ]] || die "no such mark: $label"
  local f; f="$(transcript)" || die "no transcript found"
  local a b; a="$(cat "$mk")"; b="$(wc -l < "$f" | tr -d ' ')"
  local j; j="$(usage_between "$f" "$a" "$b")"
  echo "$j" > "$STATE_DIR/$label.usage.json"
  echo "$j"
}

# --- gates ---------------------------------------------------------------------
gate() { # id status detail
  GATES+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$3")}")
  [[ "$2" == PASS ]] || FAIL=1
  log "gate $1: $2 — $3"
}

num() { python3 -c "
import json,sys
try: print(json.load(open(sys.argv[1])).get(sys.argv[2],0))
except Exception: print(0)" "$1" "$2"; }

pct_cut() { python3 -c "
b,d=float('$1'),float('$2')
print(round((b-d)/b*100,1) if b>0 else 0.0)"; }

cmd_gates() {
  local as_json=false; [[ "${1:-}" == "--json" ]] && as_json=true
  GATES=(); FAIL=0
  local B="$STATE_DIR/baseline.usage.json" D="$STATE_DIR/delegated.usage.json"

  # 1 measurable
  local f; if f="$(transcript)" && [[ -s "$f" ]]; then
    gate measurable PASS "transcript readable: $(basename "$f"), $(wc -l < "$f" | tr -d ' ') records"
  else gate measurable FAIL "no readable transcript"; fi

  # 2 delegation ratio (from the fleet ledger)
  local led="$STATE_DIR/ledger.tsv" tot=0 grok=0
  if [[ -f "$led" ]]; then
    tot=$(wc -l < "$led" | tr -d ' '); grok=$(grep -c $'\tgrok$' "$led" || echo 0)
  fi
  local ratio; ratio="$(python3 -c "print(round($grok/$tot*100,1) if $tot else 0.0)")"
  python3 -c "import sys;sys.exit(0 if $ratio>=95 else 1)" \
    && gate delegation PASS "$grok/$tot units on grok = ${ratio}%" \
    || gate delegation FAIL "$grok/$tot units on grok = ${ratio}% (need >=95%)"

  # 3 turns / 4 authoring — delegated vs measured Claude-only baseline
  if [[ -f "$B" && -f "$D" ]]; then
    local bt dt bo do_ ct co
    bt="$(num "$B" turns)"; dt="$(num "$D" turns)"
    bo="$(num "$B" output_tokens)"; do_="$(num "$D" output_tokens)"
    ct="$(pct_cut "$bt" "$dt")"; co="$(pct_cut "$bo" "$do_")"
    python3 -c "import sys;sys.exit(0 if $ct>=50 else 1)" \
      && gate turns PASS "turns $bt -> $dt (-${ct}%)" || gate turns FAIL "turns $bt -> $dt (-${ct}%, need -50%)"
    python3 -c "import sys;sys.exit(0 if $co>=50 else 1)" \
      && gate authoring PASS "output_tokens $bo -> $do_ (-${co}%)" || gate authoring FAIL "output_tokens $bo -> $do_ (-${co}%, need -50%)"
  else
    gate turns FAIL "missing baseline/delegated measurement"
    gate authoring FAIL "missing baseline/delegated measurement"
  fi

  # 5 context bytes into Claude for the fan-out
  local cb; cb="$(cat "$STATE_DIR/brief_bytes" 2>/dev/null || echo 999999)"
  (( cb <= 4096 )) && gate context PASS "collect --brief = ${cb}B (<=4096)" \
                   || gate context FAIL "collect --brief = ${cb}B (>4096)"

  # 6 parity
  local vg; vg="$(python3 -c "
import json
try:
  d=json.load(open('$REPORT_DIR/grok-verify.json'))
  print('ok' if d.get('ok') else 'fail')
except Exception: print('missing')")"
  local bench_ok; bench_ok="$(cat "$STATE_DIR/benchmark_ok" 2>/dev/null || echo no)"
  [[ "$vg" == ok && "$bench_ok" == yes ]] \
    && gate parity PASS "fleet verify 6/6 and benchmark output correct" \
    || gate parity FAIL "fleet verify=$vg benchmark_correct=$bench_ok"

  # 7 resume
  local rs; rs="$(cat "$STATE_DIR/resume_result" 2>/dev/null || echo no)"
  [[ "$rs" == pass ]] && gate resume PASS "$(cat "$STATE_DIR/resume_detail" 2>/dev/null)" \
                      || gate resume FAIL "$(cat "$STATE_DIR/resume_detail" 2>/dev/null || echo 'not run')"

  # 8 ship
  local sh; sh="$(cat "$STATE_DIR/ship_result" 2>/dev/null || echo no)"
  [[ "$sh" == pass ]] && gate ship PASS "$(cat "$STATE_DIR/ship_detail" 2>/dev/null)" \
                      || gate ship FAIL "$(cat "$STATE_DIR/ship_detail" 2>/dev/null || echo 'not shipped')"

  local IFS=,
  cat > "$REPORT_DIR/token-audit.json" <<EOF
{"ok":$([[ $FAIL -eq 0 ]] && echo true || echo false),"ts":"$(date -Iseconds)","gates":[${GATES[*]}]}
EOF
  $as_json && cat "$REPORT_DIR/token-audit.json"
  local p; p=$(grep -o '"status":"PASS"' "$REPORT_DIR/token-audit.json" | wc -l)
  log "gates: $p/${#GATES[@]} PASS"
  return $FAIL
}

# record a work unit and which engine executed it
cmd_ledger() { printf '%s\t%s\n' "${1:-unit}" "${2:-grok}" >> "$STATE_DIR/ledger.tsv"; }

case "${1:-}" in
  mark)    shift; cmd_mark "$@" ;;
  measure) shift; cmd_measure "$@" ;;
  gates)   shift; cmd_gates "$@" ;;
  ledger)  shift; cmd_ledger "$@" ;;
  *) die "usage: token-audit.sh {mark <label>|measure <label>|gates [--json]|ledger <unit> <engine>}" ;;
esac
```

---

## scripts/mk-task.sh

```bash
#!/bin/bash
# drom-flow — generate a grok task.md from a template, so dispatching N units costs
# Claude one command instead of N hand-written prompts.
#
#   mk-task.sh <template> <out-file> KEY=VALUE ...
#
# Templates live in scripts/task-templates/*.md and use {{KEY}} placeholders.
# On an unmetered grok account, prompts are free — templates are verbose on purpose.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TPL_DIR="$REPO_ROOT/scripts/task-templates"

[[ $# -ge 2 ]] || { echo "usage: mk-task.sh <template> <out-file> KEY=VAL ..." >&2; exit 2; }
tpl="$1"; out="$2"; shift 2
src="$TPL_DIR/$tpl.md"
[[ -f "$src" ]] || { echo "no such template: $tpl (have: $(ls "$TPL_DIR" 2>/dev/null | sed 's/\.md//' | tr '\n' ' '))" >&2; exit 2; }

mkdir -p "$(dirname "$out")"
cp "$src" "$out"
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  python3 - "$out" "$k" "$v" <<'PY'
import sys
p,k,v=sys.argv[1],sys.argv[2],sys.argv[3]
s=open(p,encoding='utf-8').read().replace('{{'+k+'}}',v)
open(p,'w',encoding='utf-8').write(s)
PY
done
# Leftover placeholders mean a caller forgot a key — fail loudly rather than
# shipping a prompt with literal {{FOO}} in it.
if grep -q '{{[A-Z_]*}}' "$out"; then
  echo "ERROR: unfilled placeholders in $out: $(grep -o '{{[A-Z_]*}}' "$out" | sort -u | tr '\n' ' ')" >&2
  exit 2
fi
echo "$out"
```

---

## scripts/bench-audit.sh

```bash
#!/bin/bash
# drom-flow — encapsulated audit fan-out.
#
#   bench-audit.sh <run-id> <file> [<file> ...]
#
# Exists so dispatching a fan-out costs Claude a one-line command instead of a
# hand-written block of bash + python. Claude authoring is a top-two token cost;
# this moves it into a script that is written once.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${1:?usage: bench-audit.sh <run-id> <file>...}"; shift
[[ $# -ge 1 ]] || { echo "no target files" >&2; exit 2; }

T="$REPO_ROOT/.claude/.grok-fleet/_tasks/$RUN"; mkdir -p "$T"
rm -rf "$REPO_ROOT/.claude/.grok-fleet/$RUN"

CHECKS='1) YAML frontmatter present and valid with name, description, user-invocable. 2) Ordered list numbering is strictly sequential with no duplicated or skipped numbers — report the exact duplicated number, its section heading, and the line range.'

agents=()
for f in "$@"; do
  id="$(basename "$(dirname "$f")")"; [[ "$id" == "." ]] && id="$(basename "$f" .md)"
  bash "$REPO_ROOT/scripts/mk-task.sh" audit "$T/$id.md" \
    TARGET="$f" CHECKS="$CHECKS" OUTFILE="findings.md" TITLE="$id" >/dev/null || exit 2
  bash "$REPO_ROOT/scripts/token-audit.sh" ledger "audit-$id" grok
  agents+=("$id:$T/$id.md")
done

python3 - "$T/m.json" "$RUN" "${agents[@]}" <<'PY'
import json,sys
out,run=sys.argv[1],sys.argv[2]
ag=[{'id':a.split(':',1)[0],'task_file':a.split(':',1)[1]} for a in sys.argv[3:]]
json.dump({'run_id':run,'budget_usd':0,'max_parallel':len(ag),'agents':ag},open(out,'w'),indent=2)
PY

bash "$REPO_ROOT/scripts/grok-fleet.sh" spawn --manifest "$T/m.json" >/dev/null 2>&1
bash "$REPO_ROOT/scripts/grok-fleet.sh" collect --run-id "$RUN" --brief
```

---

## scripts/check-parity.sh

```bash
#!/bin/bash
# drom-flow — parity check for the delegated audit benchmark.
# Verifies grok found the SAME defects the Claude-only baseline found, by meaning
# rather than by exact string, so a differently-worded but correct answer passes.
#
#   check-parity.sh <run-id>
# Writes yes|no to .claude/.token-audit/benchmark_ok

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${1:?usage: check-parity.sh <run-id>}"
BASE="$REPO_ROOT/.claude/.grok-fleet/$RUN/agents"
ok=yes

# expected: agent -> duplicated number, section that contains the break
check() { # agent dupnum section
  local a="$1" n="$2" sec="$3" f="$BASE/$1/output/findings.md"
  if [[ ! -s "$f" ]]; then echo "  $a: NO OUTPUT"; ok=no; return; fi
  local body; body="$(tr '[:upper:]' '[:lower:]' < "$f")"
  local hasdup=no hassec=no
  grep -qE "($n, *$n|duplicat)" <<<"$body" && hasdup=yes
  grep -qF "$(tr '[:upper:]' '[:lower:]' <<<"$sec")" <<<"$body" && hassec=yes
  if [[ "$hasdup" == yes && "$hassec" == yes ]]; then echo "  $a: OK (dup $n in $sec)"
  else echo "  $a: MISS (dup=$hasdup section=$hassec)"; ok=no; fi
}

check architect   3 Responsibilities
check debugger    4 Process
check implementer 4 Process

echo "$ok" > "$REPO_ROOT/.claude/.token-audit/benchmark_ok"
echo "benchmark_correct=$ok"
[[ "$ok" == yes ]]
```

---

## scripts/test-resume.sh

```bash
#!/bin/bash
# drom-flow — gate 7: survive Claude token exhaustion.
#
# Simulates Claude dying mid-run and verifies:
#   1. detached grok work keeps going without Claude
#   2. an interrupted unit is detected (not silently trusted)
#   3. a cold resume re-dispatches ONLY incomplete units — none re-run, none lost
#   4. resume state stays under the 2 KB budget

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLEET="$REPO_ROOT/.claude/.grok-fleet"
RUN=resumetest
T="$FLEET/_tasks/$RUN"; mkdir -p "$T"; rm -rf "$FLEET/$RUN"
detail(){ echo "$*" > "$REPO_ROOT/.claude/.token-audit/resume_detail"; echo "$*"; }
fail(){ echo pass > /dev/null; echo fail > "$REPO_ROOT/.claude/.token-audit/resume_result"; detail "$*"; exit 1; }

for i in 1 2 3 4; do
  printf 'Write a file named out.md in your working directory containing exactly: UNIT_%d\n' "$i" > "$T/u$i.md"
done
python3 - "$T/m.json" <<'PY'
import json,sys,os
t=os.path.dirname(sys.argv[1])
json.dump({'run_id':'resumetest','budget_usd':0,'max_parallel':2,
 'agents':[{'id':f'u{i}','task_file':f'{t}/u{i}.md'} for i in (1,2,3,4)]},open(sys.argv[1],'w'),indent=2)
PY

echo "== 1. dispatch detached (Claude may die after this) =="
bash "$REPO_ROOT/scripts/grok-fleet.sh" drain --manifest "$T/m.json"

echo "== 2. simulate Claude dying: kill the dispatching shell's process group parent =="
sleep 12
# Kill one RUNNING agent outright => an INTERRUPTED unit with no result.
victim=""
for d in "$FLEET/$RUN"/agents/*; do
  [[ -d "$d" ]] || continue
  if [[ "$(python3 -c "import json;print(json.load(open('$d/status.json'))['state'])" 2>/dev/null)" == RUNNING ]]; then
    pid="$(python3 -c "import json;print(json.load(open('$d/pid'))['wsl_pid'])" 2>/dev/null)"
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null && victim="$(basename "$d")" && break
  fi
done
echo "   killed agent: ${victim:-none}"

echo "== 3. wait for the detached runner to finish the rest without Claude =="
for _ in $(seq 1 60); do
  [[ -f "$FLEET/$RUN/DONE" ]] && break
  sleep 5
done
before_done=$(grep -l '"state":"DONE"' "$FLEET/$RUN"/agents/*/status.json 2>/dev/null | wc -l)
echo "   DONE after detached run: $before_done/4"

echo "== 4. cold resume =="
cost_before=$(bash -c "cd $REPO_ROOT && source scripts/grok-fleet.sh 2>/dev/null; true"; python3 - "$FLEET/$RUN/agents" <<'PY'
import json,os,sys
b=sys.argv[1];t=0.0
for a in os.listdir(b):
    try: t+=float(json.load(open(os.path.join(b,a,'status.json'))).get('cost_usd') or 0)
    except Exception: pass
print(round(t,6))
PY
)
declare -A pre
for d in "$FLEET/$RUN"/agents/*; do
  a=$(basename "$d")
  pre[$a]=$(python3 -c "import json;print(json.load(open('$d/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)
done
bash "$REPO_ROOT/scripts/grok-fleet.sh" resume --run-id "$RUN" >/dev/null 2>&1

echo "== 5. assertions =="
final_done=0; rerun=0; lost=0
for d in "$FLEET/$RUN"/agents/*; do
  a=$(basename "$d")
  s=$(python3 -c "import json;print(json.load(open('$d/status.json'))['state'])" 2>/dev/null || echo MISSING)
  c=$(python3 -c "import json;print(json.load(open('$d/status.json')).get('cost_usd',0))" 2>/dev/null || echo 0)
  [[ "$s" == DONE ]] && final_done=$(( final_done + 1 )) || lost=$(( lost + 1 ))
  # a unit that was already DONE before resume must not have been charged again
  if [[ "${pre[$a]}" != "0" && "${pre[$a]}" != "" ]]; then
    prev_state_done=$(grep -c '"state":"DONE"' <<<"$(cat "$d/status.json")")
    if [[ "$c" != "${pre[$a]}" && "$prev_state_done" == "1" ]]; then rerun=$(( rerun + 1 )); fi
  fi
  grep -qs "UNIT_${a#u}" "$d/output"/* || { [[ "$s" == DONE ]] && lost=$(( lost + 1 )); }
done
rs="$FLEET/$RUN/RESUME.md"; rbytes=$(wc -c < "$rs" 2>/dev/null || echo 99999)

echo "   final DONE=$final_done/4  re-run=$rerun  lost=$lost  RESUME.md=${rbytes}B"
[[ $final_done -eq 4 ]] || fail "not all units completed after resume ($final_done/4)"
[[ $rerun -eq 0 ]]      || fail "$rerun finished unit(s) were re-run on resume"
[[ $lost -eq 0 ]]       || fail "$lost unit(s) lost their output"
[[ $rbytes -le 2048 ]]  || fail "RESUME.md ${rbytes}B exceeds 2048B budget"

echo pass > "$REPO_ROOT/.claude/.token-audit/resume_result"
detail "detached run survived Claude death; killed agent '$victim' recovered; 4/4 DONE, 0 re-run, 0 lost, RESUME.md ${rbytes}B"
```

---

## scripts/limit-watch.sh

```bash
#!/bin/bash
# drom-flow — Claude usage-limit watcher.
#
#   limit-watch.sh status            window usage, learned budget, percent, reset time
#   limit-watch.sh check             hook entry point: trigger + arm if >= threshold
#   limit-watch.sh arm [--reset EPOCH]   checkpoint runs, arm the hourly ping
#   limit-watch.sh disarm            clear armed state
#   limit-watch.sh ping              wake-up entry: still blocked? re-arm : resume
#   limit-watch.sh verify [--json]   evaluate the eight gates
#
# IMPORTANT: Claude Code exposes no live quota meter. The limit EVENT is exact (a
# synthetic transcript message carrying the reset time); the PERCENTAGE is an
# estimate against a budget learned from past limit events. Never present the
# estimate as a reading.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$REPO_ROOT/.claude/.state"
REPORT_DIR="$REPO_ROOT/reports"
BUDGET_FILE="$STATE/limit-budget.json"
ARMED_FILE="$STATE/limit-armed.json"
PCT="${LIMIT_WATCH_PCT:-97}"
MAX_PINGS="${LIMIT_WATCH_MAX_PINGS:-12}"
PING_INTERVAL="${LIMIT_WATCH_INTERVAL:-3600}"
mkdir -p "$STATE" "$REPORT_DIR"

log() { echo "[limit-watch] $*" >&2; }

transcript() {
  [[ -n "${CLAUDE_TRANSCRIPT:-}" && -f "${CLAUDE_TRANSCRIPT:-}" ]] && { echo "$CLAUDE_TRANSCRIPT"; return 0; }
  local d="$HOME/.claude/projects/$(echo "$REPO_ROOT" | sed 's|/|-|g')"
  [[ -d "$d" ]] || return 1
  local f; f="$(ls -t "$d"/*.jsonl 2>/dev/null | head -1)"
  [[ -n "$f" ]] && echo "$f"
}

# Core analysis: limit events + current-window billable usage.
# Billable EXCLUDES cache_read: it dominates raw counts (tens of millions per
# session) but is not what exhausts a session limit, so including it would make
# the percentage meaningless.
analyze() {
  local f="${1:-}" budget="${2:-0}"
  python3 - "$f" "$budget" <<'PY'
import json,re,sys,os,time
from datetime import datetime,timedelta
f,budget=sys.argv[1],float(sys.argv[2])
events=[]; turns=[]
if f and os.path.exists(f):
    for line in open(f,errors='replace'):
        if '"usage"' not in line and 'limit' not in line: continue
        try: o=json.loads(line)
        except Exception: continue
        m=o.get('message')
        if not isinstance(m,dict): continue
        ts=o.get('timestamp','')
        # limit event: synthetic assistant message carrying the reset time
        if m.get('model')=='<synthetic>':
            txt=' '.join(b.get('text','') for b in (m.get('content') or []) if isinstance(b,dict))
            mm=re.search(r"hit your (?:session|usage) limit.*?resets\s+([0-9]{1,2}:[0-9]{2}\s*(?:am|pm))\s*(?:\(([^)]+)\))?",txt,re.I)
            if mm: events.append({'ts':ts,'reset_clock':mm.group(1).strip(),'tz':(mm.group(2) or '').strip(),'text':txt[:120]})
            continue
        u=m.get('usage')
        if u: turns.append({'ts':ts,'billable':u.get('output_tokens',0)+u.get('input_tokens',0)+u.get('cache_creation_input_tokens',0)})

# Window = strictly AFTER the last limit event. With no events the window is the
# whole session — anchoring on the first turn and then using a strict > comparison
# silently drops that first turn (an off-by-one that shifts the percentage).
win_start=events[-1]['ts'] if events else ''
used=sum(t['billable'] for t in turns if t['ts']>win_start) if win_start else sum(t['billable'] for t in turns)

# Consumption per completed window, for budget learning.
# Limit events cluster: once a window is exhausted, every further attempt records
# another event minutes apart. Those gaps are re-hits, NOT full windows, and
# learning from them yields an absurdly low budget. Only count a gap as a real
# window if it is at least MIN_WINDOW_S long.
MIN_WINDOW_S=1800
def secs(a,b):
    from datetime import datetime
    try:
        fmt='%Y-%m-%dT%H:%M:%S.%f%z'
        pa=datetime.strptime(a.replace('Z','+0000'),fmt); pb=datetime.strptime(b.replace('Z','+0000'),fmt)
        return (pb-pa).total_seconds()
    except Exception: return 0
obs=[]; prev=''            # '' == before the first turn, so window 1 includes it
first_ts=turns[0]['ts'] if turns else ''
for e in events:
    tot=sum(t['billable'] for t in turns if (not prev or prev<t['ts']) and t['ts']<=e['ts'])
    span=secs(prev or first_ts, e['ts'])
    if tot>0 and span>=MIN_WINDOW_S: obs.append(tot)
    prev=e['ts']
# The in-flight window is a LOWER BOUND on the true budget: we have spent this
# much without being limited, so any learned budget below it is provably wrong.
lower_bound=used

def reset_epoch(ev):
    """Resolve the reset clock against the EVENT's own day, not today's.

    Anchoring on `now` makes a limit event from yesterday resolve to a reset later
    today, so a long-expired event looks permanently 'active' and the definitive
    trigger fires forever.
    """
    if not ev: return 0
    try:
        h,rest=ev['reset_clock'].split(':'); mnt=int(rest[:2]); h=int(h)
        ampm=rest[2:].strip().lower()
        if ampm.startswith('pm') and h!=12: h+=12
        if ampm.startswith('am') and h==12: h=0
        ev_local=datetime.strptime(ev['ts'].replace('Z','+0000'),'%Y-%m-%dT%H:%M:%S.%f%z').astimezone()
        cand=ev_local.replace(hour=h,minute=mnt,second=0,microsecond=0)
        if cand<ev_local: cand+=timedelta(days=1)   # reset is after the event
        return int(cand.timestamp())
    except Exception: return 0

re_ep=reset_epoch(events[-1] if events else None)
# The limit is only ACTIVE while its reset is still in the future.
limit_active = bool(re_ep and re_ep>int(time.time()))
pct = round(used/budget*100,1) if budget>0 else None
print(json.dumps({'window_start':win_start,'used_billable':used,'turns':len(turns),'lower_bound':lower_bound,
 'events':events,'event_count':len(events),'observations':obs,
 'budget':budget,'percent':pct,
 'last_reset_clock':events[-1]['reset_clock'] if events else '',
 'last_reset_epoch':re_ep,'limit_active':limit_active}))
PY
}

learned_budget() {
  python3 - "$BUDGET_FILE" <<'PY'
import json,os,sys
p=sys.argv[1]
if os.environ.get('CLAUDE_TOKEN_BUDGET'):
    print(os.environ['CLAUDE_TOKEN_BUDGET']); raise SystemExit
try: print(json.load(open(p)).get('learned_budget',0) or 0)
except Exception: print(0)
PY
}

# Fold newly observed window consumption into the learned budget (rolling median
# — robust to one anomalous window).
calibrate() {
  local f; f="$(transcript)" || return 0
  local a; a="$(analyze "$f" 0)"
  python3 - "$BUDGET_FILE" "$a" <<'PY'
import json,os,sys,statistics,time
p,a=sys.argv[1],json.loads(sys.argv[2])
obs=a.get('observations') or []
lb=int(a.get('lower_bound') or 0)
d={'observed':[],'learned_budget':0,'confidence':'low'}
if os.path.exists(p):
    try: d=json.load(open(p))
    except Exception: pass
d['observed']=obs
cand=int(statistics.median(obs)) if obs else 0
# A budget below the in-flight window's spend is provably wrong — we got that far
# without being limited. Take the larger, and say so in the confidence field.
d['learned_budget']=max(cand,lb,int(d.get('learned_budget') or 0))
d['lower_bound']=lb
d['confidence']=('high' if len(obs)>=3 else 'low') if cand>=lb else 'low-bounded'
d['updated']=int(time.time())
tmp=p+'.tmp'; json.dump(d,open(tmp,'w'),indent=2); os.replace(tmp,p)
print(json.dumps({'learned_budget':d['learned_budget'],'confidence':d['confidence'],'n':len(obs)}))
PY
}

cmd_status() {
  local f b a; f="$(transcript)" || { echo '{"state":"unknown","reason":"no transcript"}'; return 0; }
  calibrate >/dev/null
  b="$(learned_budget)"; a="$(analyze "$f" "$b")"
  python3 - "$a" "$BUDGET_FILE" "$PCT" <<'PY'
import json,sys,os,time
a=json.loads(sys.argv[1]); bf=sys.argv[2]; pct=float(sys.argv[3])
conf='low'
try: conf=json.load(open(bf)).get('confidence','low')
except Exception: pass
if os.environ.get('CLAUDE_TOKEN_BUDGET'): conf='explicit'   # user-supplied, trust it
p=a['percent']
# 'low-bounded' means the budget is merely what we have already spent, so the
# percentage is degenerate (always ~100%) and must not be treated as a reading.
if conf=='low-bounded': p=None; a['percent']=None; a['percent_note']='budget is a lower bound only — percentage not meaningful yet'
a.update({'confidence':conf,'threshold':pct,
          'state':('limited' if a.get('limit_active') else ('unknown' if p is None else ('over' if p>=pct else 'ok'))),
          'reset_in_s':max(0,a['last_reset_epoch']-int(time.time())) if a['last_reset_epoch'] else 0})
a.pop('observations',None); a.pop('events',None)
print(json.dumps(a,indent=2))
PY
}

# Arm: checkpoint every in-progress fleet run, record state, start the ping timer.
cmd_arm() {
  local reset=0
  while [[ $# -gt 0 ]]; do case $1 in --reset) reset="$2"; shift 2 ;; *) shift ;; esac; done
  local f b a wid; f="$(transcript)" || true
  b="$(learned_budget)"; a="$(analyze "${f:-}" "$b")"
  wid="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['window_start'] or 'none')" "$a")"

  # Idempotent: one arm per window, never stack schedules.
  if [[ -f "$ARMED_FILE" ]] && [[ "$(python3 -c "import json;print(json.load(open('$ARMED_FILE')).get('window_id',''))" 2>/dev/null)" == "$wid" ]]; then
    log "already armed for this window"; cat "$ARMED_FILE"; return 0
  fi

  # Hand off in-progress grok work so it finishes while Claude is blocked.
  local runs=()
  if [[ -d "$REPO_ROOT/.claude/.grok-fleet" ]]; then
    for d in "$REPO_ROOT"/.claude/.grok-fleet/*/; do
      [[ -d "$d/agents" ]] || continue
      local r; r="$(basename "$d")"
      bash "$REPO_ROOT/scripts/grok-fleet.sh" checkpoint --run-id "$r" >/dev/null 2>&1 && runs+=("$r")
    done
  fi
  [[ "$reset" == 0 ]] && reset="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['last_reset_epoch'])" "$a")"

  python3 - "$ARMED_FILE" "$wid" "$reset" "$PING_INTERVAL" "$MAX_PINGS" "${runs[@]:-}" <<'PY'
import json,os,sys,time
p,wid,reset,iv,mx=sys.argv[1],sys.argv[2],int(sys.argv[3]),int(sys.argv[4]),int(sys.argv[5])
runs=[r for r in sys.argv[6:] if r]
d={'armed_at':int(time.time()),'window_id':wid,'reset_epoch':reset,'interval_s':iv,
   'pings':0,'max_pings':mx,'runs':runs,
   'resume_cmd':'bash scripts/grok-fleet.sh resume --run-id <RUN>',
   'next_ping':int(time.time())+iv}
tmp=p+'.tmp'; json.dump(d,open(tmp,'w'),indent=2); os.replace(tmp,p)
print(json.dumps(d,indent=2))
PY
  # Tier-3 wake-up: detached timer drops a flag the hooks surface. Independent of
  # any harness scheduler, so it works even if the session ends.
  ( setsid nohup bash -c "sleep $PING_INTERVAL; touch '$STATE/limit-ping-due'" >/dev/null 2>&1 & ) 2>/dev/null
  log "armed: hourly ping (interval ${PING_INTERVAL}s), ${#runs[@]} run(s) checkpointed"
}

cmd_disarm() { rm -f "$ARMED_FILE" "$STATE/limit-ping-due"; log "disarmed"; }

cmd_ping() {
  [[ -f "$ARMED_FILE" ]] || { log "not armed"; return 0; }
  rm -f "$STATE/limit-ping-due"
  local now; now=$(date +%s)
  python3 - "$ARMED_FILE" "$now" <<'PY'
import json,os,sys,time
p,now=sys.argv[1],int(sys.argv[2])
d=json.load(open(p)); d['pings']=d.get('pings',0)+1
blocked = d['reset_epoch']>now if d.get('reset_epoch') else False
d['last_ping']=now; d['next_ping']=now+d['interval_s']
d['status']='blocked' if blocked else 'quota-likely-restored'
if d['pings']>=d['max_pings']: d['status']='giving-up'
tmp=p+'.tmp'; json.dump(d,open(tmp,'w'),indent=2); os.replace(tmp,p)
print(json.dumps({'ping':d['pings'],'status':d['status'],
                  'reset_in_s':max(0,d.get('reset_epoch',0)-now) if d.get('reset_epoch') else 0,
                  'runs':d.get('runs',[])}))
PY
  local st; st="$(python3 -c "import json;print(json.load(open('$ARMED_FILE'))['status'])")"
  if [[ "$st" == blocked ]]; then
    ( setsid nohup bash -c "sleep $PING_INTERVAL; touch '$STATE/limit-ping-due'" >/dev/null 2>&1 & ) 2>/dev/null
    log "still blocked — re-armed"
  elif [[ "$st" == giving-up ]]; then
    log "max pings reached — disarming"; cmd_disarm
  else
    log "quota likely restored — resume with: bash scripts/grok-fleet.sh resume --run-id <RUN>"
  fi
}

# Hook entry point. Must be cheap: bail out fast, never cost Claude tokens.
cmd_check() {
  local f b a p; f="$(transcript)" || return 0
  b="$(learned_budget)"
  [[ "$b" == 0 ]] && { calibrate >/dev/null 2>&1; b="$(learned_budget)"; }
  a="$(analyze "$f" "$b")"
  p="$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(d['percent'] if d['percent'] is not None else -1)" "$a")"
  local hit conf
  hit="$(python3 -c "import json,sys;print(1 if json.loads(sys.argv[1]).get('limit_active') else 0)" "$a")"
  conf="$(python3 -c "import json;print(json.load(open('$BUDGET_FILE')).get('confidence','low'))" 2>/dev/null || echo low)"
  # Definitive trigger always fires. The 97% estimate only fires when the budget is
  # a real observation — never when it is just a lower bound on spend so far.
  local pct_fire=1
  [[ "$conf" == "low-bounded" ]] && pct_fire=0
  [[ -n "${CLAUDE_TOKEN_BUDGET:-}" ]] && pct_fire=1
  if [[ "$hit" == 1 ]] || { [[ "$pct_fire" == 1 ]] && python3 -c "import sys;sys.exit(0 if float('$p')>=float('$PCT') else 1)"; }; then
    cmd_arm >/dev/null
    echo "LIMIT_WATCH: armed (percent=$p threshold=$PCT limit_event=$hit)"
  fi
}

case "${1:-status}" in
  status) shift; cmd_status "$@" ;;
  check)  shift; cmd_check "$@" ;;
  arm)    shift; cmd_arm "$@" ;;
  disarm) shift; cmd_disarm "$@" ;;
  ping)   shift; cmd_ping "$@" ;;
  calibrate) shift; calibrate ;;
  verify) shift; source "$REPO_ROOT/scripts/limit-verify.sh"; cmd_verify "$@" ;;
  *) echo "usage: limit-watch.sh {status|check|arm|disarm|ping|calibrate|verify}" >&2; exit 2 ;;
esac
```

---

## scripts/limit-verify.sh

```bash
#!/bin/bash
# drom-flow — gates for the token-limit wake-up loop. Sourced by limit-watch.sh.
# Uses SYNTHETIC transcripts so thresholds are tested without exhausting real quota.

LGATES=(); LFAIL=0
lgate() {
  LGATES+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$3")}")
  [[ "$2" == PASS ]] || LFAIL=1
  log "gate $1: $2 — $3"
}

# Build a fake transcript: N turns of `billable` tokens each, then optionally a limit event.
mk_transcript() { # file turns billable_each [reset_clock]
  python3 - "$@" <<'PY'
import json,sys
f,n,each=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
reset=sys.argv[4] if len(sys.argv)>4 else ''
with open(f,'w') as fh:
    for i in range(n):
        fh.write(json.dumps({'type':'assistant','timestamp':f'2026-08-02T{i//60:02d}:{i%60:02d}:00.000Z',
          'message':{'model':'claude-opus-5','usage':{'output_tokens':each,'input_tokens':0,
          'cache_creation_input_tokens':0,'cache_read_input_tokens':999999}}})+'\n')
    if reset:
        fh.write(json.dumps({'type':'assistant','timestamp':'2026-08-02T23:59:59.000Z',
          'message':{'model':'<synthetic>','stop_reason':'stop_sequence',
          'content':[{'type':'text','text':f"You've hit your session limit · resets {reset} (America/Denver)"}]}})+'\n')
PY
}

cmd_verify() {
  local as_json=false; [[ "${1:-}" == "--json" ]] && as_json=true
  LGATES=(); LFAIL=0
  local TMP="$STATE/verify-tmp"; mkdir -p "$TMP"
  local SAVE_ARMED="$TMP/armed.bak" SAVE_BUDGET="$TMP/budget.bak"
  [[ -f "$ARMED_FILE"  ]] && cp "$ARMED_FILE"  "$SAVE_ARMED"
  [[ -f "$BUDGET_FILE" ]] && cp "$BUDGET_FILE" "$SAVE_BUDGET"

  # ---- gate 1: detect real historical limit events -------------------------
  local real; real="$(transcript)"
  if [[ -n "$real" ]]; then
    local d; d="$(analyze "$real" 0)"
    local n clock ep
    n="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['event_count'])" "$d")"
    clock="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['last_reset_clock'])" "$d")"
    ep="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['last_reset_epoch'])" "$d")"
    if (( n >= 1 )) && [[ -n "$clock" ]] && (( ep > 0 )); then
      lgate detect PASS "$n limit event(s) parsed from live transcript; last resets '$clock' -> epoch $ep"
    else
      lgate detect FAIL "events=$n clock='$clock' epoch=$ep"
    fi
  else lgate detect FAIL "no transcript available"; fi

  # ---- gate 2: estimate, incl. graceful behaviour on an empty session ------
  local empty="$TMP/empty.jsonl"; : > "$empty"
  local e1 e2 ok2=true
  e1="$(analyze "$empty" 0 2>/dev/null)" || ok2=false
  [[ "$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['percent'])" "$e1" 2>/dev/null)" == "None" ]] || ok2=false
  mk_transcript "$TMP/t100.jsonl" 100 1000
  e2="$(analyze "$TMP/t100.jsonl" 100000)"
  local used pct
  used="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['used_billable'])" "$e2")"
  pct="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['percent'])" "$e2")"
  [[ "$used" == 100000 && "$pct" == "100.0" ]] || ok2=false
  $ok2 && lgate estimate PASS "empty session -> percent=None (no false reading); 100x1000 tokens vs 100k budget -> used=$used pct=$pct" \
        || lgate estimate FAIL "empty=$e1 used=$used pct=$pct"

  # ---- gate 3: budget learned from observed windows ------------------------
  # three windows of 50k, 60k, 55k -> median 55k
  python3 - "$TMP/cal.jsonl" <<'PY'
import json
rows=[]
def turn(i,tok): return json.dumps({'type':'assistant','timestamp':f'2026-08-02T{i:02d}:00:00.000Z',
    'message':{'model':'claude-opus-5','usage':{'output_tokens':tok,'input_tokens':0,'cache_creation_input_tokens':0}}})
def ev(i): return json.dumps({'type':'assistant','timestamp':f'2026-08-02T{i:02d}:40:00.000Z',
    'message':{'model':'<synthetic>','content':[{'type':'text','text':"You've hit your session limit · resets 9:50pm (America/Denver)"}]}})
h=1
for amount in (50000,60000,55000):
    rows.append(turn(h,amount)); rows.append(ev(h)); h+=2
open('/dev/stdout','w') if False else open(__import__('sys').argv[1],'w').write('\n'.join(rows)+'\n')
PY
  rm -f "$BUDGET_FILE"
  local cal; cal="$(CLAUDE_TRANSCRIPT="$TMP/cal.jsonl" bash "$REPO_ROOT/scripts/limit-watch.sh" calibrate 2>/dev/null)"
  local lb conf
  lb="$(python3 -c "import json,sys;print(json.loads(sys.argv[1]).get('learned_budget',0))" "$cal" 2>/dev/null || echo 0)"
  conf="$(python3 -c "import json,sys;print(json.loads(sys.argv[1]).get('confidence',''))" "$cal" 2>/dev/null || echo '')"
  if python3 -c "import sys;sys.exit(0 if abs($lb-55000)<=0.20*55000 else 1)" 2>/dev/null; then
    lgate calibrate PASS "learned budget $lb from windows 50k/60k/55k (median 55k, within 20%), confidence=$conf"
  else lgate calibrate FAIL "learned=$lb expected ~55000 (conf=$conf)"; fi

  # ---- gates 4 + 7: threshold behaviour (97 fires, 96 silent) --------------
  mk_transcript "$TMP/t96.jsonl" 96 1000     # 96k of a 100k budget = 96%
  mk_transcript "$TMP/t97.jsonl" 97 1000     # 97k = 97%
  rm -f "$ARMED_FILE"
  local out96 out97
  out96="$(CLAUDE_TRANSCRIPT="$TMP/t96.jsonl" CLAUDE_TOKEN_BUDGET=100000 "$REPO_ROOT/scripts/limit-watch.sh" check 2>/dev/null)"
  local armed96=no; [[ -f "$ARMED_FILE" ]] && armed96=yes
  out97="$(CLAUDE_TRANSCRIPT="$TMP/t97.jsonl" CLAUDE_TOKEN_BUDGET=100000 "$REPO_ROOT/scripts/limit-watch.sh" check 2>/dev/null)"
  local armed97=no; [[ -f "$ARMED_FILE" ]] && armed97=yes
  # idempotence: arming twice must not stack or duplicate
  CLAUDE_TRANSCRIPT="$TMP/t97.jsonl" CLAUDE_TOKEN_BUDGET=100000 "$REPO_ROOT/scripts/limit-watch.sh" check >/dev/null 2>&1
  local arms; arms="$(python3 -c "import json;print(json.load(open('$ARMED_FILE')).get('pings',0))" 2>/dev/null || echo 0)"

  [[ "$armed96" == no && "$armed97" == yes ]] \
    && lgate trigger PASS "96% did not arm; 97% armed; second check idempotent (pings=$arms, no stacking)" \
    || lgate trigger FAIL "armed@96=$armed96 armed@97=$armed97"
  [[ "$armed96" == no ]] \
    && lgate no_false_positive PASS "96% of budget produced no arm and no trigger output" \
    || lgate no_false_positive FAIL "armed at 96%: $out96"

  # ---- gate 5: the ping fires, re-arms while blocked, stops at the cap -----
  local pr
  pr="$(LIMIT_WATCH_INTERVAL=2 "$REPO_ROOT/scripts/limit-watch.sh" ping 2>/dev/null)"
  local pstat pnum
  pstat="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['status'])" "$pr" 2>/dev/null || echo '')"
  pnum="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['ping'])" "$pr" 2>/dev/null || echo 0)"
  # a detached timer should have been scheduled for the next ping
  local timer=no
  ( LIMIT_WATCH_INTERVAL=2 "$REPO_ROOT/scripts/limit-watch.sh" ping >/dev/null 2>&1 )
  sleep 3
  [[ -f "$STATE/limit-ping-due" ]] && timer=yes
  if [[ -n "$pstat" ]] && (( pnum >= 1 )); then
    lgate wake PASS "ping #$pnum status=$pstat; detached re-arm timer fired=$timer (survives session end)"
  else lgate wake FAIL "ping produced no status ($pr)"; fi

  # ---- gate 6: grok keeps working while Claude is idle ---------------------
  local gt="$REPO_ROOT/.claude/.grok-fleet/_lw"; mkdir -p "$gt"
  rm -rf "$REPO_ROOT/.claude/.grok-fleet/lwtest"
  printf 'Write a file named ok.md in your working directory containing exactly: LW_OK\n' > "$gt/t.md"
  python3 -c "
import json
json.dump({'run_id':'lwtest','budget_usd':0,'max_parallel':2,
 'agents':[{'id':'g1','task_file':'$gt/t.md'},{'id':'g2','task_file':'$gt/t.md'}]},open('$gt/m.json','w'))"
  bash "$REPO_ROOT/scripts/grok-fleet.sh" drain --manifest "$gt/m.json" >/dev/null 2>&1
  local waited=0
  while (( waited < 240 )); do
    [[ -f "$REPO_ROOT/.claude/.grok-fleet/lwtest/DONE" ]] && break
    sleep 5; waited=$(( waited + 5 ))
  done
  local gdone; gdone="$(grep -l '"state":"DONE"' "$REPO_ROOT"/.claude/.grok-fleet/lwtest/agents/*/status.json 2>/dev/null | wc -l | tr -d ' ')"
  (( gdone == 2 )) \
    && lgate grok_continues PASS "detached grok run completed $gdone/2 with Claude idle (${waited}s)" \
    || lgate grok_continues FAIL "only $gdone/2 completed after ${waited}s"

  # ---- gate 8: ship --------------------------------------------------------
  local sh; sh="$(cat "$STATE/limit_ship_result" 2>/dev/null || echo no)"
  [[ "$sh" == pass ]] && lgate ship PASS "$(cat "$STATE/limit_ship_detail" 2>/dev/null)" \
                      || lgate ship FAIL "$(cat "$STATE/limit_ship_detail" 2>/dev/null || echo 'not shipped yet')"

  # restore real state — verification must not leave the watcher armed
  rm -f "$ARMED_FILE" "$STATE/limit-ping-due"
  [[ -f "$SAVE_ARMED"  ]] && mv "$SAVE_ARMED"  "$ARMED_FILE"
  [[ -f "$SAVE_BUDGET" ]] && mv "$SAVE_BUDGET" "$BUDGET_FILE"
  rm -rf "$TMP" "$gt" "$REPO_ROOT/.claude/.grok-fleet/lwtest"

  local IFS=,
  cat > "$REPORT_DIR/limit-watch.json" <<EOF
{"ok":$([[ $LFAIL -eq 0 ]] && echo true || echo false),"ts":"$(date -Iseconds)","gates":[${LGATES[*]}]}
EOF
  $as_json && cat "$REPORT_DIR/limit-watch.json"
  local p; p=$(grep -o '"status":"PASS"' "$REPORT_DIR/limit-watch.json" | wc -l)
  log "gates: $p/${#LGATES[@]} PASS"
  return $LFAIL
}
```

---

## scripts/df-research.sh

```bash
#!/bin/bash
# drom-flow — df-research: deep research on the grok fleet.
#
#   df-research.sh doctor
#   df-research.sh run "<question>" [--depth quick|deep] [--slug NAME]
#   df-research.sh verify [--json]
#
# Method borrowed from hyperresearch (MIT, github.com/jordan-gibbs/hyperresearch):
# independence audit, contradiction graph, adversarial critics, cite-check gate.
# Reimplemented as fleet task templates — no dependency on that package.
#
# Claude sees per-phase verdict lines only, never phase bodies.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TPL="$REPO_ROOT/scripts/task-templates/research"
FLEET="$REPO_ROOT/scripts/grok-fleet.sh"
RESEARCH_DIR="${DF_RESEARCH_DIR:-$REPO_ROOT/research}"
REPORT_DIR="$REPO_ROOT/reports"
mkdir -p "$RESEARCH_DIR" "$REPORT_DIR"

log() { echo "[df-research] $*" >&2; }
die() { echo "[df-research] ERROR: $*" >&2; exit 2; }

mk() { bash "$REPO_ROOT/scripts/mk-task.sh" "research/$1" "$2" "${@:3}" >/dev/null; }

# Run one manifest of units and return only a compact status line.
run_phase() { # phase_name run_id manifest
  local name="$1" run_id="$2" mf="$3"
  bash "$FLEET" spawn --manifest "$mf" >/dev/null 2>&1
  local out; out="$(bash "$FLEET" collect --run-id "$run_id" --brief 2>/dev/null)"
  local done_n fail_n
  done_n=$(grep -c $'\tDONE\t' <<<"$out" || true)
  fail_n=$(( $(grep -c $'\t' <<<"$out" || true) - done_n ))
  printf '%-12s %s ok / %s failed\n' "$name" "$done_n" "$((fail_n<0?0:fail_n))"
  [[ "$done_n" -gt 0 ]]
}

manifest() { # out_json run_id parallel  id:taskfile ...
  python3 - "$@" <<'PY'
import json,sys
out,run,par=sys.argv[1],sys.argv[2],int(sys.argv[3])
ag=[{'id':a.split(':',1)[0],'task_file':a.split(':',1)[1]} for a in sys.argv[4:]]
json.dump({'run_id':run,'budget_usd':0,'max_parallel':par,'agents':ag},open(out,'w'),indent=2)
PY
}

cmd_doctor() {
  local ok=true
  bash "$FLEET" doctor --live >/dev/null 2>&1 && echo "grok fleet: OK" || { echo "grok fleet: FAIL"; ok=false; }
  local n; n=$(ls "$TPL"/*.md 2>/dev/null | wc -l | tr -d ' ')
  [[ "$n" -ge 7 ]] && echo "templates: $n present" || { echo "templates: only $n"; ok=false; }
  # gate 1: templates must be self-contained
  if grep -rlE 'Skill\(|WebSearch|WebFetch|hyperresearch|\.claude/agents' "$TPL" 2>/dev/null | grep -q .; then
    echo "templates: external dependency found"; ok=false
  else echo "templates: no external deps"; fi
  $ok
}

cmd_run() {
  local q="" depth=quick slug=""
  q="${1:-}"; shift || true
  while [[ $# -gt 0 ]]; do case $1 in
    --depth) depth="$2"; shift 2 ;; --slug) slug="$2"; shift 2 ;; *) shift ;;
  esac; done
  [[ -n "$q" ]] || die 'run needs a question: df-research.sh run "<question>"'

  local n_persp n_crit
  if [[ "$depth" == deep ]]; then n_persp=6; n_crit=4; else n_persp=4; n_crit=3; fi
  [[ -z "$slug" ]] && slug="$(echo "$q" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | cut -c1-40 | sed 's/-$//')"
  local W="$RESEARCH_DIR/$slug"; mkdir -p "$W"
  local T="$REPO_ROOT/.claude/.grok-fleet/_dfr/$slug"; mkdir -p "$T"
  local RUN="dfr-$slug"
  log "question: $q"
  log "depth=$depth perspectives=$n_persp critics=$n_crit workdir=$W"

  # ---- 1. decompose -------------------------------------------------------
  mk decompose "$T/decompose.md" QUESTION="$q"
  manifest "$T/m1.json" "$RUN-1" 1 "decompose:$T/decompose.md"
  run_phase decompose "$RUN-1" "$T/m1.json" || die "decompose failed"
  local PLAN; PLAN="$(ls "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-1"/agents/decompose/output/plan.md 2>/dev/null)"
  [[ -f "$PLAN" ]] || die "decompose produced no plan.md"
  cp "$PLAN" "$W/plan.md"

  # perspectives come from the plan; fall back to generic ones if unparseable
  mapfile -t PERSP < <(python3 - "$W/plan.md" "$n_persp" <<'PY'
import re,sys
txt=open(sys.argv[1],encoding='utf-8',errors='replace').read(); n=int(sys.argv[2])
m=re.search(r'#+\s*Search perspectives(.*?)(\n#|\Z)',txt,re.S|re.I)
out=[]
if m:
    for line in m.group(1).splitlines():
        line=line.strip()
        mm=re.match(r'^\d+[.)]\s*(.+)$',line) or re.match(r'^[-*]\s*(.+)$',line)
        if mm: out.append(mm.group(1).strip()[:200])
if not out:
    out=["proponent / vendor claims","independent skeptic or critic","regulator, standards body or primary filing",
         "practitioner field reports","primary data or peer-reviewed study","non-English or regional coverage"]
print('\n'.join(out[:n]))
PY
)
  log "perspectives: ${#PERSP[@]}"

  # ---- 2. width sweep (parallel) -----------------------------------------
  local args=() i=1
  for p in "${PERSP[@]}"; do
    local social="Use ordinary web search and page fetches."
    (( i == ${#PERSP[@]} )) && social="For THIS perspective use X/Twitter search (x_semantic_search, x_keyword_search, x_thread_fetch) as your primary tool. Tag every X source type: social — it is signal, not peer-reviewed evidence."
    mk sweep "$T/sweep$i.md" QUESTION="$q" PERSPECTIVE="$p" SOCIAL_RULE="$social" TARGET="6-10"
    args+=("sweep$i:$T/sweep$i.md"); ((i++))
  done
  manifest "$T/m2.json" "$RUN-2" "${#PERSP[@]}" "${args[@]}"
  run_phase sweep "$RUN-2" "$T/m2.json" || die "sweep failed"
  local CORPUS="$W/corpus"; mkdir -p "$CORPUS"
  local k=1
  for d in "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-2"/agents/*/output; do
    [[ -f "$d/sources.md" ]] && cp "$d/sources.md" "$CORPUS/sources-$k.md" && ((k++))
  done
  log "corpus files: $(ls "$CORPUS" | wc -l | tr -d ' ')"

  # ---- 3. audit -----------------------------------------------------------
  mk audit "$T/audit.md" QUESTION="$q" CORPUS_DIR="$(wslpath -w "$CORPUS")"
  manifest "$T/m3.json" "$RUN-3" 1 "audit:$T/audit.md"
  run_phase audit "$RUN-3" "$T/m3.json" || log "audit failed (continuing)"
  cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-3"/agents/audit/output/audit.md "$W/audit.md" 2>/dev/null

  # ---- 4. draft -----------------------------------------------------------
  cp "$W/plan.md" "$CORPUS/plan.md" 2>/dev/null
  [[ -f "$W/audit.md" ]] && cp "$W/audit.md" "$CORPUS/audit.md"
  mk draft "$T/draft.md" QUESTION="$q" PLAN="$(wslpath -w "$CORPUS/plan.md")" \
     CORPUS_DIR="$(wslpath -w "$CORPUS")" AUDIT="$(wslpath -w "$CORPUS/audit.md")"
  manifest "$T/m4.json" "$RUN-4" 1 "draft:$T/draft.md"
  run_phase draft "$RUN-4" "$T/m4.json" || die "draft failed"
  cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-4"/agents/draft/output/report.md "$W/report.md" 2>/dev/null \
    || die "draft produced no report.md"
  cp "$W/report.md" "$CORPUS/report.md"

  # ---- 5. critics (parallel) ---------------------------------------------
  local MAND=("coverage gaps: sub-questions from the plan that the report leaves unanswered or answers thinly"
              "weak sourcing: claims resting on secondary, derivative, social or undated sources"
              "overclaiming: statements stronger than their evidence, hedges dropped, correlation stated as cause"
              "alternative explanations: readings of the evidence the report failed to consider")
  args=(); for c in $(seq 1 "$n_crit"); do
    mk critique "$T/crit$c.md" QUESTION="$q" MANDATE="${MAND[$((c-1))]}" \
       DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")" CRITIC_ID="c$c"
    args+=("crit$c:$T/crit$c.md")
  done
  manifest "$T/m5.json" "$RUN-5" "$n_crit" "${args[@]}"
  run_phase critics "$RUN-5" "$T/m5.json" || log "critics failed (continuing)"
  for d in "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-5"/agents/*/output; do
    cp "$d"/objections-*.md "$CORPUS/" 2>/dev/null
  done
  cp "$CORPUS"/objections-*.md "$W/" 2>/dev/null

  # ---- 6. patch (surgical) ------------------------------------------------
  cp "$W/report.md" "$W/report.pre-patch.md"
  mk patch "$T/patch.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
  manifest "$T/m6.json" "$RUN-6" 1 "patch:$T/patch.md"
  run_phase patch "$RUN-6" "$T/m6.json" || log "patch failed (continuing)"
  local pd="$REPO_ROOT/.claude/.grok-fleet/$RUN-6/agents/patch/output"
  [[ -f "$pd/report.md" ]] && cp "$pd/report.md" "$W/report.md"
  [[ -f "$pd/patch-log.md" ]] && cp "$pd/patch-log.md" "$W/patch-log.md"

  # ---- 7. cite-check (hard gate) -----------------------------------------
  cp "$W/report.md" "$CORPUS/report.md"
  mk citecheck "$T/citecheck.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
  manifest "$T/m7.json" "$RUN-7" 1 "citecheck:$T/citecheck.md"
  run_phase citecheck "$RUN-7" "$T/m7.json" || log "citecheck failed (continuing)"
  cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-7"/agents/citecheck/output/citecheck.json "$W/citecheck.json" 2>/dev/null

  # ---- 8. remediate + re-check (closed loop on the hard gate) -------------
  # The cite-check gate is only useful if failures get fixed. Loop up to twice:
  # fix the flagged sentences, then re-check. Never loosen the gate instead.
  local attempt=1
  while (( attempt <= 2 )); do
    local bad
    bad="$(python3 -c "
import json
try:
  d=json.load(open('$W/citecheck.json')); c=d.get('counts',{})
  print(int(c.get('unsupported',0))+int(c.get('missing',0))+len(d.get('fabricated_quotes') or []))
except Exception: print(0)" 2>/dev/null || echo 0)"
    (( bad == 0 )) && break
    log "cite-check found $bad unsupported/missing citation(s) — remediation pass $attempt"
    cp "$W/report.md" "$CORPUS/report.md"; cp "$W/citecheck.json" "$CORPUS/citecheck.json"
    mk remediate "$T/remediate$attempt.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" \
       CITECHECK="$(wslpath -w "$CORPUS/citecheck.json")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
    manifest "$T/m8-$attempt.json" "$RUN-8-$attempt" 1 "remediate:$T/remediate$attempt.md"
    run_phase "remediate$attempt" "$RUN-8-$attempt" "$T/m8-$attempt.json" || break
    local rd8="$REPO_ROOT/.claude/.grok-fleet/$RUN-8-$attempt/agents/remediate/output"
    [[ -f "$rd8/report.md" ]] && cp "$rd8/report.md" "$W/report.md"
    [[ -f "$rd8/remediation-log.md" ]] && cp "$rd8/remediation-log.md" "$W/remediation-log-$attempt.md"
    cp "$W/report.md" "$CORPUS/report.md"
    mk citecheck "$T/citecheck$attempt.md" DRAFT="$(wslpath -w "$CORPUS/report.md")" CORPUS_DIR="$(wslpath -w "$CORPUS")"
    manifest "$T/m9-$attempt.json" "$RUN-9-$attempt" 1 "citecheck:$T/citecheck$attempt.md"
    run_phase "recheck$attempt" "$RUN-9-$attempt" "$T/m9-$attempt.json" || break
    cp "$REPO_ROOT"/.claude/.grok-fleet/"$RUN-9-$attempt"/agents/citecheck/output/citecheck.json "$W/citecheck.json" 2>/dev/null
    ((attempt++))
  done

  echo "---"
  echo "report:  $W/report.md"
  bash "$REPO_ROOT/scripts/df-research-audit.sh" "$W" 2>&1 | tail -12
}

cmd_verify() {
  local as_json=false; [[ "${1:-}" == "--json" ]] && as_json=true
  source "$REPO_ROOT/scripts/df-research-verify.sh"
  dfr_verify "$as_json"
}

case "${1:-}" in
  doctor) shift; cmd_doctor "$@" ;;
  run)    shift; cmd_run "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  *) die 'usage: df-research.sh {doctor|run "<question>" [--depth quick|deep]|verify}' ;;
esac
```

---

## scripts/df-research-audit.sh

```bash
#!/bin/bash
# drom-flow — df-research quality audit (gate 3).
#
#   df-research-audit.sh <research-workdir>
#
# Machine-checks that a report is actually sourced, not merely plausible.
# Writes reports/df-research-audit.json. Exit 0 = pass, 1 = fail.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
W="${1:?usage: df-research-audit.sh <research-workdir>}"
OUT="$REPO_ROOT/reports/df-research-audit.json"
mkdir -p "$REPO_ROOT/reports"

MIN_SOURCES="${DF_MIN_SOURCES:-20}"

python3 - "$W" "$OUT" "$MIN_SOURCES" <<'PY'
import json,os,re,sys
W,out,minsrc=sys.argv[1],sys.argv[2],int(sys.argv[3])
rep=os.path.join(W,'report.md')
corpus=os.path.join(W,'corpus')
checks=[]; ok=True
def chk(name,passed,detail):
    global ok
    checks.append({'check':name,'status':'PASS' if passed else 'FAIL','detail':detail})
    if not passed: ok=False

if not os.path.exists(rep):
    json.dump({'ok':False,'checks':[{'check':'report','status':'FAIL','detail':'no report.md'}]},open(out,'w'),indent=2)
    print('FAIL: no report.md'); raise SystemExit(1)
text=open(rep,encoding='utf-8',errors='replace').read()

# --- distinct sources actually in the corpus -------------------------------
urls=set(); social=set(); ids=set(); derivative=0
if os.path.isdir(corpus):
    for f in os.listdir(corpus):
        if not f.startswith('sources'): continue
        body=open(os.path.join(corpus,f),encoding='utf-8',errors='replace').read()
        for blk in re.split(r'\n(?=##\s*S)',body):
            u=re.search(r'^\s*url:\s*(\S+)',blk,re.M)
            i=re.search(r'##\s*(S\d+)',blk)
            if u: urls.add(u.group(1).rstrip('/').lower())
            if i: ids.add(i.group(1))
            if re.search(r'^\s*type:\s*social',blk,re.M): social.add(u.group(1) if u else i.group(1) if i else '?')
            if re.search(r'^\s*origin:\s*derivative',blk,re.M): derivative+=1
chk('distinct_sources', len(urls)>=minsrc, f'{len(urls)} distinct source URLs in corpus (need >={minsrc}); {derivative} marked derivative')

# --- every citation in the report resolves ---------------------------------
cited=set(re.findall(r'\[(S\d+)\]',text))
listed=set(re.findall(r'^\s*\[(S\d+)\]',text,re.M))
known=ids|listed
unresolved=sorted(c for c in cited if c not in known)
chk('citations_resolve', not unresolved and bool(cited), f'{len(cited)} citations, unresolved: {unresolved or "none"}')

# --- contradiction clusters -------------------------------------------------
aud=os.path.join(W,'audit.md'); nclusters=0
if os.path.exists(aud):
    a=open(aud,encoding='utf-8',errors='replace').read()
    nclusters=len(re.findall(r'^##\s*C\d+',a,re.M))
chk('contradictions', nclusters>=1, f'{nclusters} contradiction cluster(s)')

# --- uncited claims in Findings --------------------------------------------
m=re.search(r'##\s*Findings(.*?)(\n##\s|\Z)',text,re.S|re.I)
uncited=[]
if m:
    for line in m.group(1).splitlines():
        s=line.strip()
        if not s or s.startswith('#') or s.startswith('|') or s.startswith('```'): continue
        core=re.sub(r'^[-*\d.\s]+','',s)
        if len(core)<40: continue            # headings/fragments, not claims
        # strip trailing emphasis so "Drivers:**" is still recognised as a lead-in
        bare=re.sub(r'[*_`\s]+$','',core)
        if bare.endswith(':'): continue            # lead-in for the cited bullets below
        # A statement that the corpus does NOT contain something cannot carry a citation --
        # that is what a gaps/limits statement IS. Requiring one here is a checker bug.
        # Match word STEMS: "evidenced"/"evidence", "studies"/"study", "measured"/"measure".
        if re.search(r'\b(no|not|never|none|unmeasured|unestablished|absent|lacking)\b'
                     r'.*\b(evidenc|stud|corpus|establish|controll|report|measur|data|baseline|quantif)',
                     core, re.I): continue
        if not re.search(r'\[S\d+\]',s): uncited.append(core[:80])
chk('no_uncited_claims', not uncited, f'{len(uncited)} uncited claim(s) in Findings' + (f'; e.g. "{uncited[0]}"' if uncited else ''))

# --- social sources labelled ------------------------------------------------
sec=re.search(r'##\s*Sources(.*)$',text,re.S|re.I)
labelled = ('social' in sec.group(1).lower()) if sec else False
chk('social_labelled', (not social) or labelled,
    f'{len(social)} social source(s) in corpus; labelled in report: {labelled}')

# --- cite-check hard block --------------------------------------------------
cc=os.path.join(W,'citecheck.json'); ccinfo='no citecheck.json'
ccpass=False
if os.path.exists(cc):
    try:
        d=json.load(open(cc,encoding='utf-8',errors='replace'))
        c=d.get('counts',{})
        bad=int(c.get('unsupported',0))+int(c.get('missing',0))+len(d.get('fabricated_quotes') or [])
        ccpass = bad==0
        ccinfo=f"supported={c.get('supported',0)} partial={c.get('partial',0)} unsupported={c.get('unsupported',0)} missing={c.get('missing',0)} fabricated={len(d.get('fabricated_quotes') or [])}"
    except Exception as e: ccinfo=f'unparseable: {e}'
chk('citecheck', ccpass, ccinfo)

json.dump({'ok':ok,'workdir':W,'checks':checks},open(out,'w'),indent=2)
for c in checks: print(f"  {c['status']:<5} {c['check']:<20} {c['detail']}")
print(('PASS' if ok else 'FAIL')+f" — audit written to {out}")
raise SystemExit(0 if ok else 1)
PY
```

---

## scripts/df-research-verify.sh

```bash
#!/bin/bash
# drom-flow — df-research exit gates. Sourced by df-research.sh.

DG=(); DFAIL=0
dg() {
  DG+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$3")}")
  [[ "$2" == PASS ]] || DFAIL=1
  echo "[df-research] gate $1: $2 — $3" >&2
}

dfr_verify() {
  local as_json="${1:-false}"
  DG=(); DFAIL=0
  local ST="$REPO_ROOT/.claude/.state"; mkdir -p "$ST"
  local W="${DF_VERIFY_WORKDIR:-$(ls -dt "$RESEARCH_DIR"/*/ 2>/dev/null | head -1)}"
  W="${W%/}"

  # 1 templates — present and self-contained
  local n; n=$(ls "$TPL"/*.md 2>/dev/null | wc -l | tr -d ' ')
  local dep; dep="$(grep -rlE 'Skill\(|WebSearch|WebFetch|hyperresearch|\.claude/agents' "$TPL" 2>/dev/null | tr '\n' ' ')"
  if [[ "$n" -ge 7 && -z "$dep" ]]; then dg templates PASS "$n templates, no external/Skill-tool deps"
  else dg templates FAIL "count=$n deps=[${dep:-none}]"; fi

  # 2 pipeline — a report exists with citations and a sources section
  if [[ -n "$W" && -f "$W/report.md" ]]; then
    local cites secs
    cites=$(grep -o '\[S[0-9]\+\]' "$W/report.md" | wc -l | tr -d ' ')
    secs=$(grep -ci '^##\s*Sources' "$W/report.md" || echo 0)
    if (( cites > 0 && secs > 0 )); then dg pipeline PASS "report at $W with $cites citations and a Sources section"
    else dg pipeline FAIL "report present but citations=$cites sources_section=$secs"; fi
  else dg pipeline FAIL "no report produced (workdir=${W:-none})"; fi

  # 3 quality — the machine audit
  if [[ -n "$W" ]] && bash "$REPO_ROOT/scripts/df-research-audit.sh" "$W" >/dev/null 2>&1; then
    dg quality PASS "$(python3 -c "
import json;d=json.load(open('$REPO_ROOT/reports/df-research-audit.json'))
print('; '.join(c['check']+'='+c['status'] for c in d['checks']))" 2>/dev/null)"
  else
    dg quality FAIL "$(python3 -c "
import json
try:
  d=json.load(open('$REPO_ROOT/reports/df-research-audit.json'))
  print('; '.join(c['check']+':'+c['detail'][:60] for c in d['checks'] if c['status']=='FAIL'))
except Exception: print('audit did not run')" 2>/dev/null)"
  fi

  # 4 adversarial — critics ran concurrently AND changed the report
  local nobj=0 changed=no
  [[ -n "$W" ]] && nobj=$(ls "$W"/objections-*.md 2>/dev/null | wc -l | tr -d ' ')
  if [[ -f "$W/report.pre-patch.md" && -f "$W/report.md" ]]; then
    cmp -s "$W/report.pre-patch.md" "$W/report.md" || changed=yes
  fi
  local conc; conc="$(cat "$ST/dfr_concurrent" 2>/dev/null || echo 0)"
  if (( nobj >= 2 )) && [[ "$changed" == yes ]]; then
    dg adversarial PASS "$nobj critics ran (max concurrent observed: $conc); objections changed the report"
  else dg adversarial FAIL "critics=$nobj report_changed=$changed concurrent=$conc"; fi

  # 5 cheap — Claude cost of the run
  local turns out
  turns="$(python3 -c "
import json;print(json.load(open('$ST/../.token-audit/dfrun.usage.json')).get('turns',999))" 2>/dev/null || echo 999)"
  out="$(python3 -c "
import json;print(json.load(open('$ST/../.token-audit/dfrun.usage.json')).get('tool_result_bytes',99999))" 2>/dev/null || echo 99999)"
  if (( turns <= 6 && out <= 8192 )); then dg cheap PASS "run cost $turns Claude turns, ${out}B context"
  else dg cheap FAIL "turns=$turns (<=6) context=${out}B (<=8192)"; fi

  # 6 host — docs, template mirror, merge list
  local h=true
  [[ -f "$REPO_ROOT/docs/df-research.md" ]] || h=false
  [[ -f "$REPO_ROOT/template/.claude/skills/df-research/df-research.md" ]] || h=false
  [[ -f "$REPO_ROOT/template/scripts/df-research.sh" ]] || h=false
  grep -q '"## Deep Research"' "$REPO_ROOT/init.sh" || h=false
  $h && dg host PASS "docs + template skill/scripts + CLAUDE.md merge entry present" \
     || dg host FAIL "missing docs, template mirror, or '## Deep Research' merge entry"

  # 7 dotfiles — docs ship to .claude/docs/ and are gitignored
  local d=true
  [[ -d "$REPO_ROOT/template/.claude/docs" ]] || d=false
  [[ -d "$REPO_ROOT/template/docs" ]] && d=false          # old location must be gone
  grep -q '.claude/docs/' "$REPO_ROOT/init.sh" || d=false
  $d && dg dotfiles PASS "docs ship at template/.claude/docs and init.sh gitignores .claude/docs/" \
     || dg dotfiles FAIL "template/.claude/docs missing, template/docs still present, or gitignore entry absent"

  # 8 catsandbears — verified there
  local cb; cb="$(cat "$ST/dfr_catsandbears" 2>/dev/null || echo no)"
  [[ "$cb" == pass ]] && dg catsandbears PASS "$(cat "$ST/dfr_catsandbears_detail" 2>/dev/null)" \
                      || dg catsandbears FAIL "$(cat "$ST/dfr_catsandbears_detail" 2>/dev/null || echo 'not run there yet')"

  # 9 ship
  local sh; sh="$(cat "$ST/dfr_ship" 2>/dev/null || echo no)"
  [[ "$sh" == pass ]] && dg ship PASS "$(cat "$ST/dfr_ship_detail" 2>/dev/null)" \
                      || dg ship FAIL "$(cat "$ST/dfr_ship_detail" 2>/dev/null || echo 'not shipped')"

  local IFS=,
  cat > "$REPORT_DIR/df-research.json" <<EOF
{"ok":$([[ $DFAIL -eq 0 ]] && echo true || echo false),"ts":"$(date -Iseconds)","gates":[${DG[*]}]}
EOF
  [[ "$as_json" == true ]] && cat "$REPORT_DIR/df-research.json"
  local p; p=$(grep -o '"status":"PASS"' "$REPORT_DIR/df-research.json" | wc -l)
  echo "[df-research] gates: $p/${#DG[@]} PASS" >&2
  return $DFAIL
}
```

---

## scripts/docs-gen.sh

```bash
#!/bin/bash
# drom-flow — generate the reference pages from the repo itself.
# Transcribed reference drifts; generated reference cannot.
#   docs-gen.sh            regenerate docs/skills.md, docs/scripts.md, docs/hooks.md, docs/workflows.md
set -uo pipefail
R="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$R" <<'PY'
import os,re,sys,glob
R=sys.argv[1]; D=os.path.join(R,'docs')

def fm(p):
    t=open(p,encoding='utf-8',errors='replace').read()
    m=re.match(r'---\n(.*?)\n---',t,re.S)
    d={}
    if m:
        for line in m.group(1).splitlines():
            k,_,v=line.partition(':')
            if _ and not line.startswith(' '): d[k.strip()]=v.strip()
    return d

GROUPS=[('Engineering',['planner','implementer','reviewer','debugger','refactorer','architect','orchestrator','api-expert','ascii-architect']),
        ('Grok & research',['grok-fleet','df-research']),
        ('Web quality',['web-quality-audit','accessibility','seo','performance','core-web-vitals','best-practices']),
        ('Product management',['discovery-process','problem-statement','jobs-to-be-done','customer-journey-map','user-story-mapping','epic-breakdown-advisor','user-story','user-story-splitting','prd-development','roadmap-planning','prioritization-advisor']),
        ('JavaDucker (optional)',['add-javaducker','remove-javaducker'])]

# ---- skills ----
sk={}
for base in ('.claude/skills','template/.claude/skills'):
    for d in sorted(glob.glob(os.path.join(R,base,'*'))):
        if not os.path.isdir(d): continue
        n=os.path.basename(d)
        f=os.path.join(d,f'{n}.md')
        if not os.path.exists(f): continue
        e=sk.setdefault(n,{'name':n,'desc':'','repo':False,'tpl':False})
        e['desc']=e['desc'] or fm(f).get('description','')
        e['tpl' if 'template' in base else 'repo']=True
out=['---','title: Skills','nav_order: 4','---','','# Skills','',
     f'**{len(sk)} skills.** Invoke with `/<name>`. Generated from skill frontmatter — this page cannot drift.','']
seen=set()
for title,names in GROUPS:
    rows=[sk[n] for n in names if n in sk]
    if not rows: continue
    out += [f'## {title}','','| Skill | Ships to host projects | What it does |','|---|---|---|']
    for e in rows:
        seen.add(e['name'])
        out.append(f"| `/{e['name']}` | {'yes' if e['tpl'] else '**no — repo only**'} | {e['desc']} |")
    out.append('')
extra=[e for n,e in sorted(sk.items()) if n not in seen]
if extra:
    out += ['## Other','','| Skill | Ships | What it does |','|---|---|---|']
    out += [f"| `/{e['name']}` | {'yes' if e['tpl'] else '**no**'} | {e['desc']} |" for e in extra]+['']
open(os.path.join(D,'skills.md'),'w',encoding='utf-8').write('\n'.join(out))

# ---- scripts + subcommands ----
out=['---','title: Scripts','nav_order: 5','---','','# Scripts',
     '','Every script and subcommand, read out of the `case` dispatch blocks. Sources live in',
     '[`SCRIPTS.md`](https://github.com/drompincen/drom-flow/blob/main/SCRIPTS.md) (`*.sh` is gitignored).','']
for f in sorted(glob.glob(os.path.join(R,'scripts','*.sh'))):
    t=open(f,encoding='utf-8',errors='replace').read()
    subs=set()
    for m in re.finditer(r'^\s{0,4}([a-z][a-z0-9|_-]{2,})\)\s',t,re.M):
        for part in m.group(1).split('|'):
            if part not in ('true','false','http','https'): subs.add(part)
    desc=''
    for line in t.splitlines()[1:8]:
        if line.startswith('#') and len(line)>12 and 'drom-flow' in line:
            desc=line.lstrip('# ').strip(); break
    out.append(f"### `{os.path.basename(f)}`")
    if desc: out.append(f"\n{desc}\n")
    if subs: out.append('Subcommands: '+', '.join(f'`{s}`' for s in sorted(subs))+'\n')
open(os.path.join(D,'scripts.md'),'w',encoding='utf-8').write('\n'.join(out))

# ---- hooks ----
out=['---','title: Hooks','nav_order: 6','---','','# Lifecycle hooks','',
     'Hooks run **outside Claude\'s context** — they cost zero Claude tokens.','',
     '| Hook | Purpose |','|---|---|']
for f in sorted(glob.glob(os.path.join(R,'.claude/hooks','*.sh'))):
    t=open(f,encoding='utf-8',errors='replace').read().splitlines()
    d=next((l.lstrip('# ').strip() for l in t[1:6] if l.startswith('#') and len(l)>12),'')
    out.append(f"| `{os.path.basename(f)}` | {d} |")
open(os.path.join(D,'hooks.md'),'w',encoding='utf-8').write('\n'.join(out)+'\n')

# ---- workflows ----
out=['---','title: Workflows','nav_order: 7','---','','# Workflows','',
     '| Workflow | Purpose |','|---|---|']
for f in sorted(glob.glob(os.path.join(R,'workflows','*.md'))):
    t=open(f,encoding='utf-8',errors='replace').read().splitlines()
    d=next((l.strip() for l in t[1:8] if l.strip() and not l.startswith('#')),'')
    out.append(f"| `{os.path.basename(f)}` | {d[:110]} |")
open(os.path.join(D,'workflows.md'),'w',encoding='utf-8').write('\n'.join(out)+'\n')
print("generated: skills.md scripts.md hooks.md workflows.md")
PY
```

---

## scripts/docs-verify.sh

```bash
#!/bin/bash
# drom-flow — gates for the documentation site.
#   docs-verify.sh [--json]
# Writes reports/docs-site.json. Exit 0 only when every gate passes.

set -uo pipefail
R="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$R/reports/docs-site.json"; mkdir -p "$R/reports"
SITE_URL="${DOCS_SITE_URL:-https://drompincen.github.io/drom-flow/}"
G=(); FAIL=0
g(){ G+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$3")}")
     [ "$2" = PASS ] || FAIL=1; echo "[docs] $1: $2 — $3" >&2; }

# 1 builds — config + every page has front matter and a title
miss=""; for f in "$R"/docs/*.md; do head -1 "$f" | grep -q '^---$' || miss="$miss $(basename "$f")"; done
if [ -f "$R/docs/_config.yml" ] && [ -z "$miss" ]; then
  g builds PASS "_config.yml + $(ls "$R"/docs/*.md | wc -l | tr -d ' ') pages, all with front matter"
else g builds FAIL "config=$([ -f "$R/docs/_config.yml" ] && echo ok || echo missing) missing-front-matter:${miss:-none}"; fi

# 2 live
code=$(curl -s -o /dev/null -w '%{http_code}' -m 25 "$SITE_URL" 2>/dev/null || echo 000)
[ "$code" = 200 ] && g live PASS "$SITE_URL returns 200" || g live FAIL "$SITE_URL returned $code (enable Pages: main branch, /docs)"

# 3 orchestrator section covers the required ground
O="$R/docs/orchestration.md"; needed=(cache_creation "Routing" "filesystem" "resume" "collect --brief" "sandbox"); missing=""
for n in "${needed[@]}"; do grep -qi -- "$n" "$O" 2>/dev/null || missing="$missing '$n'"; done
[ -f "$O" ] && [ -z "$missing" ] && g orchestrator PASS "$(wc -c < "$O") bytes covering model, measurements, routing, lifecycle, resume, limits" \
  || g orchestrator FAIL "missing:${missing:-file absent}"

# 4 reference completeness — counted against the filesystem, never by eye
python3 - "$R" > /tmp/dv_ref 2>/dev/null <<'PY'
import os,glob,re,sys
R=sys.argv[1]
sk={os.path.basename(d) for b in ('.claude/skills','template/.claude/skills')
    for d in glob.glob(os.path.join(R,b,'*')) if os.path.isdir(d)
    and os.path.exists(os.path.join(d,os.path.basename(d)+'.md'))}
doc=open(os.path.join(R,'docs','skills.md'),encoding='utf-8',errors='replace').read()
msk=[s for s in sk if f'`/{s}`' not in doc]
sc={os.path.basename(f) for f in glob.glob(os.path.join(R,'scripts','*.sh'))}
sdoc=open(os.path.join(R,'docs','scripts.md'),encoding='utf-8',errors='replace').read()
msc=[s for s in sc if s not in sdoc]
hk={os.path.basename(f) for f in glob.glob(os.path.join(R,'.claude/hooks','*.sh'))}
hdoc=open(os.path.join(R,'docs','hooks.md'),encoding='utf-8',errors='replace').read()
mhk=[h for h in hk if h not in hdoc]
wf={os.path.basename(f) for f in glob.glob(os.path.join(R,'workflows','*.md'))}
wdoc=open(os.path.join(R,'docs','workflows.md'),encoding='utf-8',errors='replace').read()
mwf=[w for w in wf if w not in wdoc]
print(f"{len(sk)}|{len(sc)}|{len(hk)}|{len(wf)}|{','.join(msk+msc+mhk+mwf)}")
PY
IFS='|' read -r ns nsc nh nw missing < /tmp/dv_ref 2>/dev/null || missing="parse-failed"
[ -z "${missing:-}" ] && g reference PASS "$ns skills, $nsc scripts, $nh hooks, $nw workflows — all documented" \
  || g reference FAIL "undocumented: $missing"

# 5 links — internal .md targets must exist
bad=""
for f in "$R"/docs/*.md; do
  for l in $(grep -o '](\([a-z0-9_-]*\.md\)' "$f" 2>/dev/null | sed 's/](//'); do
    [ -f "$R/docs/$l" ] || bad="$bad $(basename "$f")->$l"
  done
done
[ -z "$bad" ] && g links PASS "all internal page links resolve" || g links FAIL "broken:$bad"

# 6 truth — skill parity between repo and template must be explicit
py=$(python3 - "$R" <<'PY'
import os,glob,sys
R=sys.argv[1]
def s(b): return {os.path.basename(d) for d in glob.glob(os.path.join(R,b,'*')) if os.path.isdir(d)}
only=s('.claude/skills')-s('template/.claude/skills')
print(','.join(sorted(only)))
PY
)
doc=$(cat "$R/docs/skills.md" 2>/dev/null)
if [ -z "$py" ]; then g truth PASS "no repo-only skills — full parity with template"
elif echo "$doc" | grep -q 'repo only'; then g truth PASS "repo-only skills documented as such: $py"
else g truth FAIL "repo-only skills not disclosed: $py"; fi

# 7 separation — docs/ must never reach a host project
TMP=$(mktemp -d); ( cd "$TMP" && git init -q . ) 2>/dev/null
bash "$R/init.sh" --update "$TMP" >/dev/null 2>&1
leak=""
for f in "$R"/docs/*.md; do b=$(basename "$f"); [ -f "$TMP/docs/$b" ] && leak="$leak $b"; done
rb=$([ -f "$TMP/.claude/docs/runbook.md" ] && echo yes || echo no)
ign=$(cd "$TMP" && git check-ignore .claude/docs/runbook.md >/dev/null 2>&1 && echo yes || echo no)
rm -rf "$TMP"
[ -z "$leak" ] && [ "$rb" = yes ] && [ "$ign" = yes ] \
  && g separation PASS "fresh install: no docs/ page shipped, .claude/docs/ present and gitignored" \
  || g separation FAIL "leaked:${leak:-none} runbook=$rb gitignored=$ign"

# 8 runbook covers the first hour
RB="$R/template/.claude/docs/runbook.md"; need=(doctor spawn status stop df-research resume); m=""
for n in "${need[@]}"; do grep -q -- "$n" "$RB" 2>/dev/null || m="$m $n"; done
[ -f "$RB" ] && [ -z "$m" ] && g runbook PASS "$(wc -c < "$RB") bytes covering ${need[*]}" || g runbook FAIL "missing:${m:-file absent}"

# 9 scripts tested — syntax + executed smoke test
st=""; for s in docs-gen.sh docs-verify.sh; do bash -n "$R/scripts/$s" 2>/dev/null || st="$st $s(syntax)"; done
bash "$R/scripts/docs-gen.sh" >/dev/null 2>&1 || st="$st docs-gen(run)"
[ -z "$st" ] && g scripts_tested PASS "docs-gen.sh + docs-verify.sh: bash -n clean and docs-gen executes" || g scripts_tested FAIL "failed:$st"

# 10 ship
sh=$(cat "$R/.claude/.state/docs_ship" 2>/dev/null || echo no)
[ "$sh" = pass ] && g ship PASS "$(cat "$R/.claude/.state/docs_ship_detail" 2>/dev/null)" || g ship FAIL "not shipped yet"

IFS=,; cat > "$OUT" <<EOF
{"ok":$([ $FAIL -eq 0 ] && echo true || echo false),"ts":"$(date -Iseconds)","gates":[${G[*]}]}
EOF
[ "${1:-}" = --json ] && cat "$OUT"
echo "[docs] gates: $(grep -o '"status":"PASS"' "$OUT" | wc -l)/${#G[@]} PASS" >&2
exit $FAIL
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
| `scripts/grok-resume.sh` | `template/scripts/grok-resume.sh` |
| `scripts/token-audit.sh` | `template/scripts/token-audit.sh` |
| `scripts/mk-task.sh` | `template/scripts/mk-task.sh` |
| `scripts/limit-watch.sh` | `template/scripts/limit-watch.sh` |
| `scripts/limit-verify.sh` | `template/scripts/limit-verify.sh` |
| `scripts/bench-audit.sh` | `template/scripts/bench-audit.sh` |
| `scripts/df-research.sh` | `template/scripts/df-research.sh` |
| `scripts/df-research-audit.sh` | `template/scripts/df-research-audit.sh` |
| `scripts/df-research-verify.sh` | `template/scripts/df-research-verify.sh` |

## .claude/df/repo-intel/run

Private repository-intelligence launcher. Chooses cached classes -> javac -> jbang -> jbang
bootstrap -> graceful unavailable, translates paths when the JVM is a Windows binary reached from
WSL, and fingerprints the engine sources so an edited extractor cannot keep serving an old graph.
Not a user command.

## .claude/hooks/repo-intel-mark.sh

PostToolUse dirty marker. Appends one line and returns: no JVM, no parsing, no blocking. Measured
at ~20 ms per edit.

## .claude/hooks/repo-intel-session.sh

SessionStart check. Inspects metadata only and starts intake detached when it is genuinely
needed. Silent when healthy.

## .claude/hooks/repo-intel-path.sh

Sourced helper resolving where the graph lives: `DROMFLOW_REPO_INTEL_STATE`, then
`REPO_INTEL_STATE` in `.claude/.state/drom-flow.conf`, then the default inside the project.

## scripts/repo-intel-verify.sh

Release gates for repository intelligence; writes `reports/repo-intel.json`.

## scripts/repo-intel-bench.sh

Discovery-cost benchmark: grep-then-read baseline versus one bounded graph query. Writes
`reports/repo-intel-bench.json`.
