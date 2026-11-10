#!/usr/bin/env bash
# v0.5 scenario value-demo CAPSTONE GATE (objective proof): full operator journey on ot-recipe-apply — a
# LINEAR OT actuation saga — exercised on REAL Temporal. Four Temporal asserts (A1/A3/A5/A6). A2/A4
# (Conductor) are appended in Chunk 3.
#
# Notes:
#   (a) This is the v0.5 value-demo capstone gate. It exercises all four operator journeys on the
#       ot-recipe-apply anchor workflow composed in Chunk 1.
#   (b) The forward-fault point is the SINGLE transform.map block (node id: preflight) because fault_inject
#       keys on block TYPE. Faulting preflight guarantees PLC (applyPLC / actuate) NEVER fires.
#   (c) db.* tables (source_rows, target_rows) are hard-coded runtime defaults — the workflow params JSON
#       is only a hint; the runtime ignores the params field and uses the seeded tables directly.
#
# Asserts:
#   A1 happy-path     : operator approves the actuation gate → PLC applied, target_rows written, all DONE.
#   A3 forward-fail   : preflight fails (failAtBlockId) BEFORE actuation → ordered reverse-topo rollback,
#                       PLC untouched, target_rows cleaned.
#   A5 intervention   : fault_inject arms preflight; interactive run parks it; operator retries after
#                       disarming; then approves the actuation gate → run completes.
#   A6 best-effort    : fault_inject on notify.email compensate phase; reject triggers unwind; interlockAck
#                       recorded COMP_FAILED; unwind continues to recordPlan (db.upsert) COMPENSATED.
#
# Temporal-only (Conductor A2/A4 appended in Chunk 3). Needs Postgres 15432 + Temporal 7233.
# Bring-up cloned from run-compensation-timeline-gate.sh (env incl. HERMOD_FAULT_INJECT=1, psql_q,
# kill/cleanup, step-0 schema/reset/seed, worker+API, save+poll-bind).
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-scenario-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1   # arm the worker's test-only fault_inject toggle

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/scenario-gate"
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

fail() { echo "[GATE] FAIL: $*"; echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true; echo "--- api log tail ---"; tail -40 "$API_LOG" 2>/dev/null || true; exit 1; }

echo "[GATE] db = $HERMOD_DB_URL ; poll = ${HERMOD_WF_POLL_MS}ms ; fault-inject = on"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: ensure schemas (registry + fault_inject phase) + reset state + seed source_rows"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start the worker (background, fault-inject armed env) BEFORE composing; then start API"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=gate-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
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
echo "[GATE] step 3: save ot-recipe-apply@1.0.0 + wait for the LIVE worker to poll-bind it (no restart)"
SAVE_CODE=$(curl -s -o "$WORK/save_ora.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-apply.json")
echo "[GATE] save ot-recipe-apply@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save_ora.json")"
[ "$SAVE_CODE" = "200" ] || fail "save ot-recipe-apply@1.0.0 expected 200, got $SAVE_CODE"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind ot-recipe-apply@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-apply@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind ot-recipe-apply@1.0.0"
echo "[GATE] worker poll-bound ot-recipe-apply@1.0.0 WITHOUT restart"

# ---------------------------------------------------------------------------
echo "[GATE] ===== A1: happy path — operator approves the actuation gate ====="
psql_q "TRUNCATE fault_inject" >/dev/null
RUN_OK="scn-happy-1"
curl -fsS -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_OK\"}" >/dev/null || fail "A1 start failed"
GATED=0
for i in $(seq 1 30); do
  if curl -fsS "$API/api/runs/$RUN_OK/nodes" | grep -qE '"applyPLC":"(RUNNING|AWAITING_APPROVAL)"'; then GATED=1; break; fi
  sleep 2
done
[ "$GATED" = "1" ] || fail "A1 run never reached the actuation gate (applyPLC RUNNING/AWAITING_APPROVAL)"
curl -fsS -X POST "$API/api/runs/$RUN_OK/approve" >/dev/null || fail "A1 approve failed"
curl -fsS "$API/api/runs/$RUN_OK?wait=true" | grep -q '"completed":true' || fail "A1 run did not complete after approve"
[ "$(psql_q "SELECT count(*) FROM target_rows")" != "0" ] || fail "A1 recordPlan did not write target_rows"
curl -fsS "$API/api/runs/$RUN_OK/nodes" | grep -q '"applyPLC":"DONE"' || fail "A1 applyPLC not DONE"
echo "[GATE] A1 OK: gate approved → PLC applied, target_rows=$(psql_q "SELECT count(*) FROM target_rows"), all nodes DONE"

