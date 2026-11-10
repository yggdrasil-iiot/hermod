#!/usr/bin/env bash
# R4 governed Hold-on-drift live gate (objective proof): the "+correct" half of the detect->correct arc.
# On a detected field-transition DRIFT (an operator sees the equipment moved via a transition the FSM
# does not declare), the operator runs `fsm hold <unit>`; hermod asks the pure TransitionGovernor whether
# a hermod-driven SafeHold is declared from the LIVE state and, on ALLOW, records a corrective HOLD audit
# row and prints the workflow to dispatch. A human-gated governed saga (ot-safe-hold = db.read -> opcua.call)
# then issues the approved OPC-UA command on the real Milo sim; the equipment's transition to Held is the
# field/PLC response (represented here by :opcua:perturb, exactly as run-fsm-gate.sh / run-fsm-drift-gate.sh
# represent every equipment state). A follow-up drift-check then reads Held via a DECLARED transition ->
# drift resolved. hermod never writes the stateCurrent read-node. See design 2026-07-03.
#
# Bootstrap REPLICATES scripts/run-fsm-gate.sh (sim + worker + api bring-up, both schemas, workflow save,
# poll-bind wait, run/approve/confirm flow) since T3 dispatches a human-gated governed run just like the
# govern gate's T1. Conductor stays frozen; FSM govern is Temporal-only by decision. No new module (12).
#
# Asserts (drift_audit + command_audit rows via psql; stdout via `fsm ...`, grep-matched on SUBSTRINGS
# since OpcUaApplyPort.connect() prints a leading "[opcua] connecting to ..." line before verdicts):
#   baseline @ Held(11.0)                       -> `baseline line1 11`.
#   T1  DRIFT : Held(11) -> Execute(6) (bypass)  -> `DRIFT line1 11 -> 6`  (undeclared; equipment running).
#   T2  ALLOW : fsm hold @ Execute               -> `ALLOW ot-safe-hold` + HOLD audit(to=11); pointer stays 6.
#   T3  saga  : human-gated ot-safe-hold  -> safeHoldCall AWAITING_APPROVAL -> approve -> opcua.call CONFIRMED=1;
#               field responds -> set_state 11.0 (PLC reaches Held).
#   T4  OK    : drift-check @ Held(11)            -> `OK line1 6 -> 11` (Execute->Held declared); OK audit.
#   T5  DENY  : fsm hold @ unknown(99)            -> `DENY ...` fail-closed; 0 opcua.call CONFIRMED (untouched).
#
# Regressions (run-fsm-gate.sh / run-fsm-drift-gate.sh) are NOT chained inline (each boots its own sim on
# :48400 and would collide with this gate's still-running stack) -- run them separately.
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-fsm-hold-gate.sh   # expect: [GATE] PASS run-fsm-hold-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/lib/gate-common.sh   # for native_path; include-guarded, safe alongside inline psql_q

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:48400}"   # OpcUaApplyPort/FsmStateReader read this
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/fsm-hold-gate"
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

echo "[GATE] db = $HERMOD_DB_URL ; opcua = $HERMOD_OPCUA_URL ; poll = ${HERMOD_WF_POLL_MS}ms"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: ensure BOTH schemas (registry: drift_*/fsm_deployment*/run_index ; app: command_audit/source_rows/target_rows) + reset"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U hermod -d hermod < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE drift_observation, drift_audit, fsm_deployment, fsm_deployment_audit, command_audit, source_rows, target_rows, workflow_def, run_index, fault_inject" >/dev/null
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

echo "[GATE] step 2: start the worker (background, HERMOD_OPCUA_URL) BEFORE composing; then start API"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=hold-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
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
STATE_NODE="ns=2;s=Line1/StateCurrent"
fsm_cli() { ./gradlew -q --console=plain --no-daemon :app:cli --args="fsm $*"; }
set_state() { ./gradlew -q --no-daemon :opcua:perturb -Pnode="$STATE_NODE" -Pvalue="$1" >/dev/null 2>&1; }
drift_aud() { psql_q "SELECT count(*) FROM drift_audit WHERE unit='line1' AND verdict='$1' AND to_code=$2"; }
aud() { psql_q "SELECT count(*) FROM command_audit WHERE node='$1' AND outcome='$2'"; }

# wait until a node reaches a given state (RUNNING/DONE/...) in GET /runs/<id>/nodes
wait_node() {
  local run="$1" node="$2" state="$3" tries="${4:-30}"
  for i in $(seq 1 "$tries"); do
    if curl -fsS "$API/api/runs/$run/nodes" | grep -qE "\"$node\":\"$state\""; then return 0; fi
    sleep 2
  done
  return 1
}

echo "[GATE] step 3: deploy line1 v1 (active version) + save ot-safe-hold@1.0.0 + wait for the LIVE worker to poll-bind it"
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "prep: deploy line1 v1 failed"
SAVE_CODE=$(curl -s -o "$WORK/save.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-safe-hold.json")
echo "[GATE] save ot-safe-hold@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save.json")"
[ "$SAVE_CODE" = "200" ] || fail "save ot-safe-hold@1.0.0 expected 200, got $SAVE_CODE"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind ot-safe-hold@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-safe-hold@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind ot-safe-hold@1.0.0"
echo "[GATE] worker poll-bound ot-safe-hold@1.0.0 WITHOUT restart"
# nominal source_rows row (unused by opcua.call — it fires regardless; mirrors the govern gate's db.read shape)
psql_q "INSERT INTO source_rows(id,val) VALUES ('line1.interlock','1') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null

