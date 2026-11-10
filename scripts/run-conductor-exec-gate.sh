#!/usr/bin/env bash
# §7 v0.2d GATE (objective): IR -> Conductor forward + human gate + reverse compensation. PASS = 4 hard asserts.
set -euo pipefail
cd "$(dirname "$0")/.."

export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"

WORKER_LOG=/tmp/conductor-gate-worker.log

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

ctl() { ./gradlew -q --no-daemon :conductor-runtime:ctl --args="$*"; }

psql_q() {
  docker compose exec -T postgres psql -U hermod -d hermod -t -c "$1" | tr -d ' \n\r'
}

kill_worker_jvms() {
  local pids
  pids=$(jps -l 2>/dev/null | grep 'ConductorWorkerMainKt' | awk '{print $1}' || true)
  if [ -n "$pids" ]; then
    echo "$pids" | while read -r p; do
      taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
    done
  fi
}

trap kill_worker_jvms EXIT

# ---------------------------------------------------------------------------
# R-1: Wait for Conductor health (up to 3 min)
# ---------------------------------------------------------------------------
echo "[GATE] waiting for Conductor at http://localhost:18088/health..."
HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
until curl -sf http://localhost:18088/health 2>/dev/null | grep -q '"healthy":true'; do
  if [ "$(date +%s)" -ge "$HEALTH_DEADLINE" ]; then
    echo "[GATE] FAIL: Conductor did not become healthy within 3 minutes"; exit 1
  fi
  sleep 3
done
echo "[GATE] Conductor healthy"

# ---------------------------------------------------------------------------
# Boot workers in background
# ---------------------------------------------------------------------------
echo "[GATE] starting workers..."
: > "$WORKER_LOG"
./gradlew -q --no-daemon :conductor-runtime:run >"$WORKER_LOG" 2>&1 &

# Wait for the worker to confirm it's polling (up to 90s — Gradle JVM startup varies)
WORKER_DEADLINE=$(( $(date +%s) + 90 ))
until grep -q 'conductor workers polling' "$WORKER_LOG" 2>/dev/null; do
  if [ "$(date +%s)" -ge "$WORKER_DEADLINE" ]; then
    echo "[GATE] FAIL: workers did not start polling within 90s"; cat "$WORKER_LOG"; exit 1
  fi
  sleep 2
done
echo "[GATE] workers polling"

# ---------------------------------------------------------------------------
# Deploy workflows (idempotent)
# ---------------------------------------------------------------------------
echo "[GATE] deploying gate-forward..."
ctl "deploy gate-forward"
echo "[GATE] deploying demo (gated + compensable)..."
ctl "deploy demo"

# ---------------------------------------------------------------------------
# Terminate any stale RUNNING/SCHEDULED workflows from previous runs so workers
# aren't occupied processing unrelated tasks during our asserts.
# ---------------------------------------------------------------------------
cleanup_stale_workflows() {
  local running_ids
  running_ids=$(curl -sf "http://localhost:18088/api/workflow/search?query=status+IN+(RUNNING,SCHEDULED)&size=100" 2>/dev/null \
    | python -c "import sys,json; data=json.load(sys.stdin); [print(r['workflowId']) for r in data.get('results',[])]" 2>/dev/null || true)
  if [ -n "$running_ids" ]; then
    echo "[GATE] terminating stale workflows: $(echo "$running_ids" | wc -l | tr -d ' ') found"
    echo "$running_ids" | while read -r wid; do
      curl -sf -X DELETE "http://localhost:18088/api/workflow/$wid?reason=gate-cleanup" >/dev/null 2>&1 || true
    done
  fi
}

# ---------------------------------------------------------------------------
# Seed / reset DB
# ---------------------------------------------------------------------------
seed_db() {
  psql_q "DELETE FROM source_rows;" >/dev/null
  psql_q "DELETE FROM target_rows;"  >/dev/null
  psql_q "DELETE FROM comp_ledger;"  >/dev/null
  psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','alpha'),('r2','beta') ON CONFLICT DO NOTHING;" >/dev/null
  cleanup_stale_workflows
}

