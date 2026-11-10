#!/usr/bin/env bash
# v0.7 run-state DURABLE-ARCHIVE GATE (objective proof, BOTH engines): after a run reaches terminal and is
# reconciled, the control-plane Postgres holds the run's terminal status + final per-node lighting snapshot +
# compensation-timeline snapshot, and the API serves them — on Temporal AND Conductor.
#
# The crucial new property: the TEMPORAL compensation timeline is now DURABLE (run_comp_event). Before this
# track it was live-only (Temporal exposed it from the running workflow; once the engine forgot the run it
# returned []). Conductor's timeline was already durable in comp_ledger; here we prove it ALSO lands in the
# uniform run_comp_event snapshot and is served by the frozen (DB-first) branch.
#
# DETERMINISM: the background @Scheduled sweep is DISABLED (export HERMOD_RECONCILER_DISABLED=1) and reconcile
# is driven via the read-path endpoints (GET /api/runs, /runs/{id}/nodes, /runs/{id}/compensation), so the
# asserts never race the 10s timer. TERMINAL_GRACE is 30s: a run is "frozen" (served from DB) only AFTER the
# grace window elapses since completed_at AND its snapshots are present — failure runs (T2/T3/T5) sleep past it.
#
# Fixture: scripts/fixtures/compose/ot-recipe-apply.json — a linear OT recipe (5 nodes):
#   sensorRead(db.read) -> recordPlan(db.upsert, compensable) -> interlockAck(notify.email, compensable)
#   -> preflight(transform.map, the fault point) -> applyPLC(actuate, IRREVERSIBLE).
#   On failAtBlockId=transform.map the compensable steps already done unwind reverse-topo:
#     idx0=interlockAck(notify.email)=COMPENSATED, idx1=recordPlan(db.upsert)=COMPENSATED.
#
# FULL stack (Temporal + Conductor): Postgres 15432 + Temporal 7233 + Conductor 18088; the Temporal worker
# (:app:run), the Conductor workers (:conductor-runtime:run) and :authoring-api, all sharing this shell's
# HERMOD_* env. HERMOD_CONDUCTOR_URL/CONDUCTOR_SERVER_URL pin :18088 (EngineConfig default is :8088 — LOAD-BEARING).
#
# Run from repo root with the stack up + schema applied:
#   docker compose up -d postgres temporal conductor
#   bash scripts/init-db.sh
#   bash scripts/run-statepersist-gate.sh   # expect: [GATE] PASS run-statepersist-gate.sh  exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: pin the plugin store dir + DB + poll interval so the forked worker JVMs agree with this shell.
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
# LOAD-BEARING: the EngineConfig default is :8088 but the compose Conductor host port is :18088. Both the
# :authoring-api ConductorEnginePort (HERMOD_CONDUCTOR_URL) and the conductor workers (CONDUCTOR_SERVER_URL)
# must point at the gate server.
export HERMOD_CONDUCTOR_URL="${HERMOD_CONDUCTOR_URL:-http://localhost:18088/api}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"
# LOAD-BEARING: disable the background reconciler sweep so reconcile is driven ONLY by the read-path endpoints
# (deterministic asserts; no race with the 10s @Scheduled timer). Read inside the API JVM's RunReconciler.sweep().
export HERMOD_RECONCILER_DISABLED=1

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
CSERVER="http://localhost:18088"
WORK="build/statepersist-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
CWORKER_LOG="$WORK/conductor-worker.log"
API_LOG="$WORK/api.log"
GRACE_MS=30000   # backend TERMINAL_GRACE (RunReconciler.graceMs); failure runs must age past this to freeze.

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

kill_worker_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.app.WorkerKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
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
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_conductor_worker_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- temporal worker log tail ---";  tail -40 "$WORKER_LOG"  2>/dev/null || true
  echo "--- conductor worker log tail ---"; tail -40 "$CWORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";              tail -40 "$API_LOG"     2>/dev/null || true
  exit 1
}

