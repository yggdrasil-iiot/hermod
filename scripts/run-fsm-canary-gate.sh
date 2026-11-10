#!/usr/bin/env bash
# R4 Canary + Instant Rollback live gate (objective proof): VERSION-AWARE transition governance.
# The equipment is held at Idle(4.0) throughout and the SAME command (LoadRecipe) is issued every
# time — the VARIABLE is the active FSM-spec VERSION per unit. Governance is derived purely from the
# Git-canonical FSM spec that the control-plane pointer (fsm_deployment) currently makes active for a
# unit + the live equipment state node, via the pure koshei.opcua.TransitionGovernor and the
# :opcua:fsmGovern gate-helper. The pointer is flipped by the Git-native :app fsm CLI subcommand
# (deploy/rollback/active/resolve) — no PR, no restart.
#
#   T1  deploy v1  -> LoadRecipe@Idle ALLOW -> governed ot-recipe-stage-activate run drives the real
#                     stage+activate on the embedded Milo OPC-UA sim (2x opcua.write WRITTEN).
#   T2  deploy v2  -> SAME command+state now DENY (v2 removed loadRecipe) -> NO run, equipment
#                     untouched (0 opcua.write WRITTEN).
#   T3  rollback   -> instant flip back to v1 -> ALLOW resumes -> governed run completes again, WITHOUT
#                     a new PR (a ROLLBACK->v1 audit row is written).
#   T4  fail-closed: deploy of a NONCONFORMANT version is refused; the active pointer stays v1.
#   T5  canary subset: line1@v2 DENY while line2@v1 ALLOW simultaneously — same command+state, opposite
#                     decisions purely from the per-unit active version (subset rollout).
#
# Bootstrap REPLICATES scripts/run-fsm-gate.sh (sim+worker+api bring-up, workflow save, poll-bind
# wait, inline helpers) since T1/T3 reuse the exact same ot-recipe-stage-activate governed run.
# Conductor stays frozen; FSM is Temporal-only by decision. No new module (stays 12).
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-fsm-canary-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/lib/gate-common.sh   # for native_path; include-guarded, safe alongside inline psql_q

PLUGIN_DIR_SH="${KOSHEI_PLUGIN_DIR:-$(pwd)/registry-store}"
export KOSHEI_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export KOSHEI_DB_URL="${KOSHEI_DB_URL:-jdbc:postgresql://localhost:15432/koshei}"
export KOSHEI_DB_USER="${KOSHEI_DB_USER:-koshei}"
export KOSHEI_DB_PASS="${KOSHEI_DB_PASS:-koshei}"
export KOSHEI_WF_POLL_MS="${KOSHEI_WF_POLL_MS:-3000}"
export KOSHEI_FAULT_INJECT=1                                   # arm the worker's test-only fault_inject toggle
export KOSHEI_OPCUA_URL="${KOSHEI_OPCUA_URL:-opc.tcp://localhost:48400}"   # OpcUaApplyPort.default() reads this in the worker JVM

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/fsm-canary-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"
SIM_LOG="$WORK/sim.log"

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
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_sim_jvms || true; rm -f model/fsm/packml-line1.bad.yaml 2>/dev/null || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
  echo "--- sim log tail ---";    tail -20 "$SIM_LOG"    2>/dev/null || true
  exit 1
}