target_count() { psql_q "SELECT COUNT(*) FROM target_rows;"; }
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
# The workflow is RUNNING but the WAIT task may lag a few seconds before it's IN_PROGRESS.
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

# Poll reject until it returns reject=true (same reason as approve).
poll_reject() {
  local wfid="$1" reason="${2:-rejected}" timeout_s="${3:-90}" interval=3
  local deadline=$(( $(date +%s) + timeout_s ))
  local out
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "reject $wfid $reason" 2>/dev/null || true)
    if echo "$out" | grep -q "reject=true"; then
      echo "$out"
      return 0
    fi
    sleep "$interval"
  done
  echo "$out"
  return 1
}

# ===========================================================================
# Assert 1: forward happy-path
# ===========================================================================
echo ""
echo "[GATE] assert 1: forward happy-path (gate-forward: db.read -> transform.map -> db.upsert)"
seed_db

WF1_ID=$(ctl "start gate-forward" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF1_ID" ] || { echo "[GATE] FAIL: assert 1 — start did not return a workflowId"; exit 1; }
echo "[GATE]   started $WF1_ID"

RESULT1=$(poll_status "$WF1_ID" "COMPLETED" 90) || {
  echo "[GATE] FAIL: assert 1 — gate-forward did not COMPLETE within 90s; last result:"
  echo "$RESULT1"
  exit 1
}
echo "[GATE]   result: $RESULT1"

CNT1=$(target_count)
[ "$CNT1" = "2" ] || {
  echo "[GATE] FAIL: assert 1 — expected 2 rows in target_rows after forward, got $CNT1"
  exit 1
}
echo "[GATE] assert 1 PASS: forward COMPLETED; target_rows=$CNT1"

# ===========================================================================
# Assert 2: human-gate approve
# ===========================================================================
echo ""
echo "[GATE] assert 2: human-gate approve (demo: db.read -> ... -> actuate WAIT -> COMPLETED after approve)"
seed_db

WF2_ID=$(ctl "start demo" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF2_ID" ] || { echo "[GATE] FAIL: assert 2 — start did not return a workflowId"; exit 1; }
echo "[GATE]   started $WF2_ID"

# First wait until RUNNING (pre-gate steps running)
RESULT2_RUN=$(poll_status "$WF2_ID" "RUNNING" 90) || {
  echo "[GATE] FAIL: assert 2 — workflow did not reach RUNNING within 90s; last result: $RESULT2_RUN"
  exit 1
}
echo "[GATE]   workflow RUNNING; polling for WAIT task..."

# Poll approve (retries until the WAIT task becomes IN_PROGRESS)
APPROVE_OUT=$(poll_approve "$WF2_ID" 90) || {
  echo "[GATE] FAIL: assert 2 — WAIT task never became IN_PROGRESS within 90s"
  exit 1
}
echo "[GATE]   $APPROVE_OUT"

RESULT2=$(poll_status "$WF2_ID" "COMPLETED" 90) || {
  echo "[GATE] FAIL: assert 2 — demo did not COMPLETE after approve within 90s; last result:"
  echo "$RESULT2"
  exit 1
}
echo "[GATE] assert 2 PASS: human-gate APPROVE -> COMPLETED; result: $RESULT2"

# ===========================================================================
# Assert 3: reject -> compensation (target_rows cleaned + comp_ledger compensated)
# ===========================================================================
echo ""
echo "[GATE] assert 3: human-gate reject -> compensation (target_rows cleaned, comp_ledger compensated)"
seed_db

WF3_ID=$(ctl "start demo" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF3_ID" ] || { echo "[GATE] FAIL: assert 3 — start did not return a workflowId"; exit 1; }
echo "[GATE]   started $WF3_ID"

# Wait until RUNNING
RESULT3_RUN=$(poll_status "$WF3_ID" "RUNNING" 90) || {
  echo "[GATE] FAIL: assert 3 — workflow did not reach RUNNING within 90s; last result: $RESULT3_RUN"
  exit 1
}

# Precondition: rows were inserted by db.upsert before the WAIT
CNT3_PRE=$(target_count)
[ "$CNT3_PRE" = "2" ] || { echo "[GATE] FAIL: assert 3 — expected 2 rows in target_rows before reject, got $CNT3_PRE"; exit 1; }
echo "[GATE]   target_rows=$CNT3_PRE (pre-reject); polling for WAIT task then rejecting..."

# Poll reject (retries until the WAIT task becomes IN_PROGRESS)
REJECT_OUT=$(poll_reject "$WF3_ID" "operator-declined" 90) || {
  echo "[GATE] FAIL: assert 3 — WAIT task never became IN_PROGRESS within 90s (reject returned false)"
  exit 1
}
echo "[GATE]   $REJECT_OUT"

# Poll until FAILED + compStatus=COMPLETED
COMP3=$(poll_comp_completed "$WF3_ID" 120) || {
  echo "[GATE] FAIL: assert 3 — compensation did not complete within 120s; last result:"
  echo "$COMP3"
  exit 1
}
echo "$COMP3" | grep -q "status=FAILED" || { echo "[GATE] FAIL: assert 3 — main wf not FAILED after reject; got: $COMP3"; exit 1; }
echo "$COMP3" | grep -q "compStatus=COMPLETED" || { echo "[GATE] FAIL: assert 3 — compStatus not COMPLETED; got: $COMP3"; exit 1; }
echo "[GATE]   comp result: $COMP3"

CNT3_POST=$(target_count)
[ "$CNT3_POST" = "0" ] || { echo "[GATE] FAIL: assert 3 — target_rows not cleaned after compensation, got $CNT3_POST"; exit 1; }

COMP_ROWS=$(comp_ledger_count "$WF3_ID")
[ "$COMP_ROWS" -ge "1" ] || { echo "[GATE] FAIL: assert 3 — no compensated=true rows in comp_ledger for $WF3_ID, got $COMP_ROWS"; exit 1; }

echo "[GATE] assert 3 PASS: reject -> compensation COMPLETED; target_rows=0; comp_ledger compensated rows=$COMP_ROWS"

# ===========================================================================
# Assert 4: mid-failure -> compensation (failAt=notify.email)
# ===========================================================================
echo ""
echo "[GATE] assert 4: mid-failure -> compensation (failAt=notify.email: db.upsert succeeds then fails)"
seed_db

WF4_ID=$(ctl "start demo failAt=notify.email" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF4_ID" ] || { echo "[GATE] FAIL: assert 4 — start did not return a workflowId"; exit 1; }
echo "[GATE]   started $WF4_ID (failAt=notify.email)"

# Poll until FAILED + compStatus=COMPLETED (notify.email -> FAILED_WITH_TERMINAL_ERROR, no retry)
COMP4=$(poll_comp_completed "$WF4_ID" 120) || {
  echo "[GATE] FAIL: assert 4 — compensation did not complete within 120s; last result:"
  echo "$COMP4"
  exit 1
}
echo "$COMP4" | grep -q "status=FAILED" || { echo "[GATE] FAIL: assert 4 — main wf not FAILED after mid-flight failure; got: $COMP4"; exit 1; }
echo "$COMP4" | grep -q "compStatus=COMPLETED" || { echo "[GATE] FAIL: assert 4 — compStatus not COMPLETED after mid-failure; got: $COMP4"; exit 1; }
echo "[GATE]   comp result: $COMP4"

CNT4_POST=$(target_count)
[ "$CNT4_POST" = "0" ] || { echo "[GATE] FAIL: assert 4 — target_rows not cleaned after mid-failure compensation, got $CNT4_POST"; exit 1; }

COMP4_ROWS=$(comp_ledger_count "$WF4_ID")
[ "$COMP4_ROWS" -ge "1" ] || { echo "[GATE] FAIL: assert 4 — no compensated=true rows in comp_ledger for $WF4_ID, got $COMP4_ROWS"; exit 1; }

echo "[GATE] assert 4 PASS: mid-failure -> compensation COMPLETED; target_rows=0; comp_ledger compensated rows=$COMP4_ROWS"

# ===========================================================================
# Final
# ===========================================================================
echo ""
echo "[GATE] blockers: conductor_health=1 forward_completed=1 approve_completed=1 reject_compensation=1 midfail_compensation=1"
echo "[GATE] PASS (one IR -> Conductor forward + human gate + reverse compensation)"
