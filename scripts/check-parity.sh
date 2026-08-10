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
