#!/usr/bin/env bash
# R2 DELEGATION block CAPSTONE GATE (objective proof): the ot-quality-gated-recipe saga driving a REAL
# embedded Milo OPC-UA endpoint AND a REAL external HTTP scoring service (the embedded scoring sim),
# governed end-to-end (deny-by-default delegation policy + score threshold + reversible staged setpoints
# + irreversible human-gated activation), exercised on REAL Temporal.
#
# The governed delegate.score block calls the external scorer and FAIL-CLOSES the saga on policy denial,
# service error, or a sub-threshold score, driving reverse-topo compensation that RESTOREs the upstream
# opcua.write staged setpoints so activate never fires. R1 (:opcua / ot-recipe-stage-activate) and the
# actuate-fake anchor used by run-scenario-gate.sh + demo GIFs are left 100% untouched. Conductor stays
# frozen; delegation is Temporal-only by decision.
#
# Asserts (delegation outcomes proven via delegation_audit rows; OPC-UA outcomes via command_audit; node
# = the BLOCK id, not the step):
#   T1  happy  : nominal setpoint (300) scores 0.90 >= 0.80 -> delegation PASSED -> approve activate gate
#                -> all nodes DONE; 1x opcua.call CONFIRMED.
#   T2  reject : in-range but aggressive setpoint (2900) scores ~0.033 < 0.80 -> delegation REJECTED ->
#                reverse compensation RESTOREs the staged opcua.write; activate never fired.
#   T3  deny   : endpoint 'rogue-scorer' absent from the allowlist -> DENIED (deny-by-default, no HTTP
#                call) -> opcua.write RESTORED; activate never fired.
#   T4  unreach: kill the scoring sim JVM -> HttpDelegatePort transport failure -> delegation FAILED ->
#                opcua.write RESTORED; activate never fired.
#
# Bring-up cloned from run-opcua-gate.sh (psql_q, kill/cleanup, worker+API, save+poll-bind, app schema.sql
# for command_audit/delegation_audit/source_rows/target_rows, the standalone Milo sim via :opcua:runSim,
# KOSHEI_OPCUA_URL into the worker JVM), PLUS: the standalone embedded scoring sim started via
# :delegation:runSim and KOSHEI_DELEGATION_URL exported into the worker JVM. This gate needs NO fault_inject.
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-delegation-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${KOSHEI_PLUGIN_DIR:-$(pwd)/registry-store}"
export KOSHEI_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export KOSHEI_DB_URL="${KOSHEI_DB_URL:-jdbc:postgresql://localhost:15432/koshei}"
export KOSHEI_DB_USER="${KOSHEI_DB_USER:-koshei}"
export KOSHEI_DB_PASS="${KOSHEI_DB_PASS:-koshei}"
export KOSHEI_WF_POLL_MS="${KOSHEI_WF_POLL_MS:-3000}"
export KOSHEI_OPCUA_URL="${KOSHEI_OPCUA_URL:-opc.tcp://localhost:48400}"   # OpcUaApplyPort.default() reads this in the worker JVM

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/delegation-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"
SIM_LOG="$WORK/sim.log"
SCORING_SIM_LOG="$WORK/scoring-sim.log"

psql_q() { docker compose exec -T postgres psql -U koshei -d koshei -tAc "$1" | tr -d '[:space:]'; }