echo "[GATE] db = $KOSHEI_DB_URL ; opcua = $KOSHEI_OPCUA_URL ; poll = ${KOSHEI_WF_POLL_MS}ms ; fault-inject = on"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: ensure schemas (registry incl fsm_deployment* + app schema.sql for command_audit/source_rows/target_rows) + fault_inject + reset"
docker compose exec -T postgres psql -U koshei -d koshei < registry/src/main/resources/registry-schema.sql >/dev/null
# app schema.sql is NOT applied by run-scenario-gate.sh; we MUST apply it so command_audit (+ source_rows/
# target_rows) exist for the OPC-UA audit assertions.
docker compose exec -T postgres psql -U koshei -d koshei < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject, command_audit, fsm_deployment, fsm_deployment_audit" >/dev/null
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
echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start the worker (background, fault-inject armed + KOSHEI_OPCUA_URL) BEFORE composing; then start API"
: > "$WORKER_LOG"
KOSHEI_WORKER_NAME=canary-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
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
SLEEP_S=$(( (KOSHEI_WF_POLL_MS / 1000) + 6 ))
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
# run the :app fsm CLI subcommand; app stdout (println) flows to our stdout. Propagates exit code.
fsm_cli() { ./gradlew -q --console=plain --no-daemon :app:cli --args="fsm $*"; }
# resolve the active version's spec FILE for a unit, then govern a command against THAT file.
govern_active() {  # $1=unit $2=command
  local v; v="$(fsm_cli active "$1" 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" || true
  [ -n "$v" ] || { echo "DENY no-active-version"; return 0; }
  local f; f="$(fsm_cli resolve "$1" "$v" 2>/dev/null | grep -E '\.yaml$' | tail -1)" || true
  [ -n "$f" ] || { echo "DENY unresolved-$1-$v"; return 0; }
  ./gradlew -q --console=plain --no-daemon :opcua:fsmGovern -Pfsm="$(native_path "$f")" -Pcommand="$2" 2>/dev/null \
    | grep -E '^(ALLOW|DENY) ' | tail -1 || true
}
STATE_NODE_L1="ns=2;s=Line1/StateCurrent"
STATE_NODE_L2="ns=2;s=Line2/StateCurrent"
set_state_l1() { ./gradlew -q --no-daemon :opcua:perturb -Pnode="$STATE_NODE_L1" -Pvalue="$1" >/dev/null 2>&1; }
set_state_l2() { ./gradlew -q --no-daemon :opcua:perturb -Pnode="$STATE_NODE_L2" -Pvalue="$1" >/dev/null 2>&1; }
aud_audit() { psql_q "SELECT count(*) FROM fsm_deployment_audit WHERE unit='$1' AND action='$2' AND to_version='$3'"; }

echo "[GATE] ===== T1: deploy v1 -> ALLOW -> governed stage+activate ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
set_state_l1 "4.0"
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "T1 deploy v1 failed"
DEC="$(govern_active line1 LoadRecipe)"; echo "[GATE] governor(v1): $DEC"
echo "$DEC" | grep -q "^ALLOW ot-recipe-stage-activate$" || fail "T1 expected ALLOW, got: $DEC"
RUN_T1="canary-t1-$$"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T1\"}" >/dev/null || fail "T1 start failed"
wait_node "$RUN_T1" activateRecipe AWAITING_APPROVAL || fail "T1 never reached the activate gate"
curl -fsS -X POST "$API/api/runs/$RUN_T1/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RUN_T1?wait=true" | grep -q '"completed":true' || fail "T1 run did not complete"
[ "$(aud opcua.write WRITTEN)" = "2" ] || fail "T1 expected 2 opcua.write WRITTEN, got $(aud opcua.write WRITTEN)"
echo "[GATE] T1 OK"

echo "[GATE] ===== T2: deploy v2 -> DENY (same command+state, different version) -> no run ====="
psql_q "TRUNCATE command_audit" >/dev/null
fsm_cli deploy line1 v2 | grep -q "^deployed line1 -> v2" || fail "T2 deploy v2 failed"
[ "$(fsm_cli active line1 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" = "v2" ] || fail "T2 line1 should be active v2 before govern (airtight version-flip claim)"
DEC="$(govern_active line1 LoadRecipe)"; echo "[GATE] governor(v2): $DEC"
echo "$DEC" | grep -q "^DENY " || fail "T2 expected DENY, got: $DEC"
[ "$(aud opcua.write WRITTEN)" = "0" ] || fail "T2 DENY must leave 0 opcua.write WRITTEN, got $(aud opcua.write WRITTEN)"
echo "[GATE] T2 OK: version-flipped governance denies, equipment untouched"

