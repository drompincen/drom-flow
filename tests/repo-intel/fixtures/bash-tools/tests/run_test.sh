#!/usr/bin/env bash
# Simple test runner for bash-tools fixtures (syntax only).

. lib/common.sh
source lib/deploy.sh

run_all_tests() {
  log "running tests"
  check_ready
  prepare_deploy
  # External test builtin — not other-common.sh:test
  if test 1 -eq 1; then
    log "smoke ok"
  fi
}

function main {
  run_all_tests
}

main "$@"
