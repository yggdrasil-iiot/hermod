#!/usr/bin/env bash
# R2 HEIMDALL + SPARKPLUG NCMD GATE (objective cross-repo proof): hermod's physical apply flows
# hermod -> Sparkplug NCMD -> HiveMQ CE -> Heimdall (Bifrost's command edge, bifrost `heimdall/`)
# -> OPC-UA -> the shared Milo sim, authorized INDEPENDENTLY at the edge, compensation over the same
# channel. Answers come back on Heimdall's `bifrost/<group>/RESULT/<edge>` topic. Selects Approach B
# behind the ApplyPort seam via HERMOD_APPLY_MODE=ncmd; R1 (direct) is untouched (run-opcua-gate.sh).
#
# Asserts:
#   T1 happy            : reconcile -> approve -> saga completes over NCMD; opcua.write WRITTEN>=1,
#                         opcua.call CONFIRMED==1 (write+activate confirmed THROUGH the bridge).
#   T1b repeated       : a SECOND reconcile->approve to the same line, NO manual reset -> opcua.call
#                         CONFIRMED==1 again (bridge de-assert rearmed the equipment; NCMD handshake).
#   T2 veto             : reconcile -> reach gate (write WRITTEN>=1) -> REJECT -> completed=false;
#                         opcua.write RESTORED>=1 (reverse NCMD RESTORE), opcua.call CONFIRMED==0.
#   T3 rogue            : publish an NCMD DIRECTLY to the broker (bypass hermod) for a deny-by-default
#                         node -> bridge DENY, no APPLY (edge authz independent of hermod's D4).
#   T4 defense-in-depth : rogue write of an ALLOWED node (Rpm) but out-of-EURange (9999) -> bridge
#                         DENY above-max, no new APPLY for that publish.
#   T5 fail-closed      : bridge down -> hermod ncmd write times out -> run does NOT complete, no
#                         partial (opcua.call CONFIRMED==0; worker logs an ncmd timeout / permanent fail).
#
# Run from the hermod repo root with the stack up + a bifrost checkout (Maven builds the Heimdall jar):
#   docker compose up -d
#   BIFROST_DIR="/path/to/bifrost" bash scripts/run-r2-ncmd-gate.sh
#   # expect: [GATE] PASS run-r2-ncmd-gate.sh ... exit 0
# The worker binds every bundled workflow at start, including demo-with-greet, so the io.example.greet
# plugin must already be published (run scripts/run-add-block-gate.sh once against the same DB first).
set -euo pipefail
cd "$(dirname "$0")/.."
# Shared gate helpers (native_path, psql_q, kill_jvms_by, wait_for_log, wait_http_ok, wait_tcp).
# Resolve relative to this script so the source works regardless of CWD.
source "$(dirname "$0")/lib/gate-common.sh"

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:48400}"
# ---- Approach B (NCMD) wire config: selects SparkplugNcmdApplyPort in the two :opcua blocks and
#      points it at HiveMQ CE / the Sparkplug identity Heimdall authorizes. Forwarded to the worker
#      JVM via app/build.gradle.kts forwardHermodEnv (every HERMOD_* var). Group/edge are the target
#      of the rules in bifrost's heimdall/registry/policy.json (Bifrost:Line1 / recipe-edge), which
#      this gate runs Heimdall with; Heimdall's own default edge (recipe-edge-cmd) has no rules there.
export HERMOD_APPLY_MODE="${HERMOD_APPLY_MODE:-ncmd}"
export HERMOD_MQTT_URL="${HERMOD_MQTT_URL:-tcp://localhost:1883}"
export HERMOD_SPB_GROUP="${HERMOD_SPB_GROUP:-Bifrost:Line1}"
export HERMOD_SPB_EDGE="${HERMOD_SPB_EDGE:-recipe-edge}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/r2-ncmd-gate"
mkdir -p "$WORK"
# Canonical DESIRED values for POST /reconciliations. Without one, or without the sibling manifest.json
# whose contentSha256 the API re-computes, it fails closed 409 canonical-unresolvable (since a01e5f9), so
# no run could start. Unless the caller points at a published canonical, stage the bundled
# model/recipe-setpoints.yaml with a local manifest (defRef = this checkout's HEAD).
if [ -z "${HERMOD_RECIPE_SETPOINTS:-}" ]; then
  CANON_DIR="$WORK/canonical"; mkdir -p "$CANON_DIR"
  cp model/recipe-setpoints.yaml "$CANON_DIR/recipe-setpoints.yaml"
  printf '{"kind":"recipe","ref":"recipe-setpoints","version":"gate","defRef":"%s","contentSha256":"%s","sourcePath":"model/recipe-setpoints.yaml","publishedAt":0}\n' \
    "$(git rev-parse HEAD)" "$(sha256sum "$CANON_DIR/recipe-setpoints.yaml" | cut -d' ' -f1)" > "$CANON_DIR/manifest.json"
  export HERMOD_RECIPE_SETPOINTS="$(native_path "$(pwd)/$CANON_DIR/recipe-setpoints.yaml")"