echo "[GATE] ===== T3: instant rollback -> ALLOW resumes ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit" >/dev/null
seed_setpoints "recipe.rpmSetpoint=1500" "recipe.tempSetpoint=200"
fsm_cli rollback line1 | grep -q "^rolled back line1 -> v1" || fail "T3 rollback failed"
[ "$(aud_audit line1 ROLLBACK v1)" = "1" ] || fail "T3 expected a ROLLBACK->v1 audit row"
DEC="$(govern_active line1 LoadRecipe)"; echo "[GATE] governor(rolled back): $DEC"
echo "$DEC" | grep -q "^ALLOW ot-recipe-stage-activate$" || fail "T3 expected ALLOW after rollback, got: $DEC"
RUN_T3="canary-t3-$$"
curl -fsS -X POST "$RUN_URL" -H 'Content-Type: application/json' -d "{\"runId\":\"$RUN_T3\"}" >/dev/null || fail "T3 start failed"
wait_node "$RUN_T3" activateRecipe AWAITING_APPROVAL || fail "T3 never reached the activate gate"
curl -fsS -X POST "$API/api/runs/$RUN_T3/approve" >/dev/null || fail "T3 approve failed"
curl -fsS "$API/api/runs/$RUN_T3?wait=true" | grep -q '"completed":true' || fail "T3 run did not complete"
[ "$(aud opcua.write WRITTEN)" = "2" ] || fail "T3 expected 2 opcua.write WRITTEN after rollback, got $(aud opcua.write WRITTEN)"
echo "[GATE] T3 OK: instant rollback restored ALLOW without a new PR"

echo "[GATE] ===== T4: fail-closed deploy of a nonconformant version -> refused, pointer unchanged ====="
BAD="model/fsm/packml-line1.bad.yaml"
cat > "$BAD" <<'YAML'
name: packml-line1
unit: line1
version: bad
stateNode: line1.stateCurrent
states:
  - { id: Idle, code: 4 }
transitions:
  - { id: t, from: Nowhere, to: Idle, command: X, driver: field }
YAML
if fsm_cli deploy line1 bad >/dev/null 2>&1; then rm -f "$BAD"; fail "T4 deploy of nonconformant version should have been REFUSED"; fi
rm -f "$BAD"
[ "$(fsm_cli active line1 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" = "v1" ] || fail "T4 pointer must stay v1 after a refused deploy"
echo "[GATE] T4 OK: nonconformant version refused; pointer unchanged (v1)"

echo "[GATE] ===== T5: canary subset -> line1@v2 DENY while line2@v1 ALLOW, simultaneously ====="
set_state_l1 "4.0"; set_state_l2 "4.0"
fsm_cli deploy line2 v1 | grep -q "^deployed line2 -> v1" || fail "T5 deploy line2 v1 failed"
fsm_cli deploy line1 v2 | grep -q "^deployed line1 -> v2" || fail "T5 deploy line1 v2 failed"
[ "$(fsm_cli active line1 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" = "v2" ] || fail "T5 line1 should be v2"
[ "$(fsm_cli active line2 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" = "v1" ] || fail "T5 line2 should be v1 (untouched)"
DEC1="$(govern_active line1 LoadRecipe)"; echo "[GATE] line1(v2): $DEC1"
DEC2="$(govern_active line2 LoadRecipe)"; echo "[GATE] line2(v1): $DEC2"
echo "$DEC1" | grep -q "^DENY " || fail "T5 line1@v2 expected DENY, got: $DEC1"
echo "$DEC2" | grep -q "^ALLOW ot-recipe-stage-activate$" || fail "T5 line2@v1 expected ALLOW, got: $DEC2"
echo "[GATE] T5 OK: per-unit subset — same command+state, opposite decisions by per-unit active version"

echo ""
echo "[GATE] PASS"
exit 0