# ---------------------------------------------------------------------------
echo "[GATE] ===== A3: preflight fails BEFORE actuation → ordered reverse-topo rollback, PLC untouched ====="
psql_q "TRUNCATE fault_inject" >/dev/null
psql_q "TRUNCATE target_rows" >/dev/null
RUN_FF="scn-failforward-1"
curl -fsS -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_FF\",\"failAtBlockId\":\"transform.map\"}" >/dev/null || fail "A3 start failed"
curl -fsS "$API/api/runs/$RUN_FF?wait=true" | grep -q '"completed":false' || fail "A3 should have compensated"
TL=$(curl -fsS "$API/api/runs/$RUN_FF/compensation")
echo "[GATE] A3 timeline => $TL"
echo "$TL" | grep -qE '"index":0[^}]*"blockId":"notify.email"' || fail "A3 event 0 is not notify.email"
echo "$TL" | grep -qE '"blockId":"notify.email"[^}]*"outcome":"COMPENSATED"' || fail "A3 notify.email not COMPENSATED"
echo "$TL" | grep -qE '"blockId":"db.upsert"[^}]*"outcome":"COMPENSATED"' || fail "A3 db.upsert not COMPENSATED"
[ "$(psql_q "SELECT count(*) FROM target_rows")" = "0" ] || fail "A3 db.upsert compensation did not clean target_rows"
if curl -fsS "$API/api/runs/$RUN_FF/nodes" | grep -q '"applyPLC":"DONE"'; then
  fail "A3 PLC must NOT have actuated"
fi
echo "[GATE] A3 OK: ordered rollback [interlockAck, recordPlan], target cleaned, PLC NEVER fired"

# ---------------------------------------------------------------------------
echo "[GATE] ===== A5: operator intervention — park the faulted preflight, retry, then approve ====="
psql_q "TRUNCATE target_rows" >/dev/null
psql_q "INSERT INTO fault_inject(block_id) VALUES ('transform.map') ON CONFLICT DO NOTHING" >/dev/null
RUN_IV="scn-intervene-1"
curl -fsS -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_IV\",\"interactive\":true}" >/dev/null || fail "A5 start failed"
PARKED=0
for i in $(seq 1 30); do
  if curl -fsS "$API/api/runs/$RUN_IV/nodes" | grep -q '"preflight":"PARKED"'; then PARKED=1; break; fi
  sleep 2
done
[ "$PARKED" = "1" ] || fail "A5 preflight never PARKED"
curl -fsS "$API/api/runs/$RUN_IV" | grep -qE '"status":"WORKFLOW_EXECUTION_STATUS_RUNNING"' || fail "A5 parked run should still be RUNNING"
psql_q "DELETE FROM fault_inject WHERE block_id='transform.map'" >/dev/null
curl -fsS -X POST "$API/api/runs/$RUN_IV/retry" -H 'Content-Type: application/json' -d '{"nodeId":"preflight"}' >/dev/null || fail "A5 retry failed"
GATED=0
for i in $(seq 1 30); do
  if curl -fsS "$API/api/runs/$RUN_IV/nodes" | grep -q '"preflight":"DONE"'; then GATED=1; break; fi
  sleep 2
done
[ "$GATED" = "1" ] || fail "A5 preflight not DONE after retry"
curl -fsS -X POST "$API/api/runs/$RUN_IV/approve" >/dev/null || fail "A5 approve failed"
curl -fsS "$API/api/runs/$RUN_IV?wait=true" | grep -q '"completed":true' || fail "A5 did not complete after retry+approve"
echo "[GATE] A5 OK: parked → retry recovered → approved → completed"

# ---------------------------------------------------------------------------
echo "[GATE] ===== A6: best-effort — a failed compensation is recorded COMP_FAILED, unwind continues ====="
psql_q "TRUNCATE fault_inject" >/dev/null
psql_q "TRUNCATE target_rows" >/dev/null
RUN_BE="scn-besteffort-1"
curl -fsS -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_BE\"}" >/dev/null || fail "A6 start failed"
GATED=0
for i in $(seq 1 30); do
  if curl -fsS "$API/api/runs/$RUN_BE/nodes" | grep -q '"interlockAck":"DONE"'; then GATED=1; break; fi
  sleep 2
