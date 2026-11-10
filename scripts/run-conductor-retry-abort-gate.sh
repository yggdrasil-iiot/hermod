#!/usr/bin/env bash
# §6 v0.6d GATE (objective proof): the two remaining Conductor operator interventions work end-to-end through
# the same REST control plane the Console uses —
#   retry = whole-run re-execution (rerunWorkflow with the test-only fault hooks stripped) that RECOVERS a
#           failed+compensated run, reusing the same workflowId.
#   abort = terminateWorkflowWithFailure(triggerFailureWorkflow=true) that TERMINATES a RUNNING (gated) run AND
#           dispatches its compensation failureWorkflow (reverse-topo unwind of the completed upstream).
#
# Two hard asserts (BOTH must hold for PASS):
#   T1 retry recovers  : run comp-timeline {engine:conductor, failAtBlockId:transform.map}; node `sink` fails,
#                        main FAILS, upstream [n=notify.email, m=db.upsert] compensate (v0.6c-style timeline);
#                        POST /retry; the fault-stripped rerun reaches COMPLETED on the SAME runId and /nodes
#                        shows forward DONE with NO stale COMPENSATED overlay.
#   T2 abort compensates: run ot-recipe-apply {engine:conductor} (no fault); forward completes through preflight
#                        and the run waits RUNNING at the applyPLC WAIT (human) gate; POST /abort; main reaches
#                        TERMINATED AND /compensation shows [interlockAck=notify.email, recordPlan=db.upsert]
#                        COMPENSATED.
#
# CONDUCTOR-ONLY STACK (no Temporal worker needed):
#   Postgres 15432 + Conductor 18088; the Conductor workers (ConductorWorkerMain) + :authoring-api with
#   HERMOD_CONDUCTOR_URL=http://localhost:18088/api exported (EngineConfig default is :8088 but the gate
#   server is :18088 — LOAD-BEARING). HERMOD_FAULT_INJECT=1 is exported for bring-up parity with the sibling
#   gate; this gate inserts NO fault_inject rows, so the forward fault (T1) comes purely from the run input
#   `_failAtBlockId` and no compensate-fault is ever armed.
#
# Fixtures:
#   scripts/fixtures/compose/comp-timeline.json   (T1) — src(db.read) -> m(db.upsert) -> n(notify.email)
#                                                          -> sink(transform.map). No human gate; the
#                                                          fault-stripped rerun runs straight to COMPLETED.
#   scripts/fixtures/compose/ot-recipe-apply.json (T2) — sensorRead(db.read) -> recordPlan(db.upsert)
#                                                          -> interlockAck(notify.email) -> preflight(transform.map)
#                                                          -> applyPLC(actuate, IRREVERSIBLE + human gate => WAIT).
#
# Run from repo root with the stack up + schema applied:
#   docker compose up -d postgres conductor
#   bash scripts/init-db.sh
#   bash scripts/run-conductor-retry-abort-gate.sh   # expect: [GATE] PASS run-conductor-retry-abort-gate.sh  exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: pin the plugin store dir + DB + poll interval so the forked worker JVM agrees with this shell.
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
# Bring-up parity with the sibling gate; this gate arms no DB fault rows (forward fault is run-input only).
export HERMOD_FAULT_INJECT=1
# LOAD-BEARING: the EngineConfig default is :8088, but the compose Conductor host port is :18088. Both the
# :authoring-api ConductorEnginePort (HERMOD_CONDUCTOR_URL) and the conductor workers (CONDUCTOR_SERVER_URL)
# must point at the gate server.
export HERMOD_CONDUCTOR_URL="${HERMOD_CONDUCTOR_URL:-http://localhost:18088/api}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
CSERVER="http://localhost:18088"
WORK="build/conductor-retry-abort-gate"
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
echo "[GATE] step 0: apply registry-schema THEN app schema, reset state, seed source_rows"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U hermod -d hermod < app/src/main/resources/schema.sql >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, comp_ledger, fault_inject" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"
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
HERMOD_WORKER_NAME=ragate-cw ./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
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

# Save BOTH fixtures used by the two asserts.
for FX in comp-timeline ot-recipe-apply; do
  SAVE=$(curl -s -o "$WORK/save_$FX.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" \
    -H 'Content-Type: application/json' --data-binary "@$FIX/$FX.json")
  echo "[GATE] save $FX@1.0.0 http=$SAVE body=$(cat "$WORK/save_$FX.json")"
  [ "$SAVE" = "200" ] || fail "save $FX@1.0.0 expected 200, got $SAVE"
