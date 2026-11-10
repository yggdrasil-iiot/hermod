#!/usr/bin/env bash
# R4 FSM PoC live gate (objective proof): state-aware transition governance. The SAME command
# (LoadRecipe) is ALLOWED when the equipment is Idle -> drives the real governed
# ot-recipe-stage-activate run on the embedded Milo OPC-UA sim, and DENIED fail-closed when the
# equipment is Aborted -> no run dispatched, equipment untouched. Governance decision is derived
# purely from the Git-canonical FSM spec (model/fsm/packml-line1.yaml) + the live equipment state
# node (line1.stateCurrent) via the pure yggdrasil.hermod.opcua.TransitionGovernor and the :opcua:fsmGovern
# gate-helper task (read + govern + print ALLOW/DENY; no dispatch — this bash gate dispatches).
#
# Bootstrap REPLICATES scripts/run-opcua-gate.sh verbatim (sim+worker+api bring-up, workflow save,
# poll-bind wait, inline helpers) since T1 reuses the exact same ot-recipe-stage-activate governed
# run. Conductor stays frozen; FSM is Temporal-only by decision. No new module (stays 12).
#
# Asserts (command_audit rows; node = the BLOCK id, not the step):
#   T1  ALLOW : LoadRecipe @ Idle(4.0)    -> ALLOW ot-recipe-stage-activate -> governed run drives
#               stage+activate on the real sim; 2x opcua.write WRITTEN, nodes DONE.
#   T2  DENY  : LoadRecipe @ Aborted(9.0) -> DENY (fail-closed) -> NO governed run dispatched;
#               0 opcua.write WRITTEN, 0 opcua.call CONFIRMED in the T2 window (equipment untouched).
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-fsm-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/lib/gate-common.sh   # for native_path; include-guarded, safe alongside inline psql_q

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1                                   # arm the worker's test-only fault_inject toggle
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:48400}"   # OpcUaApplyPort.default() reads this in the worker JVM

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/fsm-gate"
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
HERMOD_WORKER_NAME=fsm-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
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
FSM="$PWD/model/fsm/packml-line1.yaml"
STATE_NODE="ns=2;s=Line1/StateCurrent"

# govern <command> -> echoes the helper's single decision line (ALLOW <wf> | DENY <reason>).
# `|| true` so an absent decision line does not abort the assignment under `set -e` before the
# explicit assertion below can print its diagnostic.
govern() {
  { ./gradlew -q --no-daemon :opcua:fsmGovern -Pfsm="$(native_path "$FSM")" -Pcommand="$1" 2>/dev/null \
    | grep -E '^(ALLOW|DENY) ' | tail -1; } || true
}
# perturb the FSM state node out-of-band (simulate the PLC being in a given PackML state)
set_state() { ./gradlew -q --no-daemon :opcua:perturb -Pnode="$STATE_NODE" -Pvalue="$1" >/dev/null 2>&1; }

echo "[GATE] ===== T1: LoadRecipe @ Idle(4.0) -> ALLOW -> governed run drives stage+activate ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
set_state "4.0"
DEC="$(govern LoadRecipe)"
echo "[GATE] governor: $DEC"
echo "$DEC" | grep -q "^ALLOW ot-recipe-stage-activate$" || fail "T1 expected ALLOW ot-recipe-stage-activate, got: $DEC"
# ALLOW -> dispatch the governed run using run-opcua-gate.sh's proven start->wait-gate->approve->wait flow
RUN_T1="fsm-t1-$$"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T1\"}" >/dev/null || fail "T1 start failed"
wait_node "$RUN_T1" activateRecipe AWAITING_APPROVAL || fail "T1 never reached the activate gate (activateRecipe AWAITING_APPROVAL)"
curl -fsS -X POST "$API/api/runs/$RUN_T1/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RUN_T1?wait=true" | grep -q '"completed":true' || fail "T1 run did not complete"
curl -fsS "$API/api/runs/$RUN_T1/nodes" | grep -q '"stageRecipe":"DONE"'    || fail "T1 stageRecipe not DONE"
curl -fsS "$API/api/runs/$RUN_T1/nodes" | grep -q '"activateRecipe":"DONE"' || fail "T1 activateRecipe not DONE"
[ "$(aud opcua.write WRITTEN)"  = "2" ] || fail "T1 expected 2 opcua.write WRITTEN, got $(aud opcua.write WRITTEN)"
echo "[GATE] T1 OK: ALLOW -> governed stage+activate confirmed on the real OPC-UA sim"

echo "[GATE] ===== T2: LoadRecipe @ Aborted(9.0) -> DENY fail-closed -> NO governed run, equipment untouched ====="
psql_q "TRUNCATE command_audit" >/dev/null      # clear the T1 audit so the untouched-check below is affirmative
set_state "9.0"
DEC="$(govern LoadRecipe)"
echo "[GATE] governor: $DEC"
echo "$DEC" | grep -q "^DENY " || fail "T2 expected DENY, got: $DEC"
# fail-closed: the gate does NOT dispatch. Affirmative "equipment untouched" proof — no governed
# actuation was audited in the T2 window (SAME command as T1; only the state differs).
[ "$(aud opcua.write WRITTEN)"  = "0" ] || fail "T2 DENY must leave 0 opcua.write WRITTEN, got $(aud opcua.write WRITTEN)"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T2 DENY must leave 0 opcua.call CONFIRMED, got $(aud opcua.call CONFIRMED)"
echo "[GATE] T2 OK: DENY -> no governed run, no actuation (state-aware fail-closed)"

echo ""
echo "[GATE] PASS"
exit 0