done
[ "$GATED" = "1" ] || fail "A6 interlockAck never completed before the gate"
psql_q "INSERT INTO fault_inject(block_id, phase) VALUES ('notify.email','compensate') ON CONFLICT DO NOTHING" >/dev/null
curl -fsS -X POST "$API/api/runs/$RUN_BE/reject" >/dev/null || fail "A6 reject failed"
curl -fsS "$API/api/runs/$RUN_BE?wait=true" | grep -q '"completed":false' || fail "A6 rejected run should compensate"
TLB=$(curl -fsS "$API/api/runs/$RUN_BE/compensation")
echo "[GATE] A6 timeline => $TLB"
echo "$TLB" | grep -qE '"blockId":"notify.email"[^}]*"outcome":"FAILED"' || fail "A6 notify.email (interlockAck) compensation not recorded FAILED in timeline"
echo "$TLB" | grep -qE '"blockId":"db.upsert"[^}]*"outcome":"COMPENSATED"' || fail "A6 unwind did not continue to recordPlan after the failed step"
curl -fsS "$API/api/runs/$RUN_BE/nodes" | grep -q '"interlockAck":"COMP_FAILED"' || fail "A6 interlockAck node not COMP_FAILED"
psql_q "DELETE FROM fault_inject WHERE block_id='notify.email' AND phase='compensate'" >/dev/null
echo "[GATE] A6 OK: failed compensation surfaced (COMP_FAILED), unwind continued to recordPlan (residue not hidden)"
echo ""
# ---------------------------------------------------------------------------
# Conductor section: A2 (happy) + A4 (compensation)
# ---------------------------------------------------------------------------

echo ""
echo "[GATE] ===== CONDUCTOR SECTION: A2 + A4 (engine-neutral IR proof) ====="

# Kill Temporal JVMs so ports/resources are free before Conductor bring-up
echo "[GATE] tearing down Temporal worker + API JVMs..."
kill_api_jvms || true
kill_worker_jvms || true

# Conductor-specific helpers ------------------------------------------------

CONDUCTOR_WORKER_LOG="$WORK/conductor-worker.log"

kill_conductor_worker_jvms() {
  local pids
  pids=$(jps -l 2>/dev/null | grep 'ConductorWorkerMainKt' | awk '{print $1}' || true)
  if [ -n "$pids" ]; then
    echo "$pids" | while read -r p; do
      taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
    done
  fi
}

# Re-register cleanup trap to also kill conductor worker
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_conductor_worker_jvms || true; }
trap cleanup EXIT

ctl() { ./gradlew -q --no-daemon :conductor-runtime:ctl --args="$*"; }

# Terminate any stale RUNNING/SCHEDULED Conductor workflows
cleanup_stale_workflows() {
  local running_ids
  running_ids=$(curl -sf "http://localhost:18088/api/workflow/search?query=status+IN+(RUNNING,SCHEDULED)&size=100" 2>/dev/null \
    | python -c "import sys,json; data=json.load(sys.stdin); [print(r['workflowId']) for r in data.get('results',[])]" 2>/dev/null || true)
  if [ -n "$running_ids" ]; then
    echo "[GATE] terminating stale Conductor workflows: $(echo "$running_ids" | wc -l | tr -d ' ') found"
    echo "$running_ids" | while read -r wid; do
      curl -sf -X DELETE "http://localhost:18088/api/workflow/$wid?reason=gate-cleanup" >/dev/null 2>&1 || true
    done
  fi
}

# Seed / reset DB for Conductor asserts (seeds source_rows + resets target_rows + comp_ledger + stale workflows)
seed_db() {
  psql_q "DELETE FROM source_rows;" >/dev/null
  psql_q "DELETE FROM target_rows;"  >/dev/null
  psql_q "DELETE FROM comp_ledger;"  >/dev/null
  psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','alpha'),('r2','beta') ON CONFLICT DO NOTHING;" >/dev/null
  cleanup_stale_workflows
}

comp_ledger_count() { psql_q "SELECT COUNT(*) FROM comp_ledger WHERE workflow_id='$1' AND compensated=true;"; }

# Poll ctl result until status=EXPECTED (or timeout). Prints last output on exit.
poll_status() {
  local wfid="$1" expected="$2" timeout_s="${3:-90}" interval=3
  local deadline=$(( $(date +%s) + timeout_s ))
  local out status
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "result $wfid" 2>/dev/null || true)
    status=$(echo "$out" | grep '^status=' | cut -d= -f2 | tr -d '\r')
    if [ "$status" = "$expected" ]; then
      echo "$out"
      return 0
    fi
    # bail early on unexpected terminal
    case "$status" in
      COMPLETED|FAILED|TERMINATED|TIMED_OUT)
        echo "$out"
        return 1
        ;;
    esac
    sleep "$interval"
  done
  out=$(ctl "result $wfid" 2>/dev/null || true)
  echo "$out"
  return 1
}

