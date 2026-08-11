#!/usr/bin/env bash
# Shared helpers for bash-tools.

export APP_NAME="bash-tools"
export LOG_LEVEL="info"
readonly MAX_RETRIES=3
readonly DEFAULT_TIMEOUT=30

# Variable named like a function (trap — not a call)
log_prefix="[common]"

# name() form
log() {
  printf '%s %s\n' "$log_prefix" "$1"
}

# function name form; same-file call to log
function die {
  log "ERROR: $1"
  return 1
}

check_ready() {
  # Function name only in a comment: die
  # Single-quoted string must not count as a call: 'please die quietly'
  local hint='never invoke die from this string'
  log "ready:$hint"
}

# Shadow-name trap target: external `printf` must not resolve here
printf_fmt() {
  printf '%s\n' "$1"
}
