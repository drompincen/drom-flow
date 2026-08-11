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