echo "[GATE] db=$HERMOD_DB_URL ; conductor=$HERMOD_CONDUCTOR_URL ; poll=${HERMOD_WF_POLL_MS}ms ; reconciler-sweep=DISABLED"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: apply registry-schema THEN app schema, reset state, seed source_rows"
# Order matters: registry-schema first (run_index archive columns + run_node_state + run_comp_event), then
# app/schema.sql (source/target/comp_ledger/fault_inject). Apply schema BEFORE truncate so the tables exist.
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U hermod -d hermod < app/src/main/resources/schema.sql >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, comp_ledger, fault_inject, run_node_state, run_comp_event" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"
# sanity: the v0.7 archive column must exist (registry-schema applied).
FINAL_COL=$(psql_q "SELECT count(*) FROM information_schema.columns WHERE table_name='run_index' AND column_name='final_status'")
[ "$FINAL_COL" = "1" ] || fail "run_index.final_status column missing (registry-schema not applied?)"
echo "[GATE] run_index.final_status column present (archive schema applied)"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: wait for Temporal + Conductor health, then build app + conductor-runtime + API"
HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
# Conductor health
until curl -sf "$CSERVER/health" 2>/dev/null | grep -q '"healthy":true'; do
  [ "$(date +%s)" -lt "$HEALTH_DEADLINE" ] || fail "Conductor not healthy within 3m (is 'docker compose up -d conductor' running?)"
  sleep 3
done
echo "[GATE] Conductor healthy"
# Temporal health: the frontend grpc port 7233 must accept connections (worker will hard-fail otherwise).
until docker compose exec -T temporal tctl --address temporal:7233 cluster health >/dev/null 2>&1; do
  [ "$(date +%s)" -lt "$HEALTH_DEADLINE" ] || fail "Temporal not healthy within 3m (is 'docker compose up -d temporal' running?)"
  sleep 3
done
echo "[GATE] Temporal healthy"

./gradlew -q --no-daemon :app:build -x test :conductor-runtime:build -x test :authoring-api:build -x test >/dev/null

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the Temporal worker (:app:run), the Conductor workers (:conductor-runtime:run), and :authoring-api"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=spgate-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
WUP=0
for i in $(seq 1 60); do
  if grep -q "starting; polling" "$WORKER_LOG" 2>/dev/null; then WUP=1; break; fi
  sleep 2
done
[ "$WUP" = "1" ] || fail "temporal worker did not reach 'starting; polling' within ~120s"
echo "[GATE] temporal worker polling"

: > "$CWORKER_LOG"
HERMOD_WORKER_NAME=spgate-cw ./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
CWUP=0
for i in $(seq 1 60); do
  if grep -q "conductor workers polling" "$CWORKER_LOG" 2>/dev/null; then CWUP=1; break; fi
  sleep 2
done
[ "$CWUP" = "1" ] || fail "conductor workers did not reach 'conductor workers polling' within ~120s"
echo "[GATE] conductor workers polling"

: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0
for i in $(seq 1 60); do
  if curl -sf "$API/api/workflows" >/dev/null 2>&1; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || fail "API did not answer GET /api/workflows within ~120s"
echo "[GATE] API up on 18090"

# Save the linear OT recipe fixture + wait for BOTH worker families to poll-bind it.
SAVE_R=$(curl -s -o "$WORK/save_recipe.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-apply.json")
echo "[GATE] save ot-recipe-apply@1.0.0 http=$SAVE_R body=$(cat "$WORK/save_recipe.json")"
[ "$SAVE_R" = "200" ] || fail "save ot-recipe-apply@1.0.0 expected 200, got $SAVE_R"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for the Temporal worker to poll-bind ot-recipe-apply@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-apply@1.0.0" "$WORKER_LOG" || fail "temporal worker did not poll-bind ot-recipe-apply@1.0.0"
echo "[GATE] temporal worker poll-bound ot-recipe-apply@1.0.0"

# The recipe node count (for the all-DONE assert). Parse the fixture's steps[].
NODE_COUNT=$(python - "$FIX/ot-recipe-apply.json" <<'PY'
import sys, json
print(len(json.load(open(sys.argv[1], encoding="utf-8"))["steps"]))
PY
)
echo "[GATE] recipe node count = $NODE_COUNT"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Extract a JSON field from a saved file (python — robust vs grep on nested JSON).
json_field() { python - "$1" "$2" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8")).get(sys.argv[2], ""))
PY
}

