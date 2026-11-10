#!/usr/bin/env bash
# R1 OPC-UA adapter CAPSTONE GATE (objective proof): the ot-recipe-stage-activate saga driving a REAL
# embedded Milo OPC-UA endpoint, governed end-to-end (deny-by-default policy + EURange + reversible
# staged setpoints + irreversible human-gated activation), exercised on REAL Temporal.
#
# This is a NEW workflow (ot-recipe-stage-activate) distinct from ot-recipe-apply — the existing
# actuate-fake anchor used by run-scenario-gate.sh + demo GIFs is left 100% untouched. Conductor stays
# frozen; OPC-UA is Temporal-only by decision.
#
# Asserts (all OPC-UA outcomes are proven via command_audit rows; node = the BLOCK id, not the step):
#   T1  happy        : approve the activate gate → all nodes DONE; 2x opcua.write WRITTEN + 1x opcua.call CONFIRMED.
#   T2a forward-fail : fault_inject(transform.map) BEFORE staging → reverse-topo compensation (recordPlan
#                      COMPENSATED, target_rows cleaned); activate never fired (no opcua.call CONFIRMED).
#   T2b RESTORE      : clean run to the gate, then REJECT → stageRecipe (already WRITTEN) is unwound via
#                      opcua.write.compensate → 2x opcua.write RESTORED; activate never fired.
#   T3  EURange      : rpm=9999 (model high=3000) → stageRecipe fails with EURANGE_REJECT; no activate.
#   T4  deny         : node absent from command-policy.json → DENIED (deny-by-default); no activate.
#
# Bring-up cloned from run-scenario-gate.sh (env incl. HERMOD_FAULT_INJECT=1, psql_q, kill/cleanup,
# worker+API, save+poll-bind), PLUS: app schema.sql applied (command_audit/source_rows/target_rows),
# the standalone Milo sim started via :opcua:runSim, and HERMOD_OPCUA_URL exported into the worker JVM.
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-opcua-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1                                   # arm the worker's test-only fault_inject toggle
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:48400}"   # OpcUaApplyPort.default() reads this in the worker JVM
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/opcua-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"
SIM_LOG="$WORK/sim.log"

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

kill_worker_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.app.WorkerKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_api_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_sim_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.opcua.SimMainKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_sim_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
  echo "--- sim log tail ---";    tail -20 "$SIM_LOG"    2>/dev/null || true
  exit 1
}

echo "[GATE] db = $HERMOD_DB_URL ; opcua = $HERMOD_OPCUA_URL ; poll = ${HERMOD_WF_POLL_MS}ms ; fault-inject = on"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: ensure schemas (registry + app schema.sql for command_audit/source_rows/target_rows) + fault_inject + reset"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
# app schema.sql is NOT applied by run-scenario-gate.sh; we MUST apply it so command_audit (+ source_rows/
# target_rows) exist for the OPC-UA audit assertions.
docker compose exec -T postgres psql -U hermod -d hermod < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject, command_audit" >/dev/null
echo "[GATE] schemas ensured; state reset"

# ---------------------------------------------------------------------------
echo "[GATE] step 0.5: start the embedded Milo OPC-UA sim and wait for it to listen"
: > "$SIM_LOG"
./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
SIMUP=0
for i in $(seq 1 90); do
  if grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null; then SIMUP=1; break; fi
  sleep 2
done
[ "$SIMUP" = "1" ] || { echo "--- sim log ---"; cat "$SIM_LOG" 2>/dev/null || true; fail "OPC-UA sim did not start (no 'OPC-UA sim listening' within ~180s)"; }
echo "[GATE] OPC-UA sim listening on $HERMOD_OPCUA_URL"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start the worker (background, fault-inject armed + HERMOD_OPCUA_URL) BEFORE composing; then start API"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=opcua-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
WUP=0
for i in $(seq 1 60); do
  if grep -q "starting; polling" "$WORKER_LOG" 2>/dev/null; then WUP=1; break; fi
  sleep 2
done
[ "$WUP" = "1" ] || fail "worker did not reach 'starting; polling' within ~120s"
echo "[GATE] worker up"

: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0
for i in $(seq 1 60); do
  if curl -sf "$API/api/workflows" >/dev/null 2>&1; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || fail "API did not answer GET /api/workflows within ~120s"
echo "[GATE] API up on 18090"

