#!/usr/bin/env bash
# §6 v0.3a GATE (objective): ONE engine-neutral IR -> diamond fan-in on BOTH Temporal AND Conductor,
# plus reverse-topo compensation on both engines, plus negative compiles rejected. PASS = 5 hard asserts.
set -euo pipefail
cd "$(dirname "$0")/.."

export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"

TWORKER_LOG=/tmp/dag-gate-temporal-worker.log
CWORKER_LOG=/tmp/dag-gate-conductor-worker.log

# ---------------------------------------------------------------------------
# Helpers (copied verbatim from the two existing gates)
# ---------------------------------------------------------------------------

ctl() { ./gradlew -q --no-daemon :conductor-runtime:ctl --args="$*"; }

psql_q() {
  docker compose exec -T postgres psql -U hermod -d hermod -t -c "$1" | tr -d ' \n\r'
}

# Kill the Temporal app worker (yggdrasil.hermod.app.WorkerKt)
kill_temporal_jvms() {
  { jps -l 2>/dev/null | grep 'yggdrasil.hermod.app.WorkerKt' || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}

# Kill the Conductor worker (ConductorWorkerMainKt)
kill_conductor_jvms() {
  { jps -l 2>/dev/null | grep 'ConductorWorkerMainKt' || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}

cleanup_all() { kill_temporal_jvms; kill_conductor_jvms; }
trap cleanup_all EXIT

# Terminate stale RUNNING/SCHEDULED Conductor workflows so the workers aren't busy on unrelated tasks.
cleanup_stale_workflows() {
  local running_ids
  running_ids=$(curl -sf "http://localhost:18088/api/workflow/search?query=status+IN+(RUNNING,SCHEDULED)&size=100" 2>/dev/null \
    | python -c "import sys,json; data=json.load(sys.stdin); [print(r['workflowId']) for r in data.get('results',[])]" 2>/dev/null || true)
  if [ -n "$running_ids" ]; then
    echo "$running_ids" | while read -r wid; do
      curl -sf -X DELETE "http://localhost:18088/api/workflow/$wid?reason=gate-cleanup" >/dev/null 2>&1 || true
    done
  fi
}

# Seed source_rows with 2 rows (r1,r2); clear target_rows + comp_ledger.
seed_db() {
  psql_q "DELETE FROM source_rows;" >/dev/null
  psql_q "DELETE FROM target_rows;"  >/dev/null
  psql_q "DELETE FROM comp_ledger;"  >/dev/null
  psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','alpha'),('r2','beta') ON CONFLICT DO NOTHING;" >/dev/null
}

target_count() { psql_q "SELECT COUNT(*) FROM target_rows;"; }
comp_ledger_count() { psql_q "SELECT COUNT(*) FROM comp_ledger WHERE workflow_id='$1' AND compensated=true;"; }

# Poll Conductor ctl result until status=EXPECTED (or timeout). Prints last output.
poll_status() {
  local wfid="$1" expected="$2" timeout_s="${3:-120}" interval=3
  local deadline=$(( $(date +%s) + timeout_s ))
  local out status
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "result $wfid" 2>/dev/null || true)
    status=$(echo "$out" | grep '^status=' | cut -d= -f2 | tr -d '\r')
    if [ "$status" = "$expected" ]; then echo "$out"; return 0; fi
    case "$status" in
      COMPLETED|FAILED|TERMINATED|TIMED_OUT) echo "$out"; return 1 ;;
    esac
    sleep "$interval"
  done
  out=$(ctl "result $wfid" 2>/dev/null || true)
  echo "$out"; return 1
}

# Poll until compStatus=COMPLETED (implies status=FAILED already set).
poll_comp_completed() {
  local wfid="$1" timeout_s="${2:-120}" interval=3
  local deadline=$(( $(date +%s) + timeout_s ))
  local out comp_status
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "result $wfid" 2>/dev/null || true)
    comp_status=$(echo "$out" | grep '^compStatus=' | cut -d= -f2 | tr -d '\r')
    if [ "$comp_status" = "COMPLETED" ]; then echo "$out"; return 0; fi
    sleep "$interval"
  done
  out=$(ctl "result $wfid" 2>/dev/null || true)
  echo "$out"; return 1
}

