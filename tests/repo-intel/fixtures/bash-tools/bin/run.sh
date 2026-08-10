#!/usr/bin/env bash
# Entrypoint: loads libs and runs deploy.

# Dot-form source of common
. lib/common.sh

# Explicit source of deploy (which itself sources common)
source lib/deploy.sh

main() {
  log "starting run"
  run_deploy
  die "done" || true
}

main "$@"
