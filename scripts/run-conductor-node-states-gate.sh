#!/usr/bin/env bash
# §6 v0.6b GATE (objective proof): per-node-state lighting for CONDUCTOR runs.
#
# v0.6b made GET /api/runs/{runId}/nodes return a REAL {nodeId: STATE} map for Conductor runs
# (STATE in PENDING/RUNNING/DONE/FAILED/COMPENSATED/COMP_FAILED), derived from live Conductor task state.
# This gate proves both the happy-path forward lighting (incl. the IRREVERSIBLE actuate human-gate parked
# at RUNNING, then DONE after approve) AND the compensation money-shot (the fault point FAILED, the prior
# compensable nodes COMPENSATED, and the irreversible actuate NEVER fired).
#
# CONDUCTOR-ONLY STACK (no Temporal worker needed):
#   Postgres 15432 + Conductor 18088; the Conductor workers (ConductorWorkerMain) + :authoring-api with
#   HERMOD_CONDUCTOR_URL=http://localhost:18088/api exported (EngineConfig default is :8088 but the gate
#   server is :18088 — LOAD-BEARING).
#
# Fixture: scripts/fixtures/compose/ot-recipe-apply.json — a linear OT recipe:
#   sensorRead(db.read) -> recordPlan(db.upsert, compensable) -> interlockAck(notify.email, compensable)
#   -> preflight(transform.map, the fault point) -> applyPLC(actuate, IRREVERSIBLE human-gate = WAIT task).
#
# Two hard asserts (BOTH must hold for PASS):
#   A happy lighting + gate + approve : run {engine:conductor}; /nodes shows sensorRead=DONE, recordPlan=DONE,
#                                       applyPLC=RUNNING (parked at actuate WAIT gate); approve -> applyPLC=DONE.
#   B failure money-shot              : run {engine:conductor, failAtBlockId:transform.map}; /nodes shows
#                                       preflight=FAILED, recordPlan=COMPENSATED, interlockAck=COMPENSATED,
#                                       and applyPLC != DONE (PENDING/absent — irreversible actuation never fired).
#
# Run from repo root with the stack up + schema applied:
#   docker compose up -d postgres conductor
#   bash scripts/init-db.sh
#   bash scripts/run-conductor-node-states-gate.sh   # expect: [GATE] PASS run-conductor-node-states-gate.sh  exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: pin the plugin store dir + DB + poll interval so the forked worker JVM agrees with this shell.
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
# LOAD-BEARING: the EngineConfig default is :8088, but the compose Conductor host port is :18088. Both the
# :authoring-api ConductorEnginePort (HERMOD_CONDUCTOR_URL) and the conductor workers (CONDUCTOR_SERVER_URL)
# must point at the gate server.
export HERMOD_CONDUCTOR_URL="${HERMOD_CONDUCTOR_URL:-http://localhost:18088/api}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
CSERVER="http://localhost:18088"
WORK="build/conductor-node-states-gate"
mkdir -p "$WORK"
CWORKER_LOG="$WORK/conductor-worker.log"
API_LOG="$WORK/api.log"

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