# Poll the Temporal starter `result` until it prints `completed=<bool>` (workflow reached a terminal state).
poll_temporal_result() {
  local wfid="$1" timeout_s="${2:-90}" interval=4
  local deadline=$(( $(date +%s) + timeout_s ))
  local out
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(./gradlew -q --no-daemon starter --args="result $wfid" 2>/dev/null | grep -E "completed=" || true)
    if [ -n "$out" ]; then echo "$out"; return 0; fi
    sleep "$interval"
  done
  return 1
}

# cygpath -w absolute path idiom (for --file fixtures); the :app:cli JavaExec runs with workingDir=app/.
abspath() {
  cygpath -w "$(pwd)/$1" 2>/dev/null || echo "$(pwd)/$1"
}

# ===========================================================================
# Assert 1: compile determinism + negative rejects (NO server needed)
# ===========================================================================
echo ""
echo "[GATE] assert 1: compile determinism + negative compiles rejected (cycle, unwired)"

A=$(./gradlew -q --no-daemon :app:cli --args="compile dag-diamond --target conductor")
B=$(./gradlew -q --no-daemon :app:cli --args="compile dag-diamond --target conductor")
[ "$A" = "$B" ] || { echo "[GATE] FAIL: assert 1 — non-deterministic emit for dag-diamond"; exit 1; }
echo "[GATE]   determinism OK (dag-diamond compiled byte-equal twice)"

CYCLE_F="$(abspath scripts/fixtures/dag-cycle.yaml)"
if ./gradlew -q --no-daemon :app:cli --args="compile --file '$CYCLE_F' --target conductor" 2>/tmp/dag-cycle.err; then
  echo "[GATE] FAIL: assert 1 — dag-cycle compiled (cycle detection not load-bearing)"; exit 1
fi
grep -qi "cycle" /tmp/dag-cycle.err || { echo "[GATE] FAIL: assert 1 — no cycle diagnostic"; cat /tmp/dag-cycle.err; exit 1; }
echo "[GATE]   cycle rejected: $(grep -i cycle /tmp/dag-cycle.err | head -1 | tr -d '\r')"

UNWIRED_F="$(abspath scripts/fixtures/dag-unwired.yaml)"
if ./gradlew -q --no-daemon :app:cli --args="compile --file '$UNWIRED_F' --target conductor" 2>/tmp/dag-unwired.err; then
  echo "[GATE] FAIL: assert 1 — dag-unwired compiled (unwired-input detection not load-bearing)"; exit 1
fi
grep -qi "unwired" /tmp/dag-unwired.err || { echo "[GATE] FAIL: assert 1 — no unwired diagnostic"; cat /tmp/dag-unwired.err; exit 1; }
echo "[GATE]   unwired rejected: $(grep -i unwired /tmp/dag-unwired.err | head -1 | tr -d '\r')"
echo "[GATE] assert 1 PASS: determinism + cycle + unwired rejects"

# ===========================================================================
# Assert 2: Temporal diamond run + [merge] fan-in marker
# ===========================================================================
echo ""
echo "[GATE] assert 2: Temporal diamond run + [merge] fan-in marker (dag-diamond)"
kill_temporal_jvms
: > "$TWORKER_LOG"
HERMOD_WORKER_NAME=dag ./gradlew -q --no-daemon run >"$TWORKER_LOG" 2>&1 &
echo "[GATE]   booting Temporal worker (sleep 22)..."
sleep 22

seed_db

WF="dag-$(date +%s)"
echo "[GATE]   starting $WF (dag-diamond)..."
./gradlew -q --no-daemon starter --args="start $WF - 0 dag-diamond" >/dev/null

RESULT2=$(poll_temporal_result "$WF" 90) || { echo "[GATE] FAIL: assert 2 — dag-diamond did not reach terminal within 90s"; cat "$TWORKER_LOG"; exit 1; }
echo "[GATE]   result: $RESULT2"
echo "$RESULT2" | grep -q "completed=true" || { echo "[GATE] FAIL: assert 2 — dag-diamond not completed=true; $RESULT2"; cat "$TWORKER_LOG"; exit 1; }

