#!/usr/bin/env bash
# Deploy helpers. Sources only common.sh — not other-common.sh.

source lib/common.sh

prepare_deploy() {
  log "preparing deploy"
  check_ready
}

function deploy {
  prepare_deploy
  log "deploying app=$APP_NAME retries=$MAX_RETRIES"
  # External/builtin test — must NOT resolve to other-common.sh:test
  if test -n "$APP_NAME"; then
    log "app name is set"
  fi
  # External/builtin printf — must NOT resolve to a repo function
  printf 'deploy complete\n'
}

run_deploy() {
  deploy
}
