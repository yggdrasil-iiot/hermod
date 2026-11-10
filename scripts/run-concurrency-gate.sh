#!/usr/bin/env bash
# v0.3b CONCURRENCY GATE (objective): independent branches run CONCURRENTLY on Temporal + reverse-topo
# compensation under partial parallel failure. PASS = 3 hard asserts. Temporal-only (Conductor = v0.3c).
set -euo pipefail
cd "$(dirname "$0")/.."
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}" HERMOD_DB_USER=hermod HERMOD_DB_PASS=hermod
WORKER_LOG=/tmp/concurrency-gate-worker.log

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -t -c "$1" | tr -d ' \n\r'; }
kill_worker_jvms() { { jps -l | grep yggdrasil.hermod.app.WorkerKt || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
trap kill_worker_jvms EXIT

# poll `starter result <id>` until it prints completed= (terminal), then echo the line
poll_temporal_result() {
  local wf="$1" deadline=$(( $(date +%s) + 120 )) out
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(./gradlew -q --no-daemon starter --args="result $wf" 2>/dev/null | grep -E "completed=" || true)
    [ -n "$out" ] && { echo "$out"; return 0; }
    sleep 3
  done
  return 1
}

# Block until the worker log contains $1 occurrences of $2 (a grep pattern), then echo the wall-clock
# epoch-seconds at which that count was first reached. Returns 1 on timeout. We measure concurrency from
# the WORKER LOG (the workflow's intrinsic execution), NOT from gradle call wall-clock: each
# `./gradlew --no-daemon` invocation carries ~30s of fixed JVM/config overhead that swamps a single-digit
# second sleep, and the async workflow runs DURING the start call's own startup window. The worker-log
# marker timestamps are immune to that and reflect real activity-thread scheduling.
wait_for_count() {
  local want="$1" pat="$2" deadline=$(( $(date +%s) + 90 )) n
  while [ "$(date +%s)" -lt "$deadline" ]; do
    # grep -c exits 1 with "0" on no match; pipe through wc -l on the matching lines for a clean integer.
    n=$(grep -c -e "$pat" "$WORKER_LOG" 2>/dev/null) || n=0
    [ "$n" -ge "$want" ] && { date +%s; return 0; }
    sleep 1
  done
  return 1
}

echo "[GATE] boot Temporal app worker..."
kill_worker_jvms
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=conc ./gradlew -q --no-daemon run >"$WORKER_LOG" 2>&1 &
sleep 22

# ---- assert 1: actual concurrency (worker-log wall-clock overlap; slow ONLY the parallel branches) ----
# conc-fanout fans out into 3 transform.map branches (p1,p2,p3) that all read src.rows independently, then
# merge -> db.upsert sink. We slow ONLY transform.map (slowAtBlockId) by SLOW_MS each. We measure the window
# from the FIRST "forward transform.map" marker to the "forward db.upsert" (sink) marker:
#   - CONCURRENT: all 3 branches enter ~together at t0, drain at t0+SLOW_MS, merge, sink enters ~t0+SLOW_MS
#                 -> window ~= 1*SLOW_MS.
#   - SEQUENTIAL: branches run one-after-another (topo order p1,p2,p3 precede the sink), so the sink enters
#                 ~t0+3*SLOW_MS -> window ~= 3*SLOW_MS.
# (We measure from the FIRST transform marker, NOT the 3rd: a 3rd-marker-to-sink window is ~1*SLOW_MS in BOTH
#  cases and would NOT discriminate.) Worker-log markers are immune to per-call gradle overhead (see
#  wait_for_count). Threshold sits between 1x and 3x SLOW_MS so only genuine overlap can pass.
SLOW_MS=12000
echo "[GATE] assert 1: 3 fan-out transform.map branches run concurrently (worker-log overlap, slowMs=${SLOW_MS})"
psql_q "TRUNCATE source_rows;" >/dev/null
psql_q "TRUNCATE target_rows;"  >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','x'),('r2','y') ON CONFLICT DO NOTHING;" >/dev/null
WF1="conc-$(date +%s)"
# Fresh log so the marker counts below are unambiguous for THIS run (boot lines are pre-truncation).
: > "$WORKER_LOG"
# positional: start <id> <failAt|-> <slowMs> <workflowName> <slowAtBlockId>
./gradlew -q --no-daemon starter --args="start $WF1 - $SLOW_MS conc-fanout transform.map" >/dev/null
# T_FIRST: the FIRST parallel transform.map activity entered forward() (1st "forward transform.map").
T_FIRST=$(wait_for_count 1 "forward transform.map") || { echo "[GATE] FAIL: transform.map branches never started"; cat "$WORKER_LOG"; exit 1; }
# T_SINK: the post-merge db.upsert sink entered forward() -> the slow branches feeding the join have drained.
T_SINK=$(wait_for_count 1 "forward db.upsert") || { echo "[GATE] FAIL: sink db.upsert never reached (branches stuck)"; cat "$WORKER_LOG"; exit 1; }
FAN_ELAPSED=$((T_SINK - T_FIRST))
R1=$(poll_temporal_result "$WF1") || { echo "[GATE] FAIL: conc-fanout did not finish"; cat "$WORKER_LOG"; exit 1; }
echo "$R1" | grep -q "completed=true" || { echo "[GATE] FAIL: conc-fanout not completed; $R1"; exit 1; }
# 3 parallel transform.map @ 12s each: concurrent first-to-sink ~12s, sequential ~36s. Threshold 24s
# (= 2*SLOW_MS) sits strictly between 1x and 3x: concurrent passes, any serialization (~36s) fails.
[ "$FAN_ELAPSED" -lt 24 ] || { echo "[GATE] FAIL: fan-out first-to-sink ${FAN_ELAPSED}s >= 24s — branches did NOT overlap (sequential)"; cat "$WORKER_LOG"; exit 1; }
echo "[GATE] assert 1 PASS: first transform.map -> sink in ${FAN_ELAPSED}s (< 24s -> concurrent; sequential would be ~36s); $R1"

# ---- assert 2: reverse-topological compensation under partial parallel failure ----
echo "[GATE] assert 2: partial failure -> both concurrent branches compensated (reverse-topo)"
psql_q "TRUNCATE source_rows;" >/dev/null
psql_q "TRUNCATE target_rows;"  >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','x'),('r2','y') ON CONFLICT DO NOTHING;" >/dev/null
WF2="conc-comp-$(date +%s)"
./gradlew -q --no-daemon starter --args="start $WF2 notify.email 0 conc-comp" >/dev/null
R2=$(poll_temporal_result "$WF2") || { echo "[GATE] FAIL: conc-comp did not finish"; cat "$WORKER_LOG"; exit 1; }
echo "$R2" | grep -q "completed=false" || { echo "[GATE] FAIL: conc-comp should not complete; $R2"; exit 1; }
# BOTH concurrent compensable branches must be compensated -> two db.upsert entries (not one)
NCOMP=$(echo "$R2" | grep -o 'db.upsert' | wc -l | tr -d ' ')
[ "$NCOMP" -ge 2 ] || { echo "[GATE] FAIL: expected 2 db.upsert compensations, got $NCOMP; $R2"; exit 1; }
T1=$(psql_q "SELECT COUNT(*) FROM target_rows;")
[ "$T1" = "0" ] || { echo "[GATE] FAIL: branches not compensated (target_rows=$T1)"; exit 1; }
echo "[GATE] assert 2 PASS: $R2 ; ${NCOMP} db.upsert compensations, target_rows=$T1 (both concurrent upserts undone)"

# ---- assert 3: back-compat — linear demo still runs through the promise graph ----
echo "[GATE] assert 3: back-compat linear demo via the promise graph"
psql_q "TRUNCATE source_rows;" >/dev/null; psql_q "TRUNCATE target_rows;" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('A1','x') ON CONFLICT DO NOTHING;" >/dev/null
WF3="conc-demo-$(date +%s)"
./gradlew -q --no-daemon starter --args="start $WF3 - 0 demo" >/dev/null
sleep 6
./gradlew -q --no-daemon starter --args="approve $WF3" >/dev/null
R3=$(poll_temporal_result "$WF3") || { echo "[GATE] FAIL: demo did not finish"; exit 1; }
echo "$R3" | grep -q "completed=true" || { echo "[GATE] FAIL: linear demo regressed; $R3"; exit 1; }
echo "[GATE] assert 3 PASS: linear demo completed via promise graph"

kill_worker_jvms
echo "[GATE] blockers: concurrency=1 partial_failure_comp=1 backcompat_linear=1"
echo "[GATE] PASS (Temporal branches run concurrently; reverse-topo compensation under partial failure)"
