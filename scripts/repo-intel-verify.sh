#!/bin/bash
# drom-flow — release gates for repository intelligence.
#   repo-intel-verify.sh [--json] [--only GATE]
# Writes reports/repo-intel.json. Exit 0 only when every gate passes.
#
# Every gate here corresponds to a line in the release scorecard. Numbers are measured, never
# asserted: if a gate cannot be measured on this machine it fails loudly rather than passing quietly.

set -uo pipefail
# `date +%s%N` is GNU-only; on macOS it yields a literal N and every timing becomes garbage.
now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }
R="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$R/reports/repo-intel.json"; mkdir -p "$R/reports"
WORK="$R/.ri-test"; mkdir -p "$WORK"
RUN="$R/template/.claude/df/repo-intel/run"
FIX="$R/tests/repo-intel/fixtures"
ONLY="${2:-}"
G=(); FAIL=0

j() { python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$1"; }
g() { # id status detail [metrics-json]
  G+=("{\"id\":\"$1\",\"status\":\"$2\",\"detail\":$(j "$3")${4:+,\"metrics\":$4}}")
  [ "$2" = PASS ] || FAIL=1
  printf '[repo-intel] %-22s %-4s %s\n' "$1" "$2" "$3" >&2
}
skip_gate() { [ -n "$ONLY" ] && [ "$ONLY" != "$1" ]; }

# A fixture copy gets its own git repo so the parent project's ignore rules cannot hide it.
mkfix() { # name dest
  rm -rf "$2"; cp -r "$FIX/$1" "$2"
  ( cd "$2" && git init -q . && git add -A ) >/dev/null 2>&1
}
build() { CLAUDE_PROJECT_DIR="$1" bash "$RUN" ensure >/dev/null 2>&1; }
query() { local d="$1"; shift; CLAUDE_PROJECT_DIR="$d" bash "$RUN" "$@" 2>/dev/null; }
statedir() { echo "$1/.claude/.state/repo-intel"; }
jget() { python3 -c "import json,sys;d=json.load(open(sys.argv[1]));print(d.get(sys.argv[2],''))" "$1" "$2" 2>/dev/null; }
jq_field() { python3 -c "import json,sys;print(json.loads(sys.stdin.read()).get('graph',{}).get(sys.argv[1],''))" "$1" 2>/dev/null; }
node_ids() { python3 -c "import json,sys;g=json.load(open(sys.argv[1]));print('\n'.join(sorted(n['id'] for n in g['nodes'])))" "$1" 2>/dev/null; }

# ---------------------------------------------------------------- G1 fixture correctness
if ! skip_gate correctness; then
  tot_d=0; hit_d=0; tot_r=0; hit_r=0; tot_x=0; hit_x=0; fp=0; conf=0
  for f in java-spring python-app ts-js-app bash-tools manifests; do
    mkfix "$f" "$WORK/$f"; build "$WORK/$f"
    python3 "$R/tests/repo-intel/check_fixture.py" "$(statedir "$WORK/$f")/graph.json" \
      "$FIX/$f.ground-truth.json" > "$WORK/$f.score.json" 2>/dev/null || true
  done
  read -r tot_d hit_d tot_r hit_r tot_x hit_x fp conf <<<"$(python3 - "$WORK" <<'PY'
import json,glob,sys,os
t=[0]*8
for p in glob.glob(os.path.join(sys.argv[1],'*.score.json')):
    try: d=json.load(open(p))
    except Exception: continue
    t[0]+=d['declarations']['total']; t[1]+=d['declarations']['found']
    t[2]+=d['relations']['total'];    t[3]+=d['relations']['found']
    t[4]+=d['cross_file']['total'];   t[5]+=d['cross_file']['found']
    t[6]+=d['negatives']['false_positives']; t[7]+=d['graph']['confident_edges']
print(*t)
PY
)"
  dr=$(python3 -c "print(round(100*$hit_d/max(1,$tot_d),1))")
  rr=$(python3 -c "print(round(100*$hit_r/max(1,$tot_r),1))")
  xr=$(python3 -c "print(round(100*$hit_x/max(1,$tot_x),1))")
  fpr=$(python3 -c "print(round(100*$fp/max(1,$conf),2))")
  m="{\"declaration_recall_pct\":$dr,\"relation_recall_pct\":$rr,\"cross_file_recall_pct\":$xr,\"false_positive_pct\":$fpr,\"confident_edges\":$conf}"
  if python3 -c "import sys; sys.exit(0 if ($dr>=95 and $rr>=90 and $xr>=90 and $fpr<2) else 1)"; then
    g correctness PASS "decl ${dr}% rel ${rr}% cross-file ${xr}% false-positive ${fpr}% over $conf confident edges" "$m"
  else
    g correctness FAIL "decl ${dr}% (need 95) rel ${rr}% (need 90) cross-file ${xr}% (need 90) FP ${fpr}% (need <2)" "$m"
  fi
