#!/usr/bin/env bash
# R2 SELF-BRIDGE + SPARKPLUG NCMD GATE (objective cross-repo proof): koshei's physical apply flows
# koshei -> Sparkplug NCMD -> HiveMQ CE -> the self-bridge (Milo+Tahu, in sparkplug-governance-lab)
# -> OPC-UA -> the shared Milo sim, authorized INDEPENDENTLY at the edge, compensation over the same
# channel. Selects Approach B behind the ApplyPort seam via KOSHEI_APPLY_MODE=ncmd; R1 (direct) is
# untouched (run-opcua-gate.sh).
#
# Asserts:
#   T1 happy            : reconcile -> approve -> saga completes over NCMD; opcua.write WRITTEN>=1,
#                         opcua.call CONFIRMED==1 (write+activate confirmed THROUGH the bridge).
#   T1b repeated       : a SECOND reconcile->approve to the same line, NO manual reset -> opcua.call
#                         CONFIRMED==1 again (bridge de-assert rearmed the equipment; NCMD handshake).
#   T2 veto             : reconcile -> reach gate (write WRITTEN>=1) -> REJECT -> completed=false;
#                         opcua.write RESTORED>=1 (reverse NCMD RESTORE), opcua.call CONFIRMED==0.
#   T3 rogue            : publish an NCMD DIRECTLY to the broker (bypass koshei) for a deny-by-default
#                         node -> bridge DENY, no APPLY (edge authz independent of koshei's D4).
#   T4 defense-in-depth : rogue write of an ALLOWED node (Rpm) but out-of-EURange (9999) -> bridge
#                         DENY above-max, no new APPLY for that publish.
#   T5 fail-closed      : bridge down -> koshei ncmd write times out -> run does NOT complete, no
#                         partial (opcua.call CONFIRMED==0; worker logs an ncmd timeout / permanent fail).
#
# Run from the koshei repo root with the stack up + a sparkplug-governance-lab checkout:
#   docker compose up -d
#   LAB_DIR="/path/to/sparkplug-governance-lab" bash scripts/run-r2-ncmd-gate.sh
#   # expect: [GATE] PASS run-r2-ncmd-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."
# Shared gate helpers (native_path, psql_q, kill_jvms_by, wait_for_log, wait_http_ok, wait_tcp).
# Resolve relative to this script so the source works regardless of CWD.
source "$(dirname "$0")/lib/gate-common.sh"

PLUGIN_DIR_SH="${KOSHEI_PLUGIN_DIR:-$(pwd)/registry-store}"
export KOSHEI_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export KOSHEI_DB_URL="${KOSHEI_DB_URL:-jdbc:postgresql://localhost:15432/koshei}"
export KOSHEI_DB_USER="${KOSHEI_DB_USER:-koshei}"
export KOSHEI_DB_PASS="${KOSHEI_DB_PASS:-koshei}"
export KOSHEI_WF_POLL_MS="${KOSHEI_WF_POLL_MS:-3000}"
export KOSHEI_FAULT_INJECT=1
export KOSHEI_OPCUA_URL="${KOSHEI_OPCUA_URL:-opc.tcp://localhost:48400}"
# ---- Approach B (NCMD) wire config: selects SparkplugNcmdApplyPort in the two :opcua blocks and
#      points it at HiveMQ CE / the Sparkplug identity the bridge authorizes. Forwarded to the worker
#      JVM via app/build.gradle.kts forwardKosheiEnv (every KOSHEI_* var).
export KOSHEI_APPLY_MODE="${KOSHEI_APPLY_MODE:-ncmd}"
export KOSHEI_MQTT_URL="${KOSHEI_MQTT_URL:-tcp://localhost:1883}"
export KOSHEI_SPB_GROUP="${KOSHEI_SPB_GROUP:-Koshei:Line1}"
export KOSHEI_SPB_EDGE="${KOSHEI_SPB_EDGE:-recipe-edge}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/r2-ncmd-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"
SIM_LOG="$WORK/sim.log"
BRIDGE_LOG="$WORK/bridge.log"
PERTURB_LOG="$WORK/perturb.log"
ROGUE_LOG="$WORK/rogue.log"
: > "$PERTURB_LOG"
: > "$ROGUE_LOG"