# POST a run and echo the engine-effective runId (Conductor generates its own id; use the RETURNED runId).
start_run() { # $1=engine  $2=fail(true|false)  -> stdout: runId
  local engine="$1" fail="$2" body
  if [ "$fail" = "true" ]; then body="{\"engine\":\"$engine\",\"failAtBlockId\":\"transform.map\"}"
  else body="{\"engine\":\"$engine\"}"; fi
  local out="$WORK/run_${engine}_${fail}.json"
  local code
  code=$(curl -s -o "$out" -w '%{http_code}' -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" \
    -H 'Content-Type: application/json' --data-binary "$body")
  [ "$code" = "200" ] || { echo "[GATE] start_run($engine,$fail) http=$code body=$(cat "$out")" >&2; return 1; }
  json_field "$out" runId
}

# The OT recipe's terminal node applyPLC(actuate) has human.requireApprovalBefore=true (IRREVERSIBLE), so a
# HAPPY run PARKS at applyPLC (state RUNNING, all upstream DONE) and only reaches COMPLETED after an approve
# signal — on BOTH engines (cf. authoring-ui/e2e/*.spec.ts). Wait for the park, then POST /approve.
approve_gate() { # $1=runId  -> 0 once approved (after applyPLC is parked)
  local id="$1"
  for i in $(seq 1 60); do
    if curl -sf "$API/api/runs/$id/nodes" 2>/dev/null | grep -qE '"applyPLC":"(RUNNING|AWAITING_APPROVAL)"'; then
      curl -sf -X POST "$API/api/runs/$id/approve" -o /dev/null 2>/dev/null || true
      echo "[GATE] approved applyPLC gate for $id"
      return 0
    fi
    sleep 2
  done
  echo "[GATE] approve_gate($id): applyPLC never parked (RUNNING/AWAITING_APPROVAL) within ~120s" >&2
  return 1
}

# Poll GET /runs/{id} (non-wait) until the engine reports a terminal status (Temporal proto or Conductor short).
wait_terminal() { # $1=runId  -> 0 if terminal observed within deadline
  local id="$1"
  local out="$WORK/status_${id//[^A-Za-z0-9]/_}.json"
  for i in $(seq 1 90); do
    curl -sf "$API/api/runs/$id" -o "$out" 2>/dev/null || true
    if python - "$out" 2>/dev/null <<'PY'
import sys, json
TERM = {"COMPLETED","FAILED","TERMINATED","CANCELED","CANCELLED","TIMED_OUT"}
s = json.load(open(sys.argv[1], encoding="utf-8")).get("status","")
s = s.upper().removeprefix("WORKFLOW_EXECUTION_STATUS_")
sys.exit(0 if s in TERM else 1)
PY
    then return 0; fi
    sleep 2
  done
  echo "[GATE] last status for $id = $(cat "$out" 2>/dev/null)" >&2
  return 1
}

# Drive the lazy reconcile via the read-path endpoints, then poll the DB until final_status is set. For failure
# runs (wait_freeze=1) also wait until the grace window has elapsed since completed_at AND snapshots are present
# (so isFrozen → DB-first serving is active). Drives the endpoints repeatedly so the refresh-within-grace path
# catches Conductor's post-terminal compensation (it lands AFTER the workflow is terminal).
archive_run() { # $1=runId  $2=wait_freeze(0|1)
  local id="$1" wf="$2"
  local deadline=$(( $(date +%s) + 120 ))
  while :; do
    # read-path endpoints drive reconcile (statusOrUnknown -> lazy reconcile; nodes/compensation isFrozen path)
    curl -sf "$API/api/runs"                    -o /dev/null 2>/dev/null || true
    curl -sf "$API/api/runs/$id/nodes"          -o /dev/null 2>/dev/null || true
    curl -sf "$API/api/runs/$id/compensation"   -o /dev/null 2>/dev/null || true
    local fs ca elapsed_ok nodes_ok comp_ok
    fs=$(psql_q "SELECT coalesce(final_status,'') FROM run_index WHERE run_id='$id'")
    if [ -n "$fs" ]; then
      if [ "$wf" != "1" ]; then return 0; fi
      # failure run: require grace elapsed + snapshots present so the frozen (DB-first) branch is active.
      elapsed_ok=$(psql_q "SELECT CASE WHEN now() - completed_at > interval '${GRACE_MS} milliseconds' THEN 1 ELSE 0 END FROM run_index WHERE run_id='$id'")
      nodes_ok=$(psql_q "SELECT CASE WHEN count(*)>0 THEN 1 ELSE 0 END FROM run_node_state WHERE run_id='$id'")
      comp_ok=$(psql_q "SELECT CASE WHEN count(*)>0 THEN 1 ELSE 0 END FROM run_comp_event WHERE run_id='$id'")
      if [ "$elapsed_ok" = "1" ] && [ "$nodes_ok" = "1" ] && [ "$comp_ok" = "1" ]; then return 0; fi
    fi
    [ "$(date +%s)" -lt "$deadline" ] || { echo "[GATE] archive_run($id,wf=$wf) timed out: final_status='$fs'" >&2; return 1; }
    sleep 2
  done
}

# ===========================================================================
echo "[GATE] ===== Assert T1: Temporal happy -> DB final_status COMPLETED + comp_outcome NONE + all nodes DONE ====="
T1ID=$(start_run temporal false) || fail "start T1 (temporal happy) failed"
[ -n "$T1ID" ] || fail "T1 returned no runId"
echo "[GATE] T1 temporal runId = $T1ID"
approve_gate "$T1ID" || fail "T1 applyPLC gate not approved (never parked)"
wait_terminal "$T1ID" || fail "T1 did not reach terminal within ~180s"
archive_run "$T1ID" 0 || fail "T1 was not archived (final_status not set)"
T1_FS=$(psql_q "SELECT final_status FROM run_index WHERE run_id='$T1ID'")
T1_CO=$(psql_q "SELECT comp_outcome FROM run_index WHERE run_id='$T1ID'")
T1_DONE=$(psql_q "SELECT count(*) FROM run_node_state WHERE run_id='$T1ID' AND state='DONE'")
echo "[GATE] T1 final_status=$T1_FS comp_outcome=$T1_CO done_nodes=$T1_DONE/$NODE_COUNT"
case "$T1_FS" in *COMPLETED) ;; *) fail "T1 final_status='$T1_FS' not LIKE %COMPLETED" ;; esac
[ "$T1_CO" = "NONE" ]        || fail "T1 comp_outcome='$T1_CO' expected NONE"
[ "$T1_DONE" = "$NODE_COUNT" ] || fail "T1 DONE node count=$T1_DONE expected $NODE_COUNT (all DONE)"
echo "[GATE] assert T1 PASS"

