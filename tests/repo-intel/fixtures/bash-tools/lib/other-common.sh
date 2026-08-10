#!/usr/bin/env bash
# Alternate helpers — deliberately NOT sourced by deploy.sh.
# Defines log() with the same name as common.sh (resolution trap).

log() {
  echo "OTHER-LOG: $1"
}

# External-name trap: a function called test; external `test` must not resolve here
test() {
  log "other-test:$1"
}

other_helper() {
  log "from other-common"
}
