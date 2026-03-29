#!/bin/bash
# drom-flow — JavaDucker guard functions (sourced by other hooks)
# Provides javaducker_available() and javaducker_healthy()
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