BRIDGE_PID=""

# psql_q, kill_jvms_by come from scripts/lib/gate-common.sh (sourced above).
# The bridge runs as `mvn exec:java` (the main class runs INSIDE the Maven/classworlds JVM, so it is
# NOT visible to `jps -l` as NcmdOpcUaBridgeMain). Kill the recorded process TREE (//T) — the bash
# subshell -> mvn -> java children — and best-effort mop up any classworlds launcher left behind.
kill_bridge() {
  if [ -n "$BRIDGE_PID" ]; then taskkill //F //T //PID "$BRIDGE_PID" >/dev/null 2>&1 || true; fi
  { jps -lm 2>/dev/null | grep -i "NcmdOpcUaBridgeMain" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done
}
cleanup() {
  kill_jvms_by "koshei.authoring.AuthoringApplicationKt" || true; kill_jvms_by "koshei.app.WorkerKt" || true; kill_jvms_by "koshei.opcua.SimMainKt" || true; kill_bridge || true
  [ -n "${LAB_COMPOSE:-}" ] && docker compose -f "$LAB_COMPOSE" stop hivemq-ce >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
  echo "--- sim log tail ---";    tail -20 "$SIM_LOG"    2>/dev/null || true
  echo "--- bridge log tail ---"; tail -40 "$BRIDGE_LOG" 2>/dev/null || true
  exit 1
}

# Cross-repo dependency: the sparkplug-governance-lab checkout (the dir containing pom.xml + the
# self-bridge). Fail-closed if unset — NO silent skip of the cross-repo assertions (integration-PoV lesson).
LAB_DIR="${LAB_DIR:-}"
[ -n "$LAB_DIR" ] || fail "LAB_DIR not set — point it at the sparkplug-governance-lab checkout (the dir containing pom.xml + the NCMD self-bridge). No silent skip of the NCMD assertions."
[ -f "$LAB_DIR/pom.xml" ] || fail "no pom.xml under LAB_DIR='$LAB_DIR'"
[ -f "$LAB_DIR/docker-compose.yml" ] || fail "no docker-compose.yml under LAB_DIR='$LAB_DIR' (needs the hivemq-ce service)"
# docker compose is a NATIVE Windows binary: an MSYS /c/... path passed to `-f` becomes an invalid
# C:\c\... path. cygpath -m yields a Windows path with forward slashes that docker accepts (the
# integration-PoV lesson). `cd "$LAB_DIR" && mvn` calls below are unaffected (they cd, not -f).
LAB_COMPOSE="$(native_path "$LAB_DIR/docker-compose.yml")"

echo "[GATE] db=$KOSHEI_DB_URL ; opcua=$KOSHEI_OPCUA_URL ; mqtt=$KOSHEI_MQTT_URL ; mode=$KOSHEI_APPLY_MODE ; lab=$LAB_DIR"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: schemas (registry + app) + fault_inject + reset"
docker compose exec -T postgres psql -U koshei -d koshei < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U koshei -d koshei < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE run_index, workflow_def, command_audit, fault_inject, source_rows, target_rows" >/dev/null
echo "[GATE] schemas ensured; state reset"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: start HiveMQ CE (broker) + wait for :1883"
docker compose -f "$LAB_COMPOSE" up -d hivemq-ce >/dev/null 2>&1 || fail "failed to start hivemq-ce"
wait_tcp localhost 1883 60 || { docker compose -f "$LAB_COMPOSE" logs --tail 40 hivemq-ce 2>/dev/null || true; fail "HiveMQ CE did not open :1883"; }
echo "[GATE] HiveMQ CE up on :1883"

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the embedded Milo OPC-UA sim (shared by koshei write + the bridge)"
: > "$SIM_LOG"
./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
wait_for_log "$SIM_LOG" "OPC-UA sim listening" 90 || { cat "$SIM_LOG" 2>/dev/null || true; fail "OPC-UA sim did not start"; }
echo "[GATE] OPC-UA sim listening on $KOSHEI_OPCUA_URL"

# Out-of-band raw writes to the sim to establish a clean canonical baseline before each saga.
perturb() {  # $1=nodeId $2=value
  ./gradlew -q --no-daemon :opcua:perturb -Pnode="$1" -Pvalue="$2" >>"$PERTURB_LOG" 2>&1 || fail "perturb $1=$2 failed (see $PERTURB_LOG)"
}
RPM_NODE="ns=2;s=Recipe/Rpm"
TEMP_NODE="ns=2;s=Recipe/Temp"
seed_canonical() { perturb "$RPM_NODE" 1500; perturb "$TEMP_NODE" 200; }
echo "[GATE] step 3: seed canonical baseline (Rpm=1500, Temp=200)"
seed_canonical

# ---------------------------------------------------------------------------
echo "[GATE] step 4: start the self-bridge (lab: NcmdOpcUaBridgeMain) + wait for [BRIDGE] ready"
: > "$BRIDGE_LOG"
( cd "$LAB_DIR" && mvn -q compile exec:java -Dexec.mainClass=dev.krillin.sparkplug.bridge.NcmdOpcUaBridgeMain ) >"$BRIDGE_LOG" 2>&1 &
BRIDGE_PID=$!
wait_for_log "$BRIDGE_LOG" "\[BRIDGE\] ready" 90 || { cat "$BRIDGE_LOG" 2>/dev/null || true; fail "self-bridge did not reach '[BRIDGE] ready'"; }
echo "[GATE] self-bridge ready (pid tree $BRIDGE_PID)"

# ---------------------------------------------------------------------------
echo "[GATE] step 5: build + start worker (KOSHEI_APPLY_MODE=ncmd) then API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null
: > "$WORKER_LOG"
KOSHEI_WORKER_NAME=r2ncmd-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
wait_for_log "$WORKER_LOG" "starting; polling" 60 || fail "worker did not reach 'starting; polling'"
echo "[GATE] worker up (ncmd apply mode)"

: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
wait_http_ok "$API/api/workflows" 60 || fail "API did not answer GET /api/workflows"
echo "[GATE] API up on 18090"

# ---------------------------------------------------------------------------
echo "[GATE] step 6: save ot-recipe-stage-activate@1.0.0 + wait for the LIVE worker to poll-bind it"
SAVE_CODE=$(curl -s -o "$WORK/save_osa.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-stage-activate.json")
[ "$SAVE_CODE" = "200" ] || fail "save ot-recipe-stage-activate@1.0.0 expected 200, got $SAVE_CODE ($(cat "$WORK/save_osa.json"))"
SLEEP_S=$(( (KOSHEI_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for poll-bind..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-stage-activate@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind the saga"
echo "[GATE] worker poll-bound ot-recipe-stage-activate@1.0.0"

# Helpers
aud() { psql_q "SELECT count(*) FROM command_audit WHERE node='$1' AND outcome='$2'"; }
wait_node() {  # $1=runId $2=node $3=state $4=tries
  local run="$1" node="$2" state="$3" tries="${4:-30}"
  for i in $(seq 1 "$tries"); do curl -fsS "$API/api/runs/$run/nodes" | grep -q "\"$node\":\"$state\"" && return 0; sleep 2; done
  return 1
}
reconcile() {  # $1=reconciliationId $2=proposalRef
  curl -fsS -X POST "$API/api/reconciliations" -H 'Content-Type: application/json' \
    -d "{\"reconciliationId\":\"$1\",\"nodes\":[\"recipe.rpmSetpoint\"],\"source\":\"r2-ncmd-gate\",\"proposalRef\":\"$2\"}"
}
apply_count() { grep -c "\[BRIDGE\] APPLY cmd=$1" "$BRIDGE_LOG" 2>/dev/null || echo 0; }
rogue() {  # $1=nodeId $2=value $3=dataType
  ( cd "$LAB_DIR" && mvn -q compile exec:java -Dexec.mainClass=dev.krillin.sparkplug.bridge.RogueNcmd \
      -Dexec.args="$1 $2 $3" ) >>"$ROGUE_LOG" 2>&1 || fail "rogue publish ($1 $2 $3) failed (see $ROGUE_LOG)"
}

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1: happy — reconcile -> approve -> saga completes over NCMD ====="
psql_q "TRUNCATE command_audit, fault_inject, source_rows, target_rows" >/dev/null
seed_canonical
RID1="ncmd-happy"
RESP=$(reconcile "$RID1" "t1") || fail "T1 POST /reconciliations failed"
echo "[GATE] T1 reconcile => $RESP"
echo "$RESP" | grep -q "\"runId\":\"$RID1\"" || fail "T1 expected runId=$RID1"
wait_node "$RID1" activateRecipe RUNNING || fail "T1 never reached the activate gate over NCMD"
[ "$(aud opcua.write WRITTEN)" -ge 1 ] || fail "T1 expected opcua.write WRITTEN>=1 before the gate, got $(aud opcua.write WRITTEN)"
curl -fsS -X POST "$API/api/runs/$RID1/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RID1?wait=true" | grep -q '"completed":true' || fail "T1 did not complete after approve"
[ "$(aud opcua.write WRITTEN)"  -ge 1 ] || fail "T1 expected opcua.write WRITTEN>=1, got $(aud opcua.write WRITTEN)"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1 expected opcua.call CONFIRMED=1, got $(aud opcua.call CONFIRMED)"
echo "[GATE] T1 OK: write+activate confirmed THROUGH the self-bridge (NCMD -> OPC-UA)"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1b: repeated activate — second reconcile confirms WITHOUT a manual reset (rearm proof) ====="
# Mirror T1's preamble EXACTLY. The gate never calls sim.reset() and seed_canonical only perturbs the
# SETPOINT nodes (Rpm/Temp), never the trigger/done nodes — so without the bridge de-assert the trigger
# stays asserted from T1 and this second activate hits the bridge baseline guard ("done already true").
psql_q "TRUNCATE command_audit, fault_inject, source_rows, target_rows" >/dev/null   # isolate: CONFIRMED=1 must hold
seed_canonical
RID1B="ncmd-happy-2"
RESP=$(reconcile "$RID1B" "t1b") || fail "T1b POST /reconciliations failed"
echo "$RESP" | grep -q "\"runId\":\"$RID1B\"" || fail "T1b expected runId=$RID1B"
wait_node "$RID1B" activateRecipe RUNNING || fail "T1b never reached the activate gate over NCMD"
curl -fsS -X POST "$API/api/runs/$RID1B/approve" >/dev/null || fail "T1b approve failed"
curl -fsS "$API/api/runs/$RID1B?wait=true" | grep -q '"completed":true' || fail "T1b did not complete after approve"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1b expected opcua.call CONFIRMED=1 (rearm without reset), got $(aud opcua.call CONFIRMED)"
echo "[GATE] T1b OK: second governed activate confirmed WITHOUT a manual done-bit reset (NCMD handshake rearm proven)"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T2: human veto — REJECT the gate -> reverse-NCMD RESTORE, no activate ====="
psql_q "TRUNCATE command_audit, fault_inject, source_rows, target_rows" >/dev/null
seed_canonical
RID2="ncmd-veto"
reconcile "$RID2" "t2" >/dev/null || fail "T2 reconcile failed"
wait_node "$RID2" activateRecipe RUNNING || fail "T2 never reached the activate gate"
[ "$(aud opcua.write WRITTEN)" -ge 1 ] || fail "T2 stageRecipe should have written (WRITTEN>=1) before the gate, got $(aud opcua.write WRITTEN)"
curl -fsS -X POST "$API/api/runs/$RID2/reject" >/dev/null || fail "T2 reject failed"
curl -fsS "$API/api/runs/$RID2?wait=true" | grep -q '"completed":false' || fail "T2 rejected run should compensate (completed=false)"
[ "$(aud opcua.write RESTORED)" -ge 1 ] || fail "T2 expected opcua.write RESTORED>=1 (reverse NCMD), got $(aud opcua.write RESTORED)"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T2 activate must NOT have fired, got opcua.call CONFIRMED=$(aud opcua.call CONFIRMED)"
echo "[GATE] T2 OK: veto compensated over the same NCMD channel (RESTORE); activate never fired"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T3: rogue — direct NCMD for a deny-by-default node -> bridge DENY, no APPLY ====="
SECRET_NODE="ns=2;s=Recipe/Secret"
rogue "$SECRET_NODE" 1.0 Double
sleep 3   # let the bridge process the inbound NCMD off its callback thread and log
grep -q "\[BRIDGE\] DENY cmd=$SECRET_NODE" "$BRIDGE_LOG" || fail "T3 bridge did not DENY the rogue deny-by-default node $SECRET_NODE"
if grep -q "\[BRIDGE\] APPLY cmd=$SECRET_NODE" "$BRIDGE_LOG"; then fail "T3 bridge APPLIED a deny-by-default node — edge authz breached"; fi
echo "[GATE] T3 OK: rogue deny-by-default node denied at the edge, never applied"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T4: defense-in-depth — rogue Rpm=9999 (allowed node, out of EURange) -> DENY above-max ====="
# The APPLY log line does NOT carry the value, and T1 legitimately APPLYed Rpm; so prove "not applied"
# by an APPLY-count delta captured immediately around THIS rogue publish (must not increase).
APPLY_BEFORE=$(apply_count "$RPM_NODE")
rogue "$RPM_NODE" 9999 Double
sleep 3
grep -qE "\[BRIDGE\] DENY cmd=$RPM_NODE .*above-max" "$BRIDGE_LOG" || fail "T4 bridge did not DENY Rpm=9999 as above-max"
APPLY_AFTER=$(apply_count "$RPM_NODE")
[ "$APPLY_AFTER" = "$APPLY_BEFORE" ] || fail "T4 an APPLY for $RPM_NODE appeared after the out-of-range rogue (before=$APPLY_BEFORE after=$APPLY_AFTER) — defense-in-depth breached"
echo "[GATE] T4 OK: out-of-EURange rogue denied at the edge (above-max), no new APPLY"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T5: fail-closed — bridge down -> koshei ncmd write times out -> no completion, no partial ====="
psql_q "TRUNCATE command_audit, fault_inject, source_rows, target_rows" >/dev/null
seed_canonical
kill_bridge
BRIDGE_PID=""   # killed; nothing to reap in cleanup
sleep 3
RID5="ncmd-failclosed"
reconcile "$RID5" "t5" >/dev/null || fail "T5 reconcile failed to start"
# The ncmd write publishes with no bridge to answer -> koshei's ~15s deadline expires -> the write
# block fails permanently. Wait (bounded) for the deterministic timeout/permanent-failure signal.
FC=0
for i in $(seq 1 40); do
  grep -qE "ncmd write timeout|PermanentBlockFailure" "$WORKER_LOG" 2>/dev/null && { FC=1; break; }
  sleep 2
done
[ "$FC" = "1" ] || fail "T5 worker did not log an ncmd write timeout / PermanentBlockFailure within the bound"
# The run must NOT complete successfully. Bounded wait so the gate can't hang if the run never terminates.
RESULT=$(curl -s --max-time 150 "$API/api/runs/$RID5?wait=true" || true)
echo "[GATE] T5 run result => ${RESULT:-<no terminal within bound>}"
echo "$RESULT" | grep -q '"completed":true' && fail "T5 run completed successfully with the bridge DOWN — not fail-closed"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T5 activate must NOT have confirmed (fail-closed), got opcua.call CONFIRMED=$(aud opcua.call CONFIRMED)"
echo "[GATE] T5 OK: bridge down -> ncmd write timed out -> no completion, activate never confirmed (fail-closed)"

echo ""
echo "[GATE] PASS run-r2-ncmd-gate.sh"
exit 0