fi
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
# Heimdall runs as `java -jar bifrost-heimdall.jar`; `jps -lm` reports the JAR path for a -jar launch,
# not the main class, so match on the jar filename. `$!` of an MSYS-backgrounded native java does not
# reliably equal the Win32 PID, so the recorded-PID kill is best-effort only.
kill_bridge() {
  if [ -n "$BRIDGE_PID" ]; then taskkill //F //T //PID "$BRIDGE_PID" >/dev/null 2>&1 || true; fi
  { jps -lm 2>/dev/null | grep -i "bifrost-heimdall.jar" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done
}
cleanup() {
  kill_jvms_by "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true; kill_jvms_by "yggdrasil.hermod.app.WorkerKt" || true; kill_jvms_by "yggdrasil.hermod.opcua.SimMainKt" || true; kill_bridge || true
  [ -n "${BIFROST_COMPOSE:-}" ] && docker compose -f "$BIFROST_COMPOSE" stop hivemq-ce >/dev/null 2>&1 || true
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

# Cross-repo dependency: the bifrost checkout (heimdall/ module + its policy/conformance fixtures + the
# hivemq-ce compose service). Fail-closed if unset — NO silent skip of the cross-repo assertions.
BIFROST_DIR="${BIFROST_DIR:-}"
[ -n "$BIFROST_DIR" ] || fail "BIFROST_DIR not set — point it at the bifrost checkout (the dir containing pom.xml + heimdall/). No silent skip of the NCMD assertions."
[ -f "$BIFROST_DIR/pom.xml" ] || fail "no pom.xml under BIFROST_DIR='$BIFROST_DIR'"
[ -f "$BIFROST_DIR/heimdall/registry/policy.json" ] || fail "no heimdall/registry/policy.json under BIFROST_DIR='$BIFROST_DIR'"
[ -f "$BIFROST_DIR/docker-compose.yml" ] || fail "no docker-compose.yml under BIFROST_DIR='$BIFROST_DIR' (needs the hivemq-ce service)"
# docker compose is a NATIVE Windows binary: an MSYS /c/... path passed to `-f` becomes an invalid
# C:\c\... path. cygpath -m yields a Windows path with forward slashes that docker accepts (the
# integration-PoV lesson). The same holds for the native java that runs the Heimdall jar.
BIFROST_COMPOSE="$(native_path "$BIFROST_DIR/docker-compose.yml")"
HEIMDALL_JAR="$(native_path "$BIFROST_DIR/heimdall/target/bifrost-heimdall.jar")"

echo "[GATE] db=$HERMOD_DB_URL ; opcua=$HERMOD_OPCUA_URL ; mqtt=$HERMOD_MQTT_URL ; mode=$HERMOD_APPLY_MODE ; bifrost=$BIFROST_DIR"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: schemas (registry + app) + fault_inject + reset"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U hermod -d hermod < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE run_index, workflow_def, command_audit, fault_inject, source_rows, target_rows" >/dev/null
# The runs below use fixed workflow ids; one left parked at the human gate by an aborted earlier run makes
# POST /reconciliations fail with WorkflowExecutionAlreadyStarted. Terminate them (a missing id is fine).
for wid in ncmd-happy ncmd-happy-2 ncmd-veto ncmd-failclosed; do
  docker compose exec -T temporal temporal workflow terminate --address temporal:7233 --workflow-id "$wid" --reason r2-gate-reset >/dev/null 2>&1 || true
done
echo "[GATE] schemas ensured; state reset"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: start HiveMQ CE (broker) + wait for :1883"
docker compose -f "$BIFROST_COMPOSE" up -d hivemq-ce >/dev/null 2>&1 || fail "failed to start hivemq-ce"
wait_tcp localhost 1883 60 || { docker compose -f "$BIFROST_COMPOSE" logs --tail 40 hivemq-ce 2>/dev/null || true; fail "HiveMQ CE did not open :1883"; }
echo "[GATE] HiveMQ CE up on :1883"

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the embedded Milo OPC-UA sim (shared by hermod write + the bridge)"
: > "$SIM_LOG"
./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
wait_for_log "$SIM_LOG" "OPC-UA sim listening" 90 || { cat "$SIM_LOG" 2>/dev/null || true; fail "OPC-UA sim did not start"; }
echo "[GATE] OPC-UA sim listening on $HERMOD_OPCUA_URL"

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
echo "[GATE] step 4: build + start Heimdall (bifrost: bifrost-heimdall.jar) + wait for [BRIDGE] ready"
# Always (re)package so the gate never runs a stale jar against the checked-out heimdall sources.
( cd "$BIFROST_DIR" && mvn -q -pl heimdall -am -DskipTests package ) >"$WORK/heimdall-build.log" 2>&1 || { tail -40 "$WORK/heimdall-build.log"; fail "Heimdall build failed (see $WORK/heimdall-build.log)"; }
[ -f "$BIFROST_DIR/heimdall/target/bifrost-heimdall.jar" ] || fail "heimdall/target/bifrost-heimdall.jar missing after build"
kill_bridge   # mop up an orphan from a prior aborted run before claiming the same MQTT client id
rm -rf "$WORK/command-ledger"   # Heimdall keeps its bdSeq and command ledger here (required)
: > "$BRIDGE_LOG"
MQTT_URL="$HERMOD_MQTT_URL" OPCUA_URL="$HERMOD_OPCUA_URL" SPB_GROUP="$HERMOD_SPB_GROUP" SPB_EDGE="$HERMOD_SPB_EDGE" \
  POLICY_PATH="$(native_path "$BIFROST_DIR/heimdall/registry/policy.json")" \
  REGISTRY_PATH="$(native_path "$BIFROST_DIR/heimdall/registry")" \
  CONFORMANCE_PATH="$(native_path "$BIFROST_DIR/heimdall/registry/conformance/Line1-Mixer/1.0.0.json")" \
  COMMAND_LEDGER_PATH="$(native_path "$(pwd)/$WORK/command-ledger")" \
  java -jar "$HEIMDALL_JAR" >"$BRIDGE_LOG" 2>&1 &
BRIDGE_PID=$!
wait_for_log "$BRIDGE_LOG" "\[BRIDGE\] ready" 90 || { cat "$BRIDGE_LOG" 2>/dev/null || true; fail "Heimdall did not reach '[BRIDGE] ready'"; }
grep -q "\[BRIDGE\] enforcement = enforcing" "$BRIDGE_LOG" || fail "Heimdall is not enforcing — the deny assertions below would prove nothing"
echo "[GATE] Heimdall ready, enforcing (pid tree $BRIDGE_PID)"

# ---------------------------------------------------------------------------
echo "[GATE] step 5: build + start worker (HERMOD_APPLY_MODE=ncmd) then API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=r2ncmd-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
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
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
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
rogue() {  # $1=nodeId $2=value $3=dataType — Heimdall's own single-metric NCMD publisher, bypassing hermod
  MQTT_URL="$HERMOD_MQTT_URL" SPB_GROUP="$HERMOD_SPB_GROUP" SPB_EDGE="$HERMOD_SPB_EDGE" \
    java -cp "$HEIMDALL_JAR" yggdrasil.bifrost.heimdall.RogueNcmd "$1" "$2" "$3" >>"$ROGUE_LOG" 2>&1 || fail "rogue publish ($1 $2 $3) failed (see $ROGUE_LOG)"
}

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1: happy — reconcile -> approve -> saga completes over NCMD ====="
psql_q "TRUNCATE command_audit, fault_inject, source_rows, target_rows" >/dev/null
seed_canonical
RID1="ncmd-happy"
RESP=$(reconcile "$RID1" "t1") || fail "T1 POST /reconciliations failed"
echo "[GATE] T1 reconcile => $RESP"
echo "$RESP" | grep -q "\"runId\":\"$RID1\"" || fail "T1 expected runId=$RID1"
wait_node "$RID1" activateRecipe AWAITING_APPROVAL || fail "T1 never reached the activate gate over NCMD"
[ "$(aud opcua.write WRITTEN)" -ge 1 ] || fail "T1 expected opcua.write WRITTEN>=1 before the gate, got $(aud opcua.write WRITTEN)"
curl -fsS -X POST "$API/api/runs/$RID1/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RID1?wait=true" | grep -q '"completed":true' || fail "T1 did not complete after approve"
[ "$(aud opcua.write WRITTEN)"  -ge 1 ] || fail "T1 expected opcua.write WRITTEN>=1, got $(aud opcua.write WRITTEN)"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1 expected opcua.call CONFIRMED=1, got $(aud opcua.call CONFIRMED)"
echo "[GATE] T1 OK: write+activate confirmed THROUGH Heimdall (NCMD -> OPC-UA)"

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
wait_node "$RID1B" activateRecipe AWAITING_APPROVAL || fail "T1b never reached the activate gate over NCMD"
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
wait_node "$RID2" activateRecipe AWAITING_APPROVAL || fail "T2 never reached the activate gate"
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
echo "[GATE] ===== T5: fail-closed — bridge down -> hermod ncmd write times out -> no completion, no partial ====="
psql_q "TRUNCATE command_audit, fault_inject, source_rows, target_rows" >/dev/null
seed_canonical
kill_bridge
BRIDGE_PID=""   # killed; nothing to reap in cleanup
sleep 3
RID5="ncmd-failclosed"
reconcile "$RID5" "t5" >/dev/null || fail "T5 reconcile failed to start"
# The ncmd write publishes with no bridge to answer -> hermod's ~15s deadline expires -> the write
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