kill_conductor_worker_jvms() {
  { jps -l 2>/dev/null | grep "ConductorWorkerMainKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_api_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
cleanup() { kill_api_jvms || true; kill_conductor_worker_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- conductor worker log tail ---"; tail -40 "$CWORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---"; tail -40 "$API_LOG" 2>/dev/null || true
  exit 1
}

echo "[GATE] db = $HERMOD_DB_URL ; conductor = $HERMOD_CONDUCTOR_URL ; poll = ${HERMOD_WF_POLL_MS}ms"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: registry schema (incl. run_index.engine) + reset state + seed source_rows"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index" >/dev/null
# comp_ledger only exists once the registry/app schema has it; ignore if absent.
psql_q "TRUNCATE comp_ledger" >/dev/null 2>&1 || true
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"
# sanity: the engine column must exist (Chunk 1).
ENGINE_COL=$(psql_q "SELECT count(*) FROM information_schema.columns WHERE table_name='run_index' AND column_name='engine'")
[ "$ENGINE_COL" = "1" ] || fail "run_index.engine column missing (registry-schema not applied?)"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: wait for Conductor health, then build conductor-runtime + API"
HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
until curl -sf "$CSERVER/health" 2>/dev/null | grep -q '"healthy":true'; do
  [ "$(date +%s)" -lt "$HEALTH_DEADLINE" ] || fail "Conductor not healthy within 3m (is 'docker compose up -d conductor' running?)"
  sleep 3
done
echo "[GATE] Conductor healthy"

./gradlew -q --no-daemon :conductor-runtime:build -x test :authoring-api:build -x test >/dev/null

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the Conductor workers (poll the per-blockId task types)"
: > "$CWORKER_LOG"
HERMOD_WORKER_NAME=nsgate-cw ./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
CWUP=0
for i in $(seq 1 60); do
  if grep -q "conductor workers polling" "$CWORKER_LOG" 2>/dev/null; then CWUP=1; break; fi
  sleep 2
done
[ "$CWUP" = "1" ] || fail "conductor workers did not reach 'conductor workers polling' within ~120s"
echo "[GATE] conductor workers polling"

# ---------------------------------------------------------------------------
echo "[GATE] step 3: start :authoring-api (EngineRouter; HERMOD_CONDUCTOR_URL=$HERMOD_CONDUCTOR_URL)"
: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0
for i in $(seq 1 60); do
  if curl -sf "$API/api/workflows" >/dev/null 2>&1; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || fail "API did not answer GET /api/workflows within ~120s"
echo "[GATE] API up on 18090"

# Save the linear OT recipe fixture.
SAVE_R=$(curl -s -o "$WORK/save_recipe.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-apply.json")
echo "[GATE] save ot-recipe-apply@1.0.0 http=$SAVE_R body=$(cat "$WORK/save_recipe.json")"
[ "$SAVE_R" = "200" ] || fail "save ot-recipe-apply@1.0.0 expected 200, got $SAVE_R"

# ===========================================================================
echo "[GATE] ===== Assert A: happy lighting + actuate gate + approve -> applyPLC DONE ====="
RUN_A=$(curl -s -o "$WORK/run_happy.json" -w '%{http_code}' -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"engine":"conductor"}')
echo "[GATE] run happy {engine:conductor} http=$RUN_A body=$(cat "$WORK/run_happy.json")"
[ "$RUN_A" = "200" ] || fail "run happy (conductor) expected 200, got $RUN_A; body=$(cat "$WORK/run_happy.json")"
HAPPY_ID=$(python - "$WORK/run_happy.json" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$HAPPY_ID" ] || fail "run happy returned no runId"
echo "[GATE] happy conductor runId = $HAPPY_ID"

# Poll /nodes until forward lighting parks at the actuate WAIT gate (Conductor indexes async).
PARKED=0
for i in $(seq 1 30); do
  curl -sf "$API/api/runs/$HAPPY_ID/nodes" -o "$WORK/nodes_happy.json" 2>/dev/null || true
  if python - "$WORK/nodes_happy.json" 2>/dev/null <<'PY'
import sys, json
nodes = json.load(open(sys.argv[1], encoding="utf-8"))
ok = (nodes.get("sensorRead") == "DONE"
      and nodes.get("recordPlan") == "DONE"
      and nodes.get("applyPLC") in ("RUNNING", "AWAITING_APPROVAL"))
sys.exit(0 if ok else 1)
PY
  then PARKED=1; break; fi
  sleep 2
done
echo "[GATE] happy /nodes (parked) = $(cat "$WORK/nodes_happy.json" 2>/dev/null)"
[ "$PARKED" = "1" ] || fail "happy run /nodes did not reach sensorRead=DONE,recordPlan=DONE,applyPLC=RUNNING/AWAITING_APPROVAL within ~60s; last=$(cat "$WORK/nodes_happy.json" 2>/dev/null)"

# Approve — the WAIT task may lag before it's IN_PROGRESS (approve no-ops until then); retry approve and
# poll /nodes until applyPLC=DONE.
DONE_A=0
for i in $(seq 1 30); do
  curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/runs/$HAPPY_ID/approve" >/dev/null 2>&1 || true
  curl -sf "$API/api/runs/$HAPPY_ID/nodes" -o "$WORK/nodes_happy_after.json" 2>/dev/null || true
  if python - "$WORK/nodes_happy_after.json" 2>/dev/null <<'PY'
import sys, json
nodes = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if nodes.get("applyPLC") == "DONE" else 1)
PY
  then DONE_A=1; break; fi
  sleep 3
done
echo "[GATE] happy /nodes (after approve) = $(cat "$WORK/nodes_happy_after.json" 2>/dev/null)"
[ "$DONE_A" = "1" ] || fail "happy run applyPLC did not reach DONE after approve within ~90s; last=$(cat "$WORK/nodes_happy_after.json" 2>/dev/null)"
echo "[GATE] assert A PASS"

# ===========================================================================
echo "[GATE] ===== Assert B: failure money-shot — preflight FAILED, prior compensable COMPENSATED, actuate never fired ====="
RUN_B=$(curl -s -o "$WORK/run_fail.json" -w '%{http_code}' -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"engine":"conductor","failAtBlockId":"transform.map"}')
echo "[GATE] run fail {engine:conductor, failAtBlockId:transform.map} http=$RUN_B body=$(cat "$WORK/run_fail.json")"
[ "$RUN_B" = "200" ] || fail "run fail (conductor) expected 200, got $RUN_B; body=$(cat "$WORK/run_fail.json")"
FAIL_ID=$(python - "$WORK/run_fail.json" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$FAIL_ID" ] || fail "run fail returned no runId"
echo "[GATE] fail conductor runId = $FAIL_ID"

# Poll /nodes until the compensation overlay appears. The failureWorkflow must dispatch + index before the
# COMPENSATED states light up — give it a generous ~90s deadline.
MONEY=0
for i in $(seq 1 45); do
  curl -sf "$API/api/runs/$FAIL_ID/nodes" -o "$WORK/nodes_fail.json" 2>/dev/null || true
  if python - "$WORK/nodes_fail.json" 2>/dev/null <<'PY'
import sys, json
nodes = json.load(open(sys.argv[1], encoding="utf-8"))
ok = (nodes.get("preflight") == "FAILED"
      and nodes.get("recordPlan") == "COMPENSATED"
      and nodes.get("interlockAck") == "COMPENSATED"
      and nodes.get("applyPLC") != "DONE")  # PENDING or absent — irreversible actuation never fired
sys.exit(0 if ok else 1)
PY
  then MONEY=1; break; fi
  sleep 2
done
echo "[GATE] fail /nodes = $(cat "$WORK/nodes_fail.json" 2>/dev/null)"
[ "$MONEY" = "1" ] || fail "fail run /nodes did not reach preflight=FAILED,recordPlan=COMPENSATED,interlockAck=COMPENSATED,applyPLC!=DONE within ~90s; last=$(cat "$WORK/nodes_fail.json" 2>/dev/null)"
echo "[GATE] assert B PASS"

# ---------------------------------------------------------------------------
echo ""
echo "[GATE] blockers: happy_lighting_gate_approve=1 failure_money_shot=1"
echo "[GATE] PASS run-conductor-node-states-gate.sh"
exit 0