# ===========================================================================
echo "[GATE] ===== Assert T2: Temporal failure -> DB COMPLETED(engine)+COMPENSATED outcome + DURABLE ordered comp timeline ====="
# ENGINE-STATUS SEMANTICS (load-bearing, verified vs SagaWorkflowImpl.compensate): on Temporal a saga that
# fails forward CATCHES the failure, unwinds compensation, and RETURNS NORMALLY (WorkflowOutput(completed=false)).
# The Temporal WORKFLOW therefore reports WORKFLOW_EXECUTION_STATUS_COMPLETED — the business failure is NOT in
# the engine status but in comp_outcome=COMPENSATED + the per-node lighting (preflight=FAILED, the compensable
# upstreams=COMPENSATED) + the durable timeline. (Conductor differs: its main run is FAILED/TERMINATED — see T5.)
# So the durable archive faithfully stores COMPLETED here; the DURABLE-FAILURE proof is comp_outcome + timeline.
T2ID=$(start_run temporal true) || fail "start T2 (temporal failure) failed"
[ -n "$T2ID" ] || fail "T2 returned no runId"
echo "[GATE] T2 temporal runId = $T2ID"
wait_terminal "$T2ID" || fail "T2 did not reach terminal within ~180s"
archive_run "$T2ID" 1 || fail "T2 was not archived+frozen (final_status/grace/snapshots)"
T2_FS=$(psql_q "SELECT final_status FROM run_index WHERE run_id='$T2ID'")
T2_CO=$(psql_q "SELECT comp_outcome FROM run_index WHERE run_id='$T2ID'")
echo "[GATE] T2 final_status=$T2_FS comp_outcome=$T2_CO"
# Temporal: the saga completes after compensating, so the engine status is COMPLETED (NOT FAILED).
case "$T2_FS" in *COMPLETED) ;; *) fail "T2 final_status='$T2_FS' not LIKE %COMPLETED (Temporal saga completes after compensating)" ;; esac
[ "$T2_CO" = "COMPENSATED" ] || fail "T2 comp_outcome='$T2_CO' expected COMPENSATED"
# The durable FAILURE proof: the faulted node (preflight/transform.map) is recorded FAILED in the node snapshot.
T2_NPF=$(psql_q "SELECT state FROM run_node_state WHERE run_id='$T2ID' AND node_id='preflight'")
echo "[GATE] T2 node preflight=$T2_NPF (the faulted node, durably captured)"
[ "$T2_NPF" = "FAILED" ] || fail "T2 node preflight='$T2_NPF' expected FAILED (failure not durably captured)"
# run_comp_event ordered: idx0=interlockAck/notify.email/COMPENSATED, idx1=recordPlan/db.upsert/COMPENSATED, at_millis>0.
T2_CE=$(psql_q "SELECT count(*) FROM run_comp_event WHERE run_id='$T2ID'")
[ "$T2_CE" = "2" ] || fail "T2 run_comp_event count=$T2_CE expected 2; rows=$(psql_q "SELECT idx||':'||node_id||':'||block_id||':'||outcome||':'||at_millis FROM run_comp_event WHERE run_id='$T2ID' ORDER BY idx")"
T2_IDX0=$(psql_q "SELECT node_id||'/'||block_id||'/'||outcome||'/'||(at_millis>0)::int FROM run_comp_event WHERE run_id='$T2ID' AND idx=0")
T2_IDX1=$(psql_q "SELECT node_id||'/'||block_id||'/'||outcome||'/'||(at_millis>0)::int FROM run_comp_event WHERE run_id='$T2ID' AND idx=1")
echo "[GATE] T2 comp idx0=$T2_IDX0 idx1=$T2_IDX1"
[ "$T2_IDX0" = "interlockAck/notify.email/COMPENSATED/1" ] || fail "T2 idx0='$T2_IDX0' expected interlockAck/notify.email/COMPENSATED/1 (at_millis>0)"
[ "$T2_IDX1" = "recordPlan/db.upsert/COMPENSATED/1" ]      || fail "T2 idx1='$T2_IDX1' expected recordPlan/db.upsert/COMPENSATED/1 (at_millis>0)"
# run_node_state shows interlockAck & recordPlan = COMPENSATED.
T2_NIA=$(psql_q "SELECT state FROM run_node_state WHERE run_id='$T2ID' AND node_id='interlockAck'")
T2_NRP=$(psql_q "SELECT state FROM run_node_state WHERE run_id='$T2ID' AND node_id='recordPlan'")
echo "[GATE] T2 node interlockAck=$T2_NIA recordPlan=$T2_NRP"
[ "$T2_NIA" = "COMPENSATED" ] || fail "T2 node interlockAck='$T2_NIA' expected COMPENSATED"
[ "$T2_NRP" = "COMPENSATED" ] || fail "T2 node recordPlan='$T2_NRP' expected COMPENSATED"
echo "[GATE] assert T2 PASS (Temporal compensation timeline + failure lighting are now DURABLE)"

