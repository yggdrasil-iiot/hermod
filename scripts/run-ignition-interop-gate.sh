#!/usr/bin/env bash
# IGNITION INTEROP GATE (objective proof, NOT hermetic/CI): the ot-recipe-stage-activate saga driving a
# REAL Ignition OPC-UA server, governed end-to-end (deny-by-default policy + EURange + reversible staged
# setpoints + irreversible human-gated activation), exercised on REAL Temporal.
#
# Sibling of run-opcua-gate.sh (the embedded-Milo-sim R1 capstone gate), with these deltas:
#   - Ignition must ALREADY BE UP with the runbook project loaded (docs/ignition-interop-runbook.md)
#     before running this script — there is no embedded-sim startup step here.
#   - HERMOD_OPCUA_URL points at the real Ignition OPC-UA endpoint (fail-closed reachability probe
#     at step 0.5 — this gate refuses to silently skip or hang if Ignition is not reachable).
#   - HERMOD_OPCUA_MODEL points at the Ignition model variant (model/ot-site-ignition.yaml).
#   - The sim-only cases T1b (rearm-without-reset) and T2a (forward-fail compensation) are dropped;
#     T1, T2b, T3, T4 are kept verbatim.
#
# This is a NEW workflow (ot-recipe-stage-activate) distinct from ot-recipe-apply — the existing
# actuate-fake anchor used by run-scenario-gate.sh + demo GIFs is left 100% untouched. Conductor stays
# frozen; OPC-UA is Temporal-only by decision.
#
# Asserts (all OPC-UA outcomes are proven via command_audit rows; node = the BLOCK id, not the step):
#   T1  happy        : approve the activate gate → all nodes DONE; 2x opcua.write WRITTEN + 1x opcua.call CONFIRMED.
#   T2b RESTORE      : clean run to the gate, then REJECT → stageRecipe (already WRITTEN) is unwound via
#                      opcua.write.compensate → 2x opcua.write RESTORED; activate never fired.
#   T3  EURange      : rpm=9999 (model high=3000) → stageRecipe fails with EURANGE_REJECT; no activate.
#   T4  deny         : node absent from command-policy.json → DENIED (deny-by-default); no activate.
#
# Bring-up cloned from run-opcua-gate.sh (env incl. HERMOD_FAULT_INJECT=1, psql_q, kill/cleanup,
# worker+API, save+poll-bind), PLUS: app schema.sql applied (command_audit/source_rows/target_rows),
# and HERMOD_OPCUA_URL/HERMOD_OPCUA_MODEL exported into the worker JVM pointing at Ignition.
#
# Run from repo root with the stack up AND Ignition already up with the runbook project loaded:
#   docker compose up -d
#   HERMOD_OPCUA_URL="opc.tcp://localhost:62541" bash scripts/run-ignition-interop-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/lib/gate-common.sh"

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1                                   # arm the worker's test-only fault_inject toggle
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:62541}"   # Ignition OPC-UA server
export HERMOD_OPCUA_MODEL="$(native_path "$(pwd)/model/ot-site-ignition.yaml")"
export HERMOD_RECIPE_SETPOINTS="$(native_path "$(pwd)/model/recipe-setpoints-ignition.yaml")"   # canonical DESIRED values; nodeIds must match the site model (CanonicalSetpoints cross-validates at API startup)
# Ignition grants anonymous READ but denies WRITE (Bad_UserAccessDenied); authenticate as Ignition's
# built-in OPC-UA user (opcuauser/password — documented defaults, write-permitted). Override via env.
export HERMOD_OPCUA_USER="${HERMOD_OPCUA_USER:-opcuauser}"
export HERMOD_OPCUA_PASS="${HERMOD_OPCUA_PASS:-password}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/opcua-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"

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
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
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
# The runs below use fixed workflow ids; one left parked at the human gate by an aborted earlier run makes
# the run POST fail with WorkflowExecutionAlreadyStarted. Terminate them (a missing id is fine).
for wid in opcua-happy-1 opcua-restore-1 opcua-eurange-1 opcua-deny-1; do
  docker compose exec -T temporal temporal workflow terminate --address temporal:7233 --workflow-id "$wid" --reason ignition-gate-reset >/dev/null 2>&1 || true
done
echo "[GATE] schemas ensured; state reset"

# ---------------------------------------------------------------------------
echo "[GATE] step 0.5: verify the Ignition OPC-UA server is reachable (fail-closed, no silent skip)"
IGN_HP="${HERMOD_OPCUA_URL#opc.tcp://}"; IGN_HOST="${IGN_HP%%:*}"; IGN_PORT="${IGN_HP##*:}"; IGN_PORT="${IGN_PORT%%/*}"
wait_tcp "$IGN_HOST" "$IGN_PORT" 15 || fail "Ignition OPC-UA server not reachable at $HERMOD_OPCUA_URL — start Ignition + load the project first (docs/ignition-interop-runbook.md)"
echo "[GATE] Ignition OPC-UA server reachable at $HERMOD_OPCUA_URL"

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
echo "[GATE] ===== T1: happy path — approve the activate gate → staged + activated on the real Ignition OPC-UA server ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
RUN_T1="opcua-happy-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T1\"}" >/dev/null || fail "T1 start failed"
wait_node "$RUN_T1" activateRecipe AWAITING_APPROVAL || fail "T1 never reached the activate gate (activateRecipe AWAITING_APPROVAL)"
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
echo "[GATE] ===== T2b: RESTORE of staged setpoints — clean run to the gate, then REJECT → opcua.write.compensate ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
RUN_T2B="opcua-restore-1"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T2B\"}" >/dev/null || fail "T2b start failed"
wait_node "$RUN_T2B" activateRecipe AWAITING_APPROVAL || fail "T2b never reached the activate gate"
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
echo "[GATE] PASS run-ignition-interop-gate.sh"
exit 0