# ---------------------------------------------------------------------------
echo "[GATE] ===== baseline @ Held(11.0) ====="
set_state "11.0"
OUT=$(fsm_cli drift-check line1) || fail "baseline drift-check exited non-zero: $OUT"
echo "[GATE] drift-check: $OUT"
echo "$OUT" | grep -q "baseline line1 11" || fail "baseline expected 'baseline line1 11', got: $OUT"
echo "[GATE] baseline OK"

echo "[GATE] ===== T1: bypass Held(11) -> Execute(6) = DRIFT (undeclared) ====="
set_state "6.0"
OUT=$(fsm_cli drift-check line1) || fail "T1 drift-check exited non-zero: $OUT"
echo "[GATE] drift-check: $OUT"
echo "$OUT" | grep -q "DRIFT line1 11 -> 6" || fail "T1 expected 'DRIFT line1 11 -> 6', got: $OUT"
[ "$(drift_aud DRIFT 6)" -ge 1 ] || fail "T1 expected a DRIFT drift_audit row for to_code=6"
echo "[GATE] T1 OK: undeclared bypass detected (equipment running when it should be Held)"

echo "[GATE] ===== T2: fsm hold @ Execute -> govern SafeHold -> ALLOW + HOLD audit ====="
OUT=$(fsm_cli hold line1) || fail "T2 fsm hold exited non-zero: $OUT"
echo "[GATE] fsm hold: $OUT"
echo "$OUT" | grep -q "ALLOW ot-safe-hold" || fail "T2 expected 'ALLOW ot-safe-hold', got: $OUT"
[ "$(drift_aud HOLD 11)" -ge 1 ] || fail "T2 expected a HOLD drift_audit row for to_code=11"
PTR="$(psql_q "SELECT last_state_code FROM drift_observation WHERE unit='line1'")"
[ "$PTR" = "6" ] || fail "T2 observation pointer must stay 6 (drift-check owns it), got $PTR"
echo "[GATE] T2 OK: corrective SafeHold governed ALLOW; HOLD audit recorded; pointer untouched (6)"

echo "[GATE] ===== T3: human-gated ot-safe-hold saga issues the governed command; field responds ====="
psql_q "TRUNCATE command_audit" >/dev/null      # clear so the T3 CONFIRMED count + T5 untouched-check are affirmative
RUN_T3="hold-t3-$$"
curl -fsS -X POST "$API/api/workflows/ot-safe-hold/1.0.0/run" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T3\"}" >/dev/null || fail "T3 start failed"
# The IRREVERSIBLE human gate records AWAITING_APPROVAL while parked (SagaWorkflowImpl.kt:75, legibility fix
# 9d1fd34); "RUNNING" only appears briefly AFTER approval. Tolerate both, mirroring the scenario gate.
wait_node "$RUN_T3" safeHoldCall "(RUNNING|AWAITING_APPROVAL)" || fail "T3 never reached the human gate (safeHoldCall AWAITING_APPROVAL)"
curl -fsS -X POST "$API/api/runs/$RUN_T3/approve" >/dev/null || fail "T3 approve failed"
curl -fsS "$API/api/runs/$RUN_T3?wait=true" | grep -q '"completed":true' || fail "T3 run did not complete"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T3 expected 1 opcua.call CONFIRMED, got $(aud opcua.call CONFIRMED)"
set_state "11.0"                                # field/PLC responds to the approved safe-hold command -> Held
echo "[GATE] T3 OK: human gate parked+approved; governed opcua.call CONFIRMED; equipment reaches Held"

echo "[GATE] ===== T4: verify resolved -> drift-check @ Held(11) -> OK ====="
OUT=$(fsm_cli drift-check line1) || fail "T4 drift-check exited non-zero: $OUT"
echo "[GATE] drift-check: $OUT"
echo "$OUT" | grep -q "OK line1 6 -> 11" || fail "T4 expected 'OK line1 6 -> 11', got: $OUT"
[ "$(drift_aud OK 11)" -ge 1 ] || fail "T4 expected an OK drift_audit row for to_code=11"
echo "[GATE] T4 OK: equipment returned to Held via a DECLARED transition -> drift resolved"

echo "[GATE] ===== T5: fsm hold @ unknown(99) -> DENY fail-closed -> no dispatch, equipment untouched ====="
psql_q "TRUNCATE command_audit" >/dev/null
set_state "99.0"
OUT=$(fsm_cli hold line1) || fail "T5 fsm hold exited non-zero: $OUT"
echo "[GATE] fsm hold: $OUT"
echo "$OUT" | grep -q "^DENY " || fail "T5 expected DENY, got: $OUT"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T5 DENY must leave 0 opcua.call CONFIRMED, got $(aud opcua.call CONFIRMED)"
# loose-ends: a govern DENY now leaves a DENY drift_audit row (recordDenyAlarm), symmetric with ALLOW->HOLD.
[ "$(psql_q "SELECT count(*) FROM drift_audit WHERE unit='line1' AND verdict='DENY'")" -ge 1 ] || fail "T5 expected a DENY drift_audit row (recordDenyAlarm)"
echo "[GATE] T5 OK: unknown state -> DENY, no actuation (fail-closed) + DENY audit row recorded"

echo ""
echo "[GATE] PASS run-fsm-hold-gate.sh"
exit 0