# ===========================================================================
echo "[GATE] ===== Assert T3: aged served-from-DB (Temporal, reuse T1) — /nodes JSON == persisted run_node_state ====="
# T1 was happy (no grace wait in archive_run). Force the freeze: wait until grace has elapsed since T1's
# completed_at, then snapshot node states via a read-path hit, so isFrozen → /nodes serves the DB rows.
T1_AGE_DEADLINE=$(( $(date +%s) + 60 ))
until [ "$(psql_q "SELECT CASE WHEN now() - completed_at > interval '${GRACE_MS} milliseconds' THEN 1 ELSE 0 END FROM run_index WHERE run_id='$T1ID'")" = "1" ]; do
  [ "$(date +%s)" -lt "$T1_AGE_DEADLINE" ] || fail "T1 did not age past grace within 60s"
  sleep 2
done
# ensure the node snapshot is present (drive reconcile via read path once more; while not-yet-frozen it snapshots live)
curl -sf "$API/api/runs/$T1ID/nodes" -o /dev/null 2>/dev/null || true
T1_SNAP=$(psql_q "SELECT count(*) FROM run_node_state WHERE run_id='$T1ID'")
[ "$T1_SNAP" -ge "1" ] || fail "T1 has no run_node_state snapshot to serve from DB"
# Now /nodes must be served from the frozen (DB) branch. Assert the JSON equals the persisted rows key-for-key.
curl -sf "$API/api/runs/$T1ID/nodes" -o "$WORK/t3_nodes.json" 2>/dev/null || fail "T3 GET /nodes failed"
# Build the DB-side map as canonical json for comparison.
psql_q "SELECT json_object_agg(node_id, state)::text FROM run_node_state WHERE run_id='$T1ID'" > "$WORK/t3_db.json"
python - "$WORK/t3_nodes.json" "$WORK/t3_db.json" <<'PY' || fail "T3 /nodes JSON != persisted run_node_state (DB-first serving not active)"
import sys, json
api = json.load(open(sys.argv[1], encoding="utf-8"))
db  = json.load(open(sys.argv[2], encoding="utf-8"))
# both must be identical key-for-key (frozen branch reads the DB)
assert api == db, f"api={api} != db={db}"
print(f"[GATE] T3 /nodes == DB run_node_state ({len(api)} nodes): {api}")
PY
echo "[GATE] assert T3 PASS (aged run served from DB)"