# ---------------------------------------------------------------------------
echo "[GATE] step 3: save ot-recipe-stage-activate@1.0.0 + wait for the LIVE worker to poll-bind it (no restart)"
SAVE_CODE=$(curl -s -o "$WORK/save_osa.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-stage-activate.json")
echo "[GATE] save ot-recipe-stage-activate@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save_osa.json")"
[ "$SAVE_CODE" = "200" ] || fail "save ot-recipe-stage-activate@1.0.0 expected 200, got $SAVE_CODE"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind ot-recipe-stage-activate@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-stage-activate@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind ot-recipe-stage-activate@1.0.0"
echo "[GATE] worker poll-bound ot-recipe-stage-activate@1.0.0 WITHOUT restart"

RUN_URL="$API/api/workflows/ot-recipe-stage-activate/1.0.0/run"

# seed source_rows with recipe setpoints (id=logical node key, val=value); db.read -> {id,val} -> opcua.write
seed_setpoints() {
  psql_q "TRUNCATE source_rows" >/dev/null
  for kv in "$@"; do
    local id="${kv%%=*}"; local val="${kv#*=}"
    psql_q "INSERT INTO source_rows(id,val) VALUES ('$id','$val') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
  done
}

# wait until a node reaches a given state (RUNNING/DONE/COMPENSATED...) in GET /runs/<id>/nodes
wait_node() {
  local run="$1" node="$2" state="$3" tries="${4:-30}"
  for i in $(seq 1 "$tries"); do
    if curl -fsS "$API/api/runs/$run/nodes" | grep -q "\"$node\":\"$state\""; then return 0; fi
    sleep 2
  done
  return 1
}

