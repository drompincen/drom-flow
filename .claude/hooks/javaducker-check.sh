#!/bin/bash
# drom-flow — JavaDucker guard and lifecycle functions (sourced by other hooks)
# When .claude/.state/javaducker.conf does not exist, all functions return false.

JAVADUCKER_CONF="${CLAUDE_PROJECT_DIR:-.}/.claude/.state/javaducker.conf"

javaducker_available() {
  [ -f "$JAVADUCKER_CONF" ] || return 1
  . "$JAVADUCKER_CONF"
  [ -n "$JAVADUCKER_ROOT" ]
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