# ===========================================================================
echo "[GATE] ===== Assert T4: Conductor happy -> DB final_status COMPLETED + comp_outcome NONE + all nodes DONE ====="
T4ID=$(start_run conductor false) || fail "start T4 (conductor happy) failed"
[ -n "$T4ID" ] || fail "T4 returned no runId"
echo "[GATE] T4 conductor runId = $T4ID"
approve_gate "$T4ID" || fail "T4 applyPLC gate not approved (never parked)"
wait_terminal "$T4ID" || fail "T4 did not reach terminal within ~180s"
archive_run "$T4ID" 0 || fail "T4 was not archived (final_status not set)"
T4_FS=$(psql_q "SELECT final_status FROM run_index WHERE run_id='$T4ID'")
T4_CO=$(psql_q "SELECT comp_outcome FROM run_index WHERE run_id='$T4ID'")
T4_DONE=$(psql_q "SELECT count(*) FROM run_node_state WHERE run_id='$T4ID' AND state='DONE'")
echo "[GATE] T4 final_status=$T4_FS comp_outcome=$T4_CO done_nodes=$T4_DONE/$NODE_COUNT"
case "$T4_FS" in *COMPLETED) ;; *) fail "T4 final_status='$T4_FS' not LIKE %COMPLETED" ;; esac
[ "$T4_CO" = "NONE" ]          || fail "T4 comp_outcome='$T4_CO' expected NONE"
[ "$T4_DONE" = "$NODE_COUNT" ] || fail "T4 DONE node count=$T4_DONE expected $NODE_COUNT (all DONE)"
echo "[GATE] assert T4 PASS"

# ===========================================================================
echo "[GATE] ===== Assert T5: Conductor failure -> DB FAILED/TERMINATED(engine)+COMPENSATED + DURABLE ordered comp timeline ====="
# Conductor compensation lands AFTER the workflow is terminal (failureWorkflow), so archive_run drives the
# read-path repeatedly within the grace window (refreshCompOutcome path) until run_comp_event is populated.
T5ID=$(start_run conductor true) || fail "start T5 (conductor failure) failed"
[ -n "$T5ID" ] || fail "T5 returned no runId"
echo "[GATE] T5 conductor runId = $T5ID"
wait_terminal "$T5ID" || fail "T5 did not reach terminal within ~180s"
# Before freezing, give Conductor's failureWorkflow time to compensate + index (poll comp_ledger), so the
# snapshot captured during the grace window is complete. Then let archive_run freeze it.
echo "[GATE] T5 waiting for Conductor failureWorkflow to compensate (comp_ledger idx)..."
CL_OK=0
for i in $(seq 1 60); do
  curl -sf "$API/api/runs/$T5ID/compensation" -o /dev/null 2>/dev/null || true   # drive refreshCompOutcome
  CL=$(psql_q "SELECT count(*) FROM comp_ledger WHERE workflow_id='$T5ID' AND outcome='COMPENSATED'")
  if [ "$CL" -ge "2" ]; then CL_OK=1; break; fi
  sleep 2