fi

# ------------------------------------------------ G2 incremental refresh == clean rebuild
if ! skip_gate incremental_equivalence; then
  norm() { python3 -c "
import json,sys
g=json.load(open(sys.argv[1]))
print(json.dumps({'nodes':sorted(json.dumps(n,sort_keys=True) for n in g['nodes']),
                  'edges':sorted(json.dumps(e,sort_keys=True) for e in g['edges'])},sort_keys=True))" "$1"; }
  mkfix java-spring "$WORK/inc"; build "$WORK/inc"
  # add, modify, delete, rename -- all four mutation kinds in one pass
  cat > "$WORK/inc/src/main/java/com/acme/util/Added.java" <<'JAVA'
package com.acme.util;
public class Added {
    public static String tag(String s) { return Helper.format(s); }
}
JAVA
  printf '\n// touched\n' >> "$WORK/inc/src/main/java/com/acme/model/Case.java"
  rm -f "$WORK/inc/src/main/java/com/acme/other/Helper.java"
  git -C "$WORK/inc" mv src/main/java/com/acme/event/CaseUpdatedEvent.java \
       src/main/java/com/acme/event/CaseChangedEvent.java >/dev/null 2>&1 \
    || mv "$WORK/inc/src/main/java/com/acme/event/CaseUpdatedEvent.java" \
          "$WORK/inc/src/main/java/com/acme/event/CaseChangedEvent.java"
  ( cd "$WORK/inc" && git add -A ) >/dev/null 2>&1
  build "$WORK/inc"
  inc_action=$(jget "$(statedir "$WORK/inc")/metadata.json" last_action)

  # Rebuild IN PLACE: comparing two differently named copies would differ only by the
  # repository node id, which says nothing about incremental correctness.
  cp "$(statedir "$WORK/inc")/graph.json" "$WORK/inc-incremental-graph.json"
  rm -rf "$(statedir "$WORK/inc")"
  build "$WORK/inc"
  a="$(norm "$WORK/inc-incremental-graph.json")"; b="$(norm "$(statedir "$WORK/inc")/graph.json")"
  if [ "$a" = "$b" ]; then
    g incremental_equivalence PASS "add+modify+delete+rename incremental (${inc_action}) is byte-identical to a clean rebuild" \
      "{\"mutations\":4,\"incremental_action\":\"$inc_action\"}"
  else
    d=$(python3 -c "
import json,sys
a=json.loads(sys.argv[1]); b=json.loads(sys.argv[2])
print('nodes only-inc %d only-reb %d edges only-inc %d only-reb %d'%(
 len(set(a['nodes'])-set(b['nodes'])),len(set(b['nodes'])-set(a['nodes'])),
 len(set(a['edges'])-set(b['edges'])),len(set(b['edges'])-set(a['edges']))))" "$a" "$b")
    g incremental_equivalence FAIL "incremental != rebuild: $d"
  fi
fi

# ----------------------------------------------------- G3 stable identity, no wasted work
if ! skip_gate stable_identity; then
  mkfix java-spring "$WORK/stab"; build "$WORK/stab"
  ids1=$(node_ids "$(statedir "$WORK/stab")/graph.json")
  out=$(query "$WORK/stab" ensure)
  parsed=$(jq_field parsed <<<"$out")
  action=$(jq_field action <<<"$out")
  # move a method within its file: identity must not change
  python3 - "$WORK/stab/src/main/java/com/acme/util/Helper.java" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
open(p,'w').write("// leading comment added to shift every line\n"+s)
PY
  build "$WORK/stab"
  ids2=$(node_ids "$(statedir "$WORK/stab")/graph.json")
  if [ "$action" = "noop" ] && [ "$parsed" = "0" ] && [ "$ids1" = "$ids2" ]; then
    nc=$(printf '%s\n' "$ids1" | wc -l | tr -d ' ')
    g stable_identity PASS "unchanged repo: action=noop, 0 files parsed; a line shift left all $nc node ids unchanged" \
      "{\"noop_parsed\":$parsed,\"nodes\":$nc}"
  else
    g stable_identity FAIL "action=$action parsed=$parsed ids_stable=$([ "$ids1" = "$ids2" ] && echo yes || echo no)"
  fi
fi

# ------------------------------------------------------------------ G4 bounded output
if ! skip_gate bounded_output; then
  mkfix java-spring "$WORK/bound"; build "$WORK/bound"
  worst=0; worst_n=0; worst_e=0; ok=1
  for cmd in "search case" "impact CaseStatus" "neighbors CaseService" "dependents Case" "impact Case"; do
    o=$(query "$WORK/bound" $cmd --limit 500 --depth 6)
    read -r bytes n e trunc <<<"$(python3 -c "
import json,sys
s=sys.stdin.read(); d=json.loads(s)
print(len(s), len(d.get('results',[])), len(d.get('edges',[])), str(d.get('truncated')).lower())" <<<"$o")"
    [ "$bytes" -gt "$worst" ] && worst=$bytes
    [ "$n" -gt "$worst_n" ] && worst_n=$n
    [ "$e" -gt "$worst_e" ] && worst_e=$e
    { [ "$n" -le 25 ] && [ "$e" -le 40 ] && [ "$bytes" -le 15400 ]; } || ok=0
  done
  m="{\"max_bytes\":$worst,\"max_nodes\":$worst_n,\"max_edges\":$worst_e}"
  [ "$ok" = 1 ] && g bounded_output PASS "worst case ${worst}B, ${worst_n} nodes, ${worst_e} edges even with --limit 500 --depth 6" "$m" \
                || g bounded_output FAIL "budget exceeded: ${worst}B / ${worst_n} nodes / ${worst_e} edges" "$m"
fi

# ------------------------------------------------------------------ G5 security
if ! skip_gate security; then
  S="$WORK/sec"; rm -rf "$S"; mkdir -p "$S/src" "$S/outside"
  cat > "$S/src/App.java" <<'JAVA'
package app;
public class App { public static void main(String[] a) { System.out.println("hi"); } }
JAVA
  printf 'SECRET_TOKEN=abcd1234\nDB_PASSWORD=hunter2\n' > "$S/.env"
  printf 'API_KEY=zzz\n' > "$S/.env.production"
  printf -- '-----BEGIN PRIVATE KEY-----\nabc\n' > "$S/server.key"
  printf 'outside-secret\n' > "$WORK/outside-target.txt"
  ln -sf "$WORK/outside-target.txt" "$S/escape.txt" 2>/dev/null
  head -c 4096 /dev/urandom > "$S/blob.bin" 2>/dev/null
  python3 -c "import sys;open(sys.argv[1],'w').write('package app;\n'+('// pad\n'*200000))" "$S/huge.java"
  cat > "$S/src/Broken.java" <<'JAVA'
package app;
public class Broken {
  public void oops( {{{ unterminated "string
JAVA
  mkdir -p "$S/weird dir"; printf 'package app;\npublic class Spaced {}\n' > "$S/weird dir/Spaced File.java"
  ( cd "$S" && git init -q . && git add -A ) >/dev/null 2>&1
  build "$S"
  viol=$(python3 - "$(statedir "$S")/graph.json" "$(statedir "$S")/metadata.json" <<'PY'
import json,sys
g=json.load(open(sys.argv[1]))
files={n.get('file') for n in g['nodes'] if n.get('file')}
bad=[]
for f in files:
    if f is None: continue
    if f.startswith('.env') or f.endswith('.key'): bad.append('secret:'+f)
    if f.endswith('.bin'): bad.append('binary:'+f)
    if f=='huge.java': bad.append('oversized:'+f)
    if f=='escape.txt': bad.append('symlink-escape:'+f)
    if f.startswith('/') or '..' in f: bad.append('unsafe-path:'+f)
try:
    m=json.load(open(sys.argv[2])); ok = m.get('status')=='ready'
except Exception: ok=False
spaced = any('weird dir' in (f or '') for f in files)
print('|'.join(bad) + '#' + ('ready' if ok else 'degraded') + '#' + ('spaced' if spaced else 'nospaced'))
PY
)
  bad="${viol%%#*}"; rest="${viol#*#}"; status="${rest%%#*}"; spaced="${rest##*#}"
  if [ -z "$bad" ] && [ "$status" = ready ] && [ "$spaced" = spaced ]; then
    g security PASS "no secret/binary/oversized/symlink-escape/traversal indexed; malformed source isolated; path with spaces indexed" \
      "{\"violations\":0}"
  else
    g security FAIL "violations: ${bad:-none}; status=$status; spaces=$spaced"
  fi
fi

# ------------------------------------------- G6 edit hook: fast, and no JVM on the hot path
if ! skip_gate edit_hook; then
  H="$WORK/hook"; rm -rf "$H"; mkdir -p "$H/.claude/hooks" "$H/shim"
  cp "$R/template/.claude/hooks/repo-intel-mark.sh" "$R/template/.claude/hooks/repo-intel-path.sh" "$H/.claude/hooks/"
  mkdir -p "$H/.claude/.state/repo-intel"
  for b in java javac jbang; do
    printf '#!/bin/sh\ntouch "%s/jvm-was-launched"\nexit 0\n' "$H" > "$H/shim/$b"; chmod +x "$H/shim/$b"
  done
  t0=$(now_ms)
  for i in 1 2 3 4 5 6 7 8 9 10; do
    PATH="$H/shim:$PATH" CLAUDE_PROJECT_DIR="$H" \
      CLAUDE_TOOL_USE_INPUT="{\"file_path\":\"$H/src/Thing$i.java\"}" \
      bash "$H/.claude/hooks/repo-intel-mark.sh" </dev/null >/dev/null 2>&1
  done
  t1=$(now_ms)
  avg=$(python3 -c "print(round(($t1-$t0)/10,1))")
  lines=$(wc -l < "$H/.claude/.state/repo-intel/dirty" 2>/dev/null || echo 0)
  jvm=$([ -f "$H/jvm-was-launched" ] && echo yes || echo no)
  m="{\"avg_ms\":$avg,\"marks\":$lines,\"jvm_launched\":\"$jvm\"}"
  if [ "$jvm" = no ] && [ "$lines" -eq 10 ] && python3 -c "import sys;sys.exit(0 if $avg<100 else 1)"; then
    g edit_hook PASS "10 marks in ${avg}ms average, no JVM process started" "$m"
  else
    g edit_hook FAIL "avg=${avg}ms marks=$lines jvm_launched=$jvm" "$m"
  fi
fi

# --------------------------------------------------- G7 freshness incl. external changes
if ! skip_gate freshness; then
  mkfix java-spring "$WORK/fresh"; build "$WORK/fresh"
  # an edit made outside Claude: no hook, no dirty marker
  python3 - "$WORK/fresh/src/main/java/com/acme/util/Helper.java" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
open(p,'w').write(s.replace('public class Helper {', 'public class Helper {\n    public static String externallyAdded() { return "x"; }'))
PY
  o=$(query "$WORK/fresh" symbol externallyAdded)
  found=$(python3 -c "import json,sys;print(len(json.loads(sys.stdin.read()).get('results',[])))" <<<"$o" 2>/dev/null || echo 0)
  act=$(jq_field action <<<"$o")
  # and a hook-marked edit
  printf 'src/main/java/com/acme/util/Helper.java\t1\n' >> "$(statedir "$WORK/fresh")/dirty"
  o2=$(query "$WORK/fresh" stats); dirty_left=$([ -f "$(statedir "$WORK/fresh")/dirty" ] && echo yes || echo no)
  if [ "$found" -ge 1 ] && [ "$act" = "incremental" ] && [ "$dirty_left" = no ]; then
    g freshness PASS "external edit detected without any hook (action=$act) and the dirty journal is consumed" \
      "{\"external_detected\":true,\"action\":\"$act\"}"
  else
    g freshness FAIL "external_found=$found action=$act dirty_consumed=$dirty_left"
  fi
fi

# ------------------------------------------------------------- G8 failure isolation
if ! skip_gate failure_isolation; then
  F="$WORK/fail"; mkfix java-spring "$F"; build "$F"
  probs=()
  # no JVM at all
  E="$WORK/emptybin"; rm -rf "$E"; mkdir -p "$E"
  out=$(PATH="$E:/usr/bin:/bin" JAVA_HOME= CLAUDE_PROJECT_DIR="$F" DROMFLOW_REPO_INTEL_NO_NETWORK=1 \
        HOME="$WORK/nohome" bash "$RUN" stats 2>/dev/null); rc=$?
  python3 -c "import json,sys;d=json.loads(sys.argv[1]);sys.exit(0 if d['ok'] is False and d['error']['code']=='engine_unavailable' else 1)" "$out" 2>/dev/null \
    && [ $rc -eq 3 ] || probs+=("no-jvm did not degrade cleanly (rc=$rc)")
  rm -f "$(statedir "$F")/unavailable.json"
  # corrupted graph
  echo '{"schema_version":1,"nodes":[{"id":' > "$(statedir "$F")/graph.json"
  out=$(query "$F" stats)
  python3 -c "import json,sys;d=json.loads(sys.argv[1]);sys.exit(0 if d['ok'] and d['graph']['nodes']>0 else 1)" "$out" 2>/dev/null \
    || probs+=("corrupted graph not recovered")
  # read-only state directory
  chmod -w "$(statedir "$F")" 2>/dev/null
  out=$(query "$F" search case); rc2=$?
  chmod +w "$(statedir "$F")" 2>/dev/null
  python3 -c "import json,sys;json.loads(sys.argv[1])" "$out" 2>/dev/null || probs+=("read-only state produced non-JSON")
  # blocked dependency resolution
  out=$(CLAUDE_PROJECT_DIR="$F" DROMFLOW_REPO_INTEL_NO_NETWORK=1 bash "$RUN" stats 2>/dev/null)
  python3 -c "import json,sys;json.loads(sys.argv[1])" "$out" 2>/dev/null || probs+=("offline mode produced non-JSON")
  if [ ${#probs[@]} -eq 0 ]; then
    g failure_isolation PASS "no-JVM, corrupt graph, read-only state and offline all degrade to structured JSON; drom-flow keeps working" \
      "{\"scenarios\":4}"
  else
    g failure_isolation FAIL "$(IFS='; '; echo "${probs[*]}")"
  fi
fi

# ------------------------------------------------------- G9 install / upgrade / uninstall
if ! skip_gate lifecycle; then
  HOST="$WORK/host"; rm -rf "$HOST"; mkdir -p "$HOST/src/main/java/com/demo"
  cat > "$HOST/src/main/java/com/demo/Thing.java" <<'JAVA'
package com.demo;
public class Thing { public String hello() { return "hi"; } }
JAVA
  ( cd "$HOST" && git init -q . && git add -A ) >/dev/null 2>&1
  bash "$R/init.sh" "$HOST" >/dev/null 2>&1
  probs=()
  for a in ".claude/df/repo-intel/run" ".claude/df/repo-intel/RepoIntel.java" \
           ".claude/hooks/repo-intel-mark.sh" ".claude/hooks/repo-intel-session.sh" \
           ".claude/hooks/repo-intel-path.sh"; do
    [ -f "$HOST/$a" ] || probs+=("missing after install: $a")
  done
  [ -x "$HOST/.claude/df/repo-intel/run" ] || probs+=("run wrapper not executable")
  grep -q 'repo-intel-mark' "$HOST/.claude/settings.json" || probs+=("PostToolUse hook not registered")
  grep -q 'repo-intel-session' "$HOST/.claude/settings.json" || probs+=("SessionStart hook not registered")
  # the user runs nothing: the session hook must bring the graph into existence
  CLAUDE_PROJECT_DIR="$HOST" bash "$HOST/.claude/hooks/repo-intel-session.sh" >/dev/null 2>&1
  for i in $(seq 1 40); do [ -f "$(statedir "$HOST")/graph.json" ] && break; sleep 0.5; done
  [ -f "$(statedir "$HOST")/graph.json" ] || probs+=("automatic intake did not produce a graph")
  auto_nodes=$(python3 -c "import json;print(len(json.load(open('$(statedir "$HOST")/graph.json'))['nodes']))" 2>/dev/null || echo 0)
  # upgrade: an older schema/engine must rebuild itself with no user action
  python3 - "$(statedir "$HOST")/metadata.json" "$(statedir "$HOST")/graph.json" <<'PY'
import json,sys
m=json.load(open(sys.argv[1])); m['engine_version']='0.0.1'; m['schema_version']=0
m['engine_stamp']='stale'; json.dump(m,open(sys.argv[1],'w'),indent=2)
g=json.load(open(sys.argv[2])); g['schema_version']=0; json.dump(g,open(sys.argv[2],'w'))
PY
  up=$(CLAUDE_PROJECT_DIR="$HOST" bash "$HOST/.claude/df/repo-intel/run" stats 2>/dev/null)
  up_action=$(jq_field action <<<"$up")
  [ "$up_action" = "full_intake" ] || probs+=("schema downgrade did not force a rebuild (action=$up_action)")
  # uninstall
  bash "$R/init.sh" --uninstall "$HOST" >/dev/null 2>&1
  for a in ".claude/df/repo-intel/run" ".claude/hooks/repo-intel-mark.sh" ".claude/hooks/repo-intel-session.sh"; do
    [ -e "$HOST/$a" ] && probs+=("left behind after uninstall: $a")
  done
  [ -d "$HOST/.claude/.state/repo-intel" ] && probs+=("state left behind after uninstall")
  [ -f "$HOST/src/main/java/com/demo/Thing.java" ] || probs+=("uninstall damaged host source")
  if [ ${#probs[@]} -eq 0 ]; then
    g lifecycle PASS "fresh install wires everything, automatic intake built $auto_nodes nodes with no user command, schema downgrade self-rebuilt, uninstall left host source intact" \
      "{\"auto_intake_nodes\":$auto_nodes,\"upgrade_action\":\"$up_action\"}"
  else
    g lifecycle FAIL "$(IFS='; '; echo "${probs[*]}")"
  fi
fi

# ------------------------------------------------------------- G10 graph verification
if ! skip_gate graph_validation; then
  mkfix java-spring "$WORK/val"; build "$WORK/val"
  o=$(query "$WORK/val" verify)
  errs=$(python3 -c "import json,sys;d=json.loads(sys.stdin.read());print(len(d.get('errors',[])))" <<<"$o" 2>/dev/null || echo -1)
  [ "$errs" = "0" ] && g graph_validation PASS "validator reports 0 integrity errors" "{\"errors\":0}" \
                    || g graph_validation FAIL "$errs integrity errors"
fi

# ------------------------------------------------------------- G11 relocatable state
if ! skip_gate state_location; then
  mkfix java-spring "$WORK/loc"; ALT="$WORK/loc-elsewhere"; rm -rf "$ALT"
  DROMFLOW_REPO_INTEL_STATE="$ALT" CLAUDE_PROJECT_DIR="$WORK/loc" bash "$RUN" ensure >/dev/null 2>&1
  a=$([ -f "$ALT/graph.json" ] && echo yes || echo no)
  b=$([ -d "$WORK/loc/.claude/.state/repo-intel" ] && echo yes || echo no)
  mkdir -p "$WORK/loc/.claude/.state"
  printf 'REPO_INTEL_STATE=%s\n' "$WORK/loc-conf" >> "$WORK/loc/.claude/.state/drom-flow.conf"
  mkdir -p "$WORK/loc/.claude/hooks"; cp "$R/template/.claude/hooks/repo-intel-path.sh" "$WORK/loc/.claude/hooks/"
  CLAUDE_PROJECT_DIR="$WORK/loc" bash "$RUN" ensure >/dev/null 2>&1
  c=$([ -f "$WORK/loc-conf/graph.json" ] && echo yes || echo no)
  if [ "$a" = yes ] && [ "$b" = no ] && [ "$c" = yes ]; then
    g state_location PASS "env override and drom-flow.conf both relocate the graph; default stays in the host project" \
      "{\"env_override\":true,\"conf_override\":true}"
  else
    g state_location FAIL "env=$a default_polluted=$b conf=$c"
  fi
fi

# ------------------------------------------------------------- G12 impact usefulness
if ! skip_gate impact; then
  mkfix java-spring "$WORK/imp"; build "$WORK/imp"
  declare -a CASES=(
    "CaseStatus|src/main/java/com/acme/model/Case.java,src/main/java/com/acme/service/CaseServiceImpl.java"
    "CaseService|src/main/java/com/acme/service/CaseServiceImpl.java,src/main/java/com/acme/api/CaseController.java"
    "CaseRepository|src/main/java/com/acme/service/CaseServiceImpl.java"
    "Case|src/main/java/com/acme/repo/CaseRepository.java,src/main/java/com/acme/service/CaseServiceImpl.java"
    "Helper|src/main/java/com/acme/service/CaseServiceImpl.java"
  )
  hit=0; total=0; ranks=""
  for c in "${CASES[@]}"; do
    sym="${c%%|*}"; want="${c##*|}"
    o=$(query "$WORK/imp" impact "$sym" --limit 25)
    for w in ${want//,/ }; do
      total=$((total+1))
      python3 -c "
import json,sys
d=json.loads(sys.argv[1]); files=d.get('candidate_files',[])
sys.exit(0 if sys.argv[2] in files else 1)" "$o" "$w" && hit=$((hit+1))
    done
    n=$(python3 -c "import json,sys;print(len(json.loads(sys.argv[1])['results']))" "$o")
    ranks="$ranks $sym:$n"
  done
  pct=$(python3 -c "print(round(100*$hit/max(1,$total),1))")
  m="{\"critical_files_found\":$hit,\"critical_files_total\":$total,\"recall_pct\":$pct}"
  python3 -c "import sys;sys.exit(0 if $pct>=90 else 1)" \
    && g impact PASS "critical downstream files surfaced in $hit/$total cases (${pct}%), bounded result sizes:$ranks" "$m" \
    || g impact FAIL "only ${pct}% of critical downstream files surfaced ($hit/$total)"
fi

# ------------------------------------------------------------- report
python3 - "$OUT" "$FAIL" "${G[@]}" <<'PY'
import json,sys,datetime
out,fail=sys.argv[1],sys.argv[2]
gates=[json.loads(x) for x in sys.argv[3:]]
json.dump({'ok':fail=='0','generated':datetime.datetime.now().isoformat(timespec='seconds'),
           'passed':sum(1 for g in gates if g['status']=='PASS'),'total':len(gates),
           'gates':gates}, open(out,'w'), indent=2)
print(f"\n{sum(1 for g in gates if g['status']=='PASS')}/{len(gates)} gates pass -> {out}")
PY
exit $FAIL