aud() { psql_q "SELECT count(*) FROM command_audit WHERE node='$1' AND outcome='$2'"; }

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1: happy path — approve the activate gate → staged + activated on the real OPC-UA sim ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
RUN_T1="opcua-happy-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T1\"}" >/dev/null || fail "T1 start failed"
wait_node "$RUN_T1" activateRecipe RUNNING || fail "T1 never reached the activate gate (activateRecipe RUNNING)"
curl -fsS -X POST "$API/api/runs/$RUN_T1/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RUN_T1?wait=true" | grep -q '"completed":true' || fail "T1 did not complete after approve"
curl -fsS "$API/api/runs/$RUN_T1/nodes" | grep -q '"stageRecipe":"DONE"'    || fail "T1 stageRecipe not DONE"
curl -fsS "$API/api/runs/$RUN_T1/nodes" | grep -q '"activateRecipe":"DONE"' || fail "T1 activateRecipe not DONE"
[ "$(aud opcua.write WRITTEN)"  = "2" ] || fail "T1 expected 2 opcua.write WRITTEN rows, got $(aud opcua.write WRITTEN)"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1 expected 1 opcua.call CONFIRMED row, got $(aud opcua.call CONFIRMED)"
echo "[GATE] T1 OK: 2x opcua.write WRITTEN + 1x opcua.call CONFIRMED, all nodes DONE"
[ "$(psql_q "SELECT count(*) FROM command_audit WHERE run_id='$RUN_T1'")" -ge 1 ] || fail "T1 command_audit not keyed to the run id ($RUN_T1)"
[ "$(psql_q "SELECT count(*) FROM command_audit WHERE run_id='-'")" = "0" ] || fail "T1 command_audit still has coarse run_id='-'"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1b: repeated activate — second clean run confirms WITHOUT a manual reset (rearm proof) ====="
# Mirror T1's preamble EXACTLY: seed source rows + truncate the same tables. run-opcua-gate.sh runs under
# `set -euo pipefail` and uses HARDCODED-LITERAL run ids (no SFX var) — use a literal id, not $RUN_SFX.
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null   # isolate this run so CONFIRMED=1 holds
RUN_T1B="opcua-t1b-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T1B\"}" >/dev/null || fail "T1b start failed"
wait_node "$RUN_T1B" activateRecipe RUNNING || fail "T1b never reached the activate gate"
curl -fsS -X POST "$API/api/runs/$RUN_T1B/approve" >/dev/null || fail "T1b approve failed"
curl -fsS "$API/api/runs/$RUN_T1B?wait=true" | grep -q '"completed":true' || fail "T1b did not complete after approve"
curl -fsS "$API/api/runs/$RUN_T1B/nodes" | grep -q '"activateRecipe":"DONE"' || fail "T1b activateRecipe not DONE"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1b expected opcua.call CONFIRMED=1 (rearm without reset), got $(aud opcua.call CONFIRMED)"
echo "[GATE] T1b OK: second governed activate confirmed WITHOUT a manual done-bit reset (handshake rearm proven)"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T2a: forward-fail upstream (transform.map) BEFORE staging → reverse compensation, no activate ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
psql_q "INSERT INTO fault_inject(block_id) VALUES ('transform.map') ON CONFLICT DO NOTHING" >/dev/null
RUN_T2A="opcua-failforward-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T2A\"}" >/dev/null || fail "T2a start failed"
curl -fsS "$API/api/runs/$RUN_T2A?wait=true" | grep -q '"completed":false' || fail "T2a should have compensated"
TL=$(curl -fsS "$API/api/runs/$RUN_T2A/compensation")
echo "[GATE] T2a timeline => $TL"
echo "$TL" | grep -qE '"blockId":"db.upsert"[^}]*"outcome":"COMPENSATED"' || fail "T2a recordPlan (db.upsert) not COMPENSATED"
[ "$(psql_q "SELECT count(*) FROM target_rows")" = "0" ] || fail "T2a compensation did not clean target_rows"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T2a activate must NOT have fired (found opcua.call CONFIRMED)"
[ "$(aud opcua.write WRITTEN)"  = "0" ] || fail "T2a stageRecipe must NOT have written (found opcua.write WRITTEN)"
psql_q "TRUNCATE fault_inject" >/dev/null
echo "[GATE] T2a OK: reverse-topo rollback (recordPlan COMPENSATED, target cleaned), staging + activate never fired"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T2b: RESTORE of staged setpoints — clean run to the gate, then REJECT → opcua.write.compensate ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
RUN_T2B="opcua-restore-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T2B\"}" >/dev/null || fail "T2b start failed"
wait_node "$RUN_T2B" activateRecipe RUNNING || fail "T2b never reached the activate gate"
[ "$(aud opcua.write WRITTEN)" = "2" ] || fail "T2b stageRecipe should have written 2 setpoints before the gate, got $(aud opcua.write WRITTEN)"
curl -fsS -X POST "$API/api/runs/$RUN_T2B/reject" >/dev/null || fail "T2b reject failed"
curl -fsS "$API/api/runs/$RUN_T2B?wait=true" | grep -q '"completed":false' || fail "T2b rejected run should compensate"
TLB=$(curl -fsS "$API/api/runs/$RUN_T2B/compensation")
echo "[GATE] T2b timeline => $TLB"
echo "$TLB" | grep -qE '"blockId":"opcua.write"[^}]*"outcome":"COMPENSATED"' || fail "T2b stageRecipe (opcua.write) not COMPENSATED in timeline"
[ "$(aud opcua.write RESTORED)" = "2" ] || fail "T2b expected 2 opcua.write RESTORED rows, got $(aud opcua.write RESTORED)"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T2b activate must NOT have fired (rejected)"
echo "[GATE] T2b OK: rejection unwound stageRecipe → 2x opcua.write RESTORED, activate never fired"
[ "$(psql_q "SELECT count(*) FROM command_audit WHERE outcome='RESTORED' AND run_id='$RUN_T2B'")" -ge 1 ] || fail "T2b compensate audit not keyed to the run id ($RUN_T2B)"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T3: EURange-reject — rpm=9999 (model high=3000) → stageRecipe fails fail-closed ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=9999"
RUN_T3="opcua-eurange-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T3\"}" >/dev/null || fail "T3 start failed"
curl -fsS "$API/api/runs/$RUN_T3?wait=true" | grep -q '"completed":false' || fail "T3 should have failed/compensated"
[ "$(aud opcua.write EURANGE_REJECT)" -ge 1 ] || fail "T3 expected an opcua.write EURANGE_REJECT audit row"
[ "$(aud opcua.call CONFIRMED)" = "0" ]       || fail "T3 activate must NOT have fired"
echo "[GATE] T3 OK: out-of-EURange setpoint rejected (EURANGE_REJECT), activate never fired"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T4: deny-by-default — node absent from command-policy.json → DENIED ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.secretValve=1"
RUN_T4="opcua-deny-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T4\"}" >/dev/null || fail "T4 start failed"
curl -fsS "$API/api/runs/$RUN_T4?wait=true" | grep -q '"completed":false' || fail "T4 should have failed/compensated"
[ "$(aud opcua.write DENIED)" -ge 1 ] || fail "T4 expected an opcua.write DENIED audit row"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T4 activate must NOT have fired"
echo "[GATE] T4 OK: unauthorized node denied by deny-by-default policy (DENIED), activate never fired"

echo ""
echo "[GATE] PASS run-opcua-gate.sh"
exit 0