done
[ "$CL_OK" = "1" ] || fail "T5 Conductor compensation did not reach 2 COMPENSATED in comp_ledger within ~120s; have=$(psql_q "SELECT count(*) FROM comp_ledger WHERE workflow_id='$T5ID'")"
archive_run "$T5ID" 1 || fail "T5 was not archived+frozen (final_status/grace/snapshots)"
T5_FS=$(psql_q "SELECT final_status FROM run_index WHERE run_id='$T5ID'")
T5_CO=$(psql_q "SELECT comp_outcome FROM run_index WHERE run_id='$T5ID'")
echo "[GATE] T5 final_status=$T5_FS comp_outcome=$T5_CO"
# Conductor (unlike Temporal): a failed saga's MAIN run is engine-terminal FAILED or TERMINATED (the
# failureWorkflow compensates out-of-band). Accept either; the business proof is comp_outcome + the timeline.
case "$T5_FS" in *FAILED|*TERMINATED) ;; *) fail "T5 final_status='$T5_FS' not LIKE %FAILED or %TERMINATED" ;; esac
[ "$T5_CO" = "COMPENSATED" ] || fail "T5 comp_outcome='$T5_CO' expected COMPENSATED"
T5_CE=$(psql_q "SELECT count(*) FROM run_comp_event WHERE run_id='$T5ID'")
[ "$T5_CE" = "2" ] || fail "T5 run_comp_event count=$T5_CE expected 2; rows=$(psql_q "SELECT idx||':'||node_id||':'||block_id||':'||outcome||':'||at_millis FROM run_comp_event WHERE run_id='$T5ID' ORDER BY idx")"
T5_IDX0=$(psql_q "SELECT node_id||'/'||block_id||'/'||outcome||'/'||(at_millis>0)::int FROM run_comp_event WHERE run_id='$T5ID' AND idx=0")
T5_IDX1=$(psql_q "SELECT node_id||'/'||block_id||'/'||outcome||'/'||(at_millis>0)::int FROM run_comp_event WHERE run_id='$T5ID' AND idx=1")
echo "[GATE] T5 comp idx0=$T5_IDX0 idx1=$T5_IDX1"
[ "$T5_IDX0" = "interlockAck/notify.email/COMPENSATED/1" ] || fail "T5 idx0='$T5_IDX0' expected interlockAck/notify.email/COMPENSATED/1 (at_millis>0)"
[ "$T5_IDX1" = "recordPlan/db.upsert/COMPENSATED/1" ]      || fail "T5 idx1='$T5_IDX1' expected recordPlan/db.upsert/COMPENSATED/1 (at_millis>0)"
# served by the frozen branch: GET /compensation JSON must equal the persisted run_comp_event rows.
curl -sf "$API/api/runs/$T5ID/compensation" -o "$WORK/t5_comp.json" 2>/dev/null || fail "T5 GET /compensation failed"
python - "$WORK/t5_comp.json" <<'PY' || fail "T5 /compensation (frozen branch) not the ordered durable snapshot"
import sys, json
t = json.load(open(sys.argv[1], encoding="utf-8"))
assert isinstance(t, list) and len(t) == 2, f"len!=2: {t}"
assert t[0]["index"]==0 and t[0]["nodeId"]=="interlockAck" and t[0]["blockId"]=="notify.email" and t[0]["outcome"]=="COMPENSATED" and t[0]["atMillis"]>0, t
assert t[1]["index"]==1 and t[1]["nodeId"]=="recordPlan"  and t[1]["blockId"]=="db.upsert"   and t[1]["outcome"]=="COMPENSATED" and t[1]["atMillis"]>0, t
print(f"[GATE] T5 /compensation served from DB snapshot: {t}")
PY
echo "[GATE] assert T5 PASS (Conductor timeline also durable in run_comp_event + served from DB)"