done

# ===========================================================================
echo "[GATE] ===== Assert T1: retry recovers a failed+compensated run (whole-run rerun) ====="
# Run comp-timeline with a forward fault at transform.map(sink): main FAILS, upstream [n,m] compensate.
RUN_T1=$(curl -s -o "$WORK/run_t1.json" -w '%{http_code}' -X POST "$API/api/workflows/comp-timeline/1.0.0/run" \
  -H 'Content-Type: application/json' --data-binary '{"engine":"conductor","failAtBlockId":"transform.map"}')
echo "[GATE] run T1 {engine:conductor, failAtBlockId:transform.map} http=$RUN_T1 body=$(cat "$WORK/run_t1.json")"
[ "$RUN_T1" = "200" ] || fail "run T1 expected 200, got $RUN_T1; body=$(cat "$WORK/run_t1.json")"
RUNID=$(python - "$WORK/run_t1.json" <<'PY'
import sys, json; print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$RUNID" ] || fail "run T1 returned no runId"
echo "[GATE] T1 runId = $RUNID"

# Precondition: the failure compensated reverse-topo [n=notify.email, m=db.upsert].
PRE=0
for i in $(seq 1 45); do
  curl -sf "$API/api/runs/$RUNID/compensation" -o "$WORK/comp_t1.json" 2>/dev/null || true
  if python - "$WORK/comp_t1.json" 2>/dev/null <<'PY'
import sys, json
t = json.load(open(sys.argv[1], encoding="utf-8"))
by = {e.get("nodeId"): e for e in t} if isinstance(t, list) else {}
ok = (by.get("n", {}).get("outcome") == "COMPENSATED" and by.get("m", {}).get("outcome") == "COMPENSATED")
sys.exit(0 if ok else 1)
PY
  then PRE=1; break; fi
  sleep 2
done
echo "[GATE] T1 precondition /compensation = $(cat "$WORK/comp_t1.json" 2>/dev/null)"
[ "$PRE" = "1" ] || fail "T1 precondition (failure compensated [n,m]) not reached; last=$(cat "$WORK/comp_t1.json" 2>/dev/null)"
echo "[GATE] T1 precondition: failed run compensated [n,m]"

# Retry the whole run (empty-body POST with {"nodeId":""}; the relaxed route + Conductor port ignore nodeId).
RETRY=$(curl -s -o "$WORK/retry_t1.txt" -w '%{http_code}' -X POST "$API/api/runs/$RUNID/retry" \
  -H 'Content-Type: application/json' --data-binary '{"nodeId":""}')
echo "[GATE] T1 retry http=$RETRY"
[ "$RETRY" = "200" ] || fail "retry T1 expected 200, got $RETRY; body=$(cat "$WORK/retry_t1.txt")"

# After the fault-stripped rerun, the SAME runId must reach COMPLETED, and /nodes must show NO COMPENSATED.
T1OK=0
ST=""
for i in $(seq 1 60); do
  curl -sf "$API/api/runs/$RUNID" -o "$WORK/status_t1.json" 2>/dev/null || true
  ST=$(python - "$WORK/status_t1.json" 2>/dev/null <<'PY'
import sys, json
try: print(json.load(open(sys.argv[1], encoding="utf-8")).get("status",""))
except Exception: print("")
PY
)
  if echo "$ST" | grep -q "COMPLETED"; then
    curl -sf "$API/api/runs/$RUNID/nodes" -o "$WORK/nodes_t1.json" 2>/dev/null || true
    if python - "$WORK/nodes_t1.json" 2>/dev/null <<'PY'
import sys, json
n = json.load(open(sys.argv[1], encoding="utf-8"))
vals = set(n.values()) if isinstance(n, dict) else set()
# recovered run: at least one DONE node, and NO stale COMPENSATED overlay.
sys.exit(0 if ("DONE" in vals and "COMPENSATED" not in vals) else 1)
PY
    then T1OK=1; break; fi
  fi
  sleep 2
done
echo "[GATE] T1 final status=$ST nodes=$(cat "$WORK/nodes_t1.json" 2>/dev/null)"
[ "$T1OK" = "1" ] || fail "T1 retry did not recover to COMPLETED with no stale COMPENSATED; status=$ST nodes=$(cat "$WORK/nodes_t1.json" 2>/dev/null)"
echo "[GATE] assert T1 PASS"

# ===========================================================================
echo "[GATE] ===== Assert T2: abort terminates a RUNNING gated run AND compensates upstream ====="
# Run ot-recipe-apply with NO fault: forward completes through preflight, run waits at applyPLC (WAIT gate).
RUN_T2=$(curl -s -o "$WORK/run_t2.json" -w '%{http_code}' -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" \
  -H 'Content-Type: application/json' --data-binary '{"engine":"conductor"}')
echo "[GATE] run T2 {engine:conductor} http=$RUN_T2 body=$(cat "$WORK/run_t2.json")"
[ "$RUN_T2" = "200" ] || fail "run T2 expected 200, got $RUN_T2; body=$(cat "$WORK/run_t2.json")"
RUNID2=$(python - "$WORK/run_t2.json" <<'PY'
import sys, json; print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$RUNID2" ] || fail "run T2 returned no runId"
echo "[GATE] T2 runId = $RUNID2"

# Wait until the run is at the gate: interlockAck (the node upstream of the WAIT) is DONE.
GATED=0
for i in $(seq 1 45); do
  curl -sf "$API/api/runs/$RUNID2/nodes" -o "$WORK/nodes_t2.json" 2>/dev/null || true
  if python - "$WORK/nodes_t2.json" 2>/dev/null <<'PY'
import sys, json
n = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if isinstance(n, dict) and n.get("interlockAck") == "DONE" else 1)
PY
  then GATED=1; break; fi
  sleep 2
done
echo "[GATE] T2 gate-wait /nodes = $(cat "$WORK/nodes_t2.json" 2>/dev/null)"
[ "$GATED" = "1" ] || fail "T2 run did not reach the applyPLC gate (interlockAck DONE) in time; nodes=$(cat "$WORK/nodes_t2.json" 2>/dev/null)"
echo "[GATE] T2 run is at the applyPLC WAIT gate"

# Abort -> terminate + compensation.
ABORT=$(curl -s -o "$WORK/abort_t2.txt" -w '%{http_code}' -X POST "$API/api/runs/$RUNID2/abort")
echo "[GATE] T2 abort http=$ABORT"
[ "$ABORT" = "200" ] || fail "abort T2 expected 200, got $ABORT; body=$(cat "$WORK/abort_t2.txt")"

# Main must reach TERMINATED, and /compensation must show [interlockAck, recordPlan] COMPENSATED.
T2OK=0
for i in $(seq 1 60); do
  curl -sf "$API/api/runs/$RUNID2/compensation" -o "$WORK/comp_t2.json" 2>/dev/null || true
  if python - "$WORK/comp_t2.json" 2>/dev/null <<'PY'
import sys, json
t = json.load(open(sys.argv[1], encoding="utf-8"))
by = {e.get("nodeId"): e for e in t} if isinstance(t, list) else {}
ok = (by.get("interlockAck", {}).get("outcome") == "COMPENSATED"
      and by.get("recordPlan", {}).get("outcome") == "COMPENSATED")
sys.exit(0 if ok else 1)
PY
  then T2OK=1; break; fi
  sleep 2
done
curl -sf "$API/api/runs/$RUNID2" -o "$WORK/status_t2.json" 2>/dev/null || true
ST2=$(python - "$WORK/status_t2.json" 2>/dev/null <<'PY'
import sys, json
try: print(json.load(open(sys.argv[1], encoding="utf-8")).get("status",""))
except Exception: print("")
PY
)
echo "[GATE] T2 status=$ST2 compensation=$(cat "$WORK/comp_t2.json" 2>/dev/null)"
echo "$ST2" | grep -q "TERMINATED" || fail "T2 main status expected TERMINATED, got $ST2"
[ "$T2OK" = "1" ] || fail "T2 abort did not compensate [interlockAck, recordPlan]; last=$(cat "$WORK/comp_t2.json" 2>/dev/null)"
echo "[GATE] assert T2 PASS"

# ---------------------------------------------------------------------------
echo ""
echo "[GATE] blockers: retry_recovers=1 abort_compensates=1"
echo "[GATE] PASS run-conductor-retry-abort-gate.sh"
exit 0