# Poll until compStatus=COMPLETED (implies status=FAILED already set)
poll_comp_completed() {
  local wfid="$1" timeout_s="${2:-120}" interval=3
  local deadline=$(( $(date +%s) + timeout_s ))
  local out comp_status
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "result $wfid" 2>/dev/null || true)
    comp_status=$(echo "$out" | grep '^compStatus=' | cut -d= -f2 | tr -d '\r')
    if [ "$comp_status" = "COMPLETED" ]; then
      echo "$out"
      return 0
    fi
    sleep "$interval"
  done
  out=$(ctl "result $wfid" 2>/dev/null || true)
  echo "$out"
  return 1
}

# Poll approve until it returns approve=true (WAIT task becomes IN_PROGRESS after RUNNING).
poll_approve() {
  local wfid="$1" timeout_s="${2:-90}" interval=3
  local deadline=$(( $(date +%s) + timeout_s ))
  local out
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "approve $wfid" 2>/dev/null || true)
    if echo "$out" | grep -q "approve=true"; then
      echo "$out"
      return 0
    fi
    sleep "$interval"
  done
  echo "$out"
  return 1
}

# Wait for Conductor health
echo "[GATE] waiting for Conductor at http://localhost:18088/health..."
CONDUCTOR_HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
until curl -sf http://localhost:18088/health 2>/dev/null | grep -q '"healthy":true'; do
  if [ "$(date +%s)" -ge "$CONDUCTOR_HEALTH_DEADLINE" ]; then
    echo "[GATE] FAIL: Conductor did not become healthy within 3 minutes"; exit 1
  fi
  sleep 3
done
echo "[GATE] Conductor healthy"

# Start Conductor worker
echo "[GATE] starting Conductor worker..."
: > "$CONDUCTOR_WORKER_LOG"
./gradlew -q --no-daemon :conductor-runtime:run >"$CONDUCTOR_WORKER_LOG" 2>&1 &

CONDUCTOR_WORKER_DEADLINE=$(( $(date +%s) + 90 ))
until grep -q 'conductor workers polling' "$CONDUCTOR_WORKER_LOG" 2>/dev/null; do
  if [ "$(date +%s)" -ge "$CONDUCTOR_WORKER_DEADLINE" ]; then
    echo "[GATE] FAIL: Conductor workers did not start polling within 90s"
    cat "$CONDUCTOR_WORKER_LOG"
    exit 1
  fi
  sleep 2
done
echo "[GATE] Conductor workers polling"

# Deploy ot-recipe-apply to Conductor
echo "[GATE] deploying ot-recipe-apply to Conductor..."
ctl "deploy ot-recipe-apply"

# ---------------------------------------------------------------------------
echo "[GATE] ===== A2: happy path on REAL Conductor (same IR) — approve gate → COMPLETED ====="
seed_db   # seeds source_rows (db.read default), resets target_rows + comp_ledger + stale workflows
WF_OK=$(ctl "start ot-recipe-apply" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF_OK" ] || fail "A2 start did not return a workflowId"
poll_approve "$WF_OK" 90 | grep -q "approve=true" || fail "A2 WAIT task never became approvable"
poll_status "$WF_OK" "COMPLETED" 90 | grep -q "COMPLETED" || fail "A2 did not COMPLETE after approve"
[ "$(psql_q "SELECT count(*) FROM target_rows")" != "0" ] || fail "A2 recordPlan did not write target_rows on Conductor"
echo "[GATE] A2 OK: same chain runs forward + human-gate approve on Conductor"

# ---------------------------------------------------------------------------
echo "[GATE] ===== A4: preflight failure on Conductor → reverse compensation, target cleaned ====="
seed_db   # seeds source_rows + resets target_rows/comp_ledger/stale workflows
WF_CF=$(ctl "start ot-recipe-apply failAt=transform.map" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF_CF" ] || fail "A4 start did not return a workflowId"
A4_COMP=$(poll_comp_completed "$WF_CF" 90) || fail "A4 compensation did not complete within 90s (last: $A4_COMP)"
[ "$(comp_ledger_count "$WF_CF")" -ge 1 ] || fail "A4 comp_ledger shows no compensation"
[ "$(psql_q "SELECT count(*) FROM target_rows")" = "0" ] || fail "A4 compensation did not clean target_rows on Conductor"
echo "[GATE] A4 OK: same IR compensates in reverse on Conductor (comp_ledger + target cleaned)"

echo ""
echo "[GATE] PASS run-scenario-gate.sh"
exit 0