# ===========================================================================
echo "[GATE] ===== Assert T6: Conductor retry un-archives then re-archives COMPLETED (archive must not mask a re-run) ====="
# v0.6d Conductor whole-run retry re-runs reusing the SAME workflowId. The durable archive writes final_status
# WRITE-ONCE on the first (failed) terminal; without clearArchive on /retry that stale FAILED would mask the
# re-run forever (the operator's approve gate never renders). Prove: fail -> archived FAILED -> POST /retry ->
# final_status goes back to NULL (un-archived) -> the re-run recovers to the applyPLC gate -> approve ->
# COMPLETED -> re-archived COMPLETED + comp_outcome NONE + all nodes DONE.
T6ID=$(start_run conductor true) || fail "start T6 (conductor failure) failed"
[ -n "$T6ID" ] || fail "T6 returned no runId"
echo "[GATE] T6 conductor runId = $T6ID"
wait_terminal "$T6ID" || fail "T6 did not reach terminal (failed) within ~180s"
archive_run "$T6ID" 1 || fail "T6 was not archived+frozen as the failed attempt"
T6_FS0=$(psql_q "SELECT final_status FROM run_index WHERE run_id='$T6ID'")
echo "[GATE] T6 archived (failed attempt) final_status=$T6_FS0"
case "$T6_FS0" in *FAILED|*TERMINATED) ;; *) fail "T6 pre-retry final_status='$T6_FS0' not LIKE %FAILED or %TERMINATED" ;; esac
# Retry: the endpoint signals the rerun AND clears the archive.
RC=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/runs/$T6ID/retry")
[ "$RC" = "200" ] || fail "T6 POST /retry expected 200, got $RC"
echo "[GATE] T6 POST /retry http=$RC"
# final_status must return to NULL (un-archived) so the re-run is tracked live again.
T6_CLEARED=0
for i in $(seq 1 15); do
  if [ -z "$(psql_q "SELECT coalesce(final_status,'') FROM run_index WHERE run_id='$T6ID'")" ]; then T6_CLEARED=1; break; fi
  sleep 2
done
[ "$T6_CLEARED" = "1" ] || fail "T6 final_status was not cleared after /retry (archive still masks the re-run='$( psql_q "SELECT final_status FROM run_index WHERE run_id='$T6ID'")')"
echo "[GATE] T6 archive cleared after retry (final_status=NULL, re-run tracked live)"
# The re-run recovers fault-free to the applyPLC human gate -> approve -> COMPLETED.
approve_gate "$T6ID" || fail "T6 re-run applyPLC gate not approved (never parked — archive may still be masking it)"
wait_terminal "$T6ID" || fail "T6 re-run did not reach terminal (completed) within ~180s"
archive_run "$T6ID" 0 || fail "T6 re-run was not re-archived"
T6_FS1=$(psql_q "SELECT final_status FROM run_index WHERE run_id='$T6ID'")
T6_CO1=$(psql_q "SELECT comp_outcome FROM run_index WHERE run_id='$T6ID'")
T6_DONE=$(psql_q "SELECT count(*) FROM run_node_state WHERE run_id='$T6ID' AND state='DONE'")
echo "[GATE] T6 re-archived final_status=$T6_FS1 comp_outcome=$T6_CO1 done_nodes=$T6_DONE/$NODE_COUNT"
case "$T6_FS1" in *COMPLETED) ;; *) fail "T6 post-retry final_status='$T6_FS1' not LIKE %COMPLETED" ;; esac
[ "$T6_CO1" = "NONE" ]          || fail "T6 post-retry comp_outcome='$T6_CO1' expected NONE (clean re-run)"
[ "$T6_DONE" = "$NODE_COUNT" ] || fail "T6 post-retry DONE node count=$T6_DONE expected $NODE_COUNT"
echo "[GATE] assert T6 PASS (retry un-archives then re-archives — archive never masks a re-run)"

# ---------------------------------------------------------------------------
echo ""
echo "[GATE] blockers: temporal_happy=1 temporal_failure_durable=1 aged_served_from_db=1 conductor_happy=1 conductor_failure_durable=1 conductor_retry_rearchive=1"
echo "[GATE] PASS run-statepersist-gate.sh"
exit 0