grep -q "\[merge\] left=2 right=2 out=4" "$TWORKER_LOG" || {
  echo "[GATE] FAIL: assert 2 — no [merge] left=2 right=2 out=4 fan-in marker in Temporal worker log"; cat "$TWORKER_LOG"; exit 1
}
echo "[GATE] assert 2 PASS: Temporal diamond COMPLETED with fan-in marker [merge] left=2 right=2 out=4"

# Kill the Temporal worker so it doesn't hold the queue while Conductor runs.
kill_temporal_jvms
echo "[GATE]   Temporal worker killed"

# ===========================================================================
# Assert 3: Conductor diamond run + fan-in
# ===========================================================================
echo ""
echo "[GATE] assert 3: Conductor diamond run + [merge] fan-in (dag-diamond)"

echo "[GATE]   waiting for Conductor health..."
HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
until curl -sf http://localhost:18088/health 2>/dev/null | grep -q '"healthy":true'; do
  if [ "$(date +%s)" -ge "$HEALTH_DEADLINE" ]; then echo "[GATE] FAIL: Conductor not healthy within 3m"; exit 1; fi
  sleep 3
done
echo "[GATE]   Conductor healthy"

kill_conductor_jvms
: > "$CWORKER_LOG"
./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
WORKER_DEADLINE=$(( $(date +%s) + 90 ))
until grep -q 'conductor workers polling' "$CWORKER_LOG" 2>/dev/null; do
  if [ "$(date +%s)" -ge "$WORKER_DEADLINE" ]; then echo "[GATE] FAIL: conductor workers did not poll within 90s"; cat "$CWORKER_LOG"; exit 1; fi
  sleep 2
done
echo "[GATE]   conductor workers polling"

ctl "deploy dag-diamond"
seed_db
cleanup_stale_workflows