kill_worker_jvms() {
  { jps -l 2>/dev/null | grep "koshei.app.WorkerKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_api_jvms() {
  { jps -l 2>/dev/null | grep "koshei.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_sim_jvms() {
  { jps -l 2>/dev/null | grep "koshei.opcua.SimMainKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_scoring_sim_jvms() {
  { jps -l 2>/dev/null | grep "koshei.delegation.SimMainKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_sim_jvms || true; kill_scoring_sim_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---";      tail -40 "$WORKER_LOG"      2>/dev/null || true
  echo "--- api log tail ---";         tail -40 "$API_LOG"         2>/dev/null || true
  echo "--- sim log tail ---";         tail -20 "$SIM_LOG"         2>/dev/null || true
  echo "--- scoring sim log tail ---"; tail -20 "$SCORING_SIM_LOG" 2>/dev/null || true
  exit 1
}

echo "[GATE] db = $KOSHEI_DB_URL ; opcua = $KOSHEI_OPCUA_URL ; delegation = ${KOSHEI_DELEGATION_URL:-http://localhost:9099/analytics/score} ; poll = ${KOSHEI_WF_POLL_MS}ms"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: ensure schemas (registry + app schema.sql for command_audit/delegation_audit/source_rows/target_rows) + reset"
docker compose exec -T postgres psql -U koshei -d koshei < registry/src/main/resources/registry-schema.sql >/dev/null
# app schema.sql is NOT applied by run-scenario-gate.sh; we MUST apply it so command_audit + delegation_audit
# (+ source_rows/target_rows) exist for the OPC-UA + delegation audit assertions.
docker compose exec -T postgres psql -U koshei -d koshei < app/src/main/resources/schema.sql >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject, command_audit, delegation_audit" >/dev/null
# The runs below use fixed workflow ids; one left parked at the human gate by an aborted earlier run makes
# the run POST fail with WorkflowExecutionAlreadyStarted. Terminate them (a missing id is fine).
for wid in dlg-pass-1 dlg-reject-1 dlg-deny-1 dlg-unreach-1; do
  docker compose exec -T temporal temporal workflow terminate --address temporal:7233 --workflow-id "$wid" --reason delegation-gate-reset >/dev/null 2>&1 || true
done
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
echo "[GATE] OPC-UA sim listening on $KOSHEI_OPCUA_URL"

# ---------------------------------------------------------------------------
echo "[GATE] step 0.6: start the embedded scoring sim (:delegation:runSim) and wait for it to listen"
export KOSHEI_DELEGATION_URL="${KOSHEI_DELEGATION_URL:-http://localhost:9099/analytics/score}"  # OpcUa-style env into the worker
: > "$SCORING_SIM_LOG"
KOSHEI_DELEGATION_SIM_PORT=9099 ./gradlew -q --no-daemon :delegation:runSim >"$SCORING_SIM_LOG" 2>&1 &
SSUP=0
for i in $(seq 1 60); do grep -q "scoring sim listening" "$SCORING_SIM_LOG" 2>/dev/null && { SSUP=1; break; }; sleep 2; done
[ "$SSUP" = "1" ] || { cat "$SCORING_SIM_LOG" 2>/dev/null || true; fail "scoring sim did not start (no 'scoring sim listening')"; }
echo "[GATE] scoring sim listening on :9099"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start the worker (background, KOSHEI_OPCUA_URL + KOSHEI_DELEGATION_URL) BEFORE composing; then start API"
: > "$WORKER_LOG"
KOSHEI_WORKER_NAME=delegation-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
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
ddec() { psql_q "SELECT decision FROM delegation_audit WHERE endpoint_id='$1' ORDER BY at_millis DESC LIMIT 1"; }

# ---------------------------------------------------------------------------
echo "[GATE] step 3: save + poll-bind both workflows (ot-quality-gated-recipe + ...-denied) — no worker restart"
SLEEP_S=$(( (KOSHEI_WF_POLL_MS / 1000) + 6 ))
save_bind() {  # $1 = fixture path, $2 = workflow name
  local code
  code=$(curl -s -o "$WORK/save_$2.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" \
         -H 'Content-Type: application/json' --data-binary "@$1")
  [ "$code" = "200" ] || fail "save $2 expected 200, got $code (body=$(cat "$WORK/save_$2.json"))"
  sleep "$SLEEP_S"
  grep -q "\[worker\] bound workflow $2@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind $2@1.0.0"
}
save_bind "$FIX/ot-quality-gated-recipe.json"        "ot-quality-gated-recipe"
save_bind "$FIX/ot-quality-gated-recipe-denied.json" "ot-quality-gated-recipe-denied"
echo "[GATE] both workflows poll-bound WITHOUT restart"

OK_URL="$API/api/workflows/ot-quality-gated-recipe/1.0.0/run"
DENY_URL="$API/api/workflows/ot-quality-gated-recipe-denied/1.0.0/run"

# ---- T1: happy — nominal setpoint (300) scores 0.90 >= 0.80 -> PASS -> approve -> activate CONFIRMED ----
echo "[GATE] ===== T1: happy (score PASS -> activate) ====="
psql_q "TRUNCATE command_audit, delegation_audit, target_rows" >/dev/null
seed_setpoints "recipe.rpmSetpoint=300"
RUN_T1="dlg-pass-1"
curl -fsS -X POST "$OK_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T1\"}" >/dev/null || fail "T1 start failed"
wait_node "$RUN_T1" activateRecipe AWAITING_APPROVAL || fail "T1 never reached the activate gate (scoreQuality should PASS)"
[ "$(ddec quality-scorer)" = "PASSED" ] || fail "T1 expected delegation_audit PASSED, got '$(ddec quality-scorer)'"
curl -fsS -X POST "$API/api/runs/$RUN_T1/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RUN_T1?wait=true" | grep -q '"completed":true' || fail "T1 did not complete after approve"
curl -fsS "$API/api/runs/$RUN_T1/nodes" | grep -q '"scoreQuality":"DONE"' || fail "T1 scoreQuality not DONE"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1 expected opcua.call CONFIRMED, got $(aud opcua.call CONFIRMED)"
echo "[GATE] T1 OK: score PASSED, activate CONFIRMED"
[ "$(psql_q "SELECT count(*) FROM delegation_audit WHERE run_id='$RUN_T1'")" -ge 1 ] || fail "delegation_audit not keyed to the run id ($RUN_T1)"
[ "$(psql_q "SELECT count(*) FROM delegation_audit WHERE run_id='-'")" = "0" ] || fail "delegation_audit still has coarse run_id='-'"

# ---- T2: fail-closed gate — in-range but aggressive setpoint (2900) scores ~0.033 < 0.80 -> REJECT -> RESTORE ----
echo "[GATE] ===== T2: fail-closed gate (score REJECT -> RESTORE, no activate) ====="
psql_q "TRUNCATE command_audit, delegation_audit, target_rows" >/dev/null
seed_setpoints "recipe.rpmSetpoint=2900"
RUN_T2="dlg-reject-1"
curl -fsS -X POST "$OK_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T2\"}" >/dev/null || fail "T2 start failed"
curl -fsS "$API/api/runs/$RUN_T2?wait=true" | grep -q '"completed":false' || fail "T2 should fail closed"
[ "$(ddec quality-scorer)" = "REJECTED" ] || fail "T2 expected delegation_audit REJECTED, got '$(ddec quality-scorer)'"
[ "$(aud opcua.write RESTORED)" -ge 1 ] || fail "T2 stageRecipe not RESTORED (got $(aud opcua.write RESTORED))"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T2 activate must NOT fire"
echo "[GATE] T2 OK: score REJECTED -> stageRecipe RESTORED, activate never fired"

# ---- T3: deny-by-default — endpoint 'rogue-scorer' absent from the allowlist -> DENIED (no HTTP call) ----
echo "[GATE] ===== T3: deny-by-default (unlisted endpoint) ====="
psql_q "TRUNCATE command_audit, delegation_audit, target_rows" >/dev/null
seed_setpoints "recipe.rpmSetpoint=300"
RUN_T3="dlg-deny-1"
curl -fsS -X POST "$DENY_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T3\"}" >/dev/null || fail "T3 start failed"
curl -fsS "$API/api/runs/$RUN_T3?wait=true" | grep -q '"completed":false' || fail "T3 should fail closed"
[ "$(ddec rogue-scorer)" = "DENIED" ] || fail "T3 expected delegation_audit DENIED, got '$(ddec rogue-scorer)'"
[ "$(aud opcua.write RESTORED)" -ge 1 ] || fail "T3 stageRecipe not RESTORED"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T3 activate must NOT fire"
echo "[GATE] T3 OK: unlisted endpoint DENIED (no call), stageRecipe RESTORED, activate never fired"

# ---- T4: scorer unreachable — kill the sim JVM -> HttpDelegatePort fails -> FAILED -> RESTORE ----
echo "[GATE] ===== T4: scorer unreachable (fail closed) ====="
kill_scoring_sim_jvms
psql_q "TRUNCATE command_audit, delegation_audit, target_rows" >/dev/null
seed_setpoints "recipe.rpmSetpoint=300"
RUN_T4="dlg-unreach-1"
curl -fsS -X POST "$OK_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T4\"}" >/dev/null || fail "T4 start failed"
curl -fsS "$API/api/runs/$RUN_T4?wait=true" | grep -q '"completed":false' || fail "T4 should fail closed"
[ "$(ddec quality-scorer)" = "FAILED" ] || fail "T4 expected delegation_audit FAILED, got '$(ddec quality-scorer)'"
[ "$(aud opcua.write RESTORED)" -ge 1 ] || fail "T4 stageRecipe not RESTORED"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T4 activate must NOT fire"
echo "[GATE] T4 OK: scorer unreachable -> FAILED, stageRecipe RESTORED, activate never fired"

echo ""
echo "[GATE] PASS run-delegation-gate.sh"
exit 0