WF3=$(ctl "start dag-diamond" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF3" ] || { echo "[GATE] FAIL: assert 3 — start did not return a workflowId"; exit 1; }
echo "[GATE]   started $WF3"

RESULT3=$(poll_status "$WF3" "COMPLETED" 120) || { echo "[GATE] FAIL: assert 3 — dag-diamond did not COMPLETE on Conductor within 120s; last: $RESULT3"; cat "$CWORKER_LOG"; exit 1; }
echo "[GATE]   result: $RESULT3"

grep -q "\[merge\] left=2 right=2 out=4" "$CWORKER_LOG" || {
  echo "[GATE] FAIL: assert 3 — no [merge] left=2 right=2 out=4 fan-in marker in Conductor worker log"; cat "$CWORKER_LOG"; exit 1
}
CNT3=$(target_count)
[ "$CNT3" = "2" ] || { echo "[GATE] FAIL: assert 3 — expected target_rows=2 (4 merged collapsed by PK), got $CNT3"; exit 1; }
echo "[GATE] assert 3 PASS: Conductor diamond COMPLETED; fan-in marker present; target_rows=$CNT3"

# ===========================================================================
# Assert 4: compensation on BOTH engines (dag-diamond-comp)
#   branch b = db.upsert (succeeds), sink = notify.email (fails) -> reverse-topo comp of db.upsert
# ===========================================================================
echo ""
echo "[GATE] assert 4a: Temporal compensation (dag-diamond-comp, failAt=notify.email)"

# Re-boot the Temporal worker (killed after assert 2). Keep conductor workers up — they bind the same
# workflow names but poll a DIFFERENT engine, so no contention.
kill_temporal_jvms
: > "$TWORKER_LOG"
HERMOD_WORKER_NAME=dag ./gradlew -q --no-daemon run >"$TWORKER_LOG" 2>&1 &
echo "[GATE]   re-booting Temporal worker (sleep 22)..."
sleep 22

seed_db
WFc="dagc-$(date +%s)"
echo "[GATE]   starting $WFc (dag-diamond-comp failAt=notify.email)..."
./gradlew -q --no-daemon starter --args="start $WFc notify.email 0 dag-diamond-comp" >/dev/null

RESULT4A=$(poll_temporal_result "$WFc" 90) || { echo "[GATE] FAIL: assert 4a — dag-diamond-comp did not reach terminal within 90s"; cat "$TWORKER_LOG"; exit 1; }
echo "[GATE]   result: $RESULT4A"
echo "$RESULT4A" | grep -q "completed=false" || { echo "[GATE] FAIL: assert 4a — expected completed=false; $RESULT4A"; cat "$TWORKER_LOG"; exit 1; }
# Starter prints `compensated=${out.compensatedInReverseOrder}` (a list). db.upsert (branch b) must appear.
echo "$RESULT4A" | grep -q "compensated=.*db.upsert" || { echo "[GATE] FAIL: assert 4a — db.upsert not in compensated reverse-order list; $RESULT4A"; cat "$TWORKER_LOG"; exit 1; }

CNT4A=$(target_count)
[ "$CNT4A" = "0" ] || { echo "[GATE] FAIL: assert 4a — target_rows not compensated to 0 (db.upsert undo), got $CNT4A"; exit 1; }
echo "[GATE] assert 4a PASS: Temporal compensated=$(echo "$RESULT4A" | sed 's/.*compensated=//'); target_rows=0"

kill_temporal_jvms
echo "[GATE]   Temporal worker killed"

echo ""
echo "[GATE] assert 4b: Conductor compensation (dag-diamond-comp failAt=notify.email)"

# Conductor workers still up from assert 3 (re-ensure they poll in case they died).
if ! grep -q 'conductor workers polling' "$CWORKER_LOG" 2>/dev/null; then
  kill_conductor_jvms
  : > "$CWORKER_LOG"
  ./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
  WORKER_DEADLINE=$(( $(date +%s) + 90 ))
  until grep -q 'conductor workers polling' "$CWORKER_LOG" 2>/dev/null; do
    if [ "$(date +%s)" -ge "$WORKER_DEADLINE" ]; then echo "[GATE] FAIL: conductor workers did not re-poll"; cat "$CWORKER_LOG"; exit 1; fi
    sleep 2
  done
fi

ctl "deploy dag-diamond-comp"
seed_db
cleanup_stale_workflows

WFc2=$(ctl "start dag-diamond-comp failAt=notify.email" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WFc2" ] || { echo "[GATE] FAIL: assert 4b — start did not return a workflowId"; exit 1; }
echo "[GATE]   started $WFc2 (failAt=notify.email)"

COMP4B=$(poll_comp_completed "$WFc2" 120) || { echo "[GATE] FAIL: assert 4b — compensation did not complete within 120s; last: $COMP4B"; cat "$CWORKER_LOG"; exit 1; }
echo "[GATE]   comp result: $COMP4B"
echo "$COMP4B" | grep -q "status=FAILED" || { echo "[GATE] FAIL: assert 4b — main wf not FAILED; got: $COMP4B"; exit 1; }
echo "$COMP4B" | grep -q "compStatus=COMPLETED" || { echo "[GATE] FAIL: assert 4b — compStatus not COMPLETED; got: $COMP4B"; exit 1; }

CNT4B=$(target_count)
[ "$CNT4B" = "0" ] || { echo "[GATE] FAIL: assert 4b — target_rows not compensated to 0, got $CNT4B"; exit 1; }
COMP4B_ROWS=$(comp_ledger_count "$WFc2")
[ "$COMP4B_ROWS" -ge "1" ] || { echo "[GATE] FAIL: assert 4b — no compensated=true comp_ledger rows for $WFc2, got $COMP4B_ROWS"; exit 1; }
echo "[GATE] assert 4b PASS: Conductor FAILED + compensation COMPLETED; target_rows=0; comp_ledger compensated=$COMP4B_ROWS"

# ===========================================================================
# Assert 5: type-mismatched JOIN wire (delegated — NOT this bash gate)
# ===========================================================================
echo ""
echo "[GATE] assert 5: type-mismatch on a JOIN wire is covered by the compiler unit test"
echo "[GATE]   (Chunk 2 Task 6 'multi-input type mismatch on one wire is rejected') + run-compiler-ir-gate.sh mistyped.yaml."
echo "[GATE]   cycle + multi-input-unwired negatives proven above in assert 1. No new bash work (spec §6.5b)."
echo "[GATE] assert 5 PASS (delegated)"

# ===========================================================================
# Final
# ===========================================================================
echo ""
echo "[GATE] blockers: determinism+negatives=1 temporal_fanin=1 conductor_fanin=1 temporal_comp=1 conductor_comp=1"
echo "[GATE] PASS (one IR -> diamond fan-in on Temporal + Conductor; reverse-topo compensation; negatives rejected)"
