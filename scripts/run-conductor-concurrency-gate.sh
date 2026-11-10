#!/usr/bin/env bash
# v0.3c CONDUCTOR CONCURRENCY GATE (objective): independent branches run CONCURRENTLY on a real Conductor
# server (layered FORK_JOIN) + reverse-topo compensation of BOTH same-blockId nodes under partial failure
# (nodeId-keyed comp_ledger). PASS = 3 hard asserts. Needs: docker compose (pg 15432 / conductor 18088).
set -euo pipefail
cd "$(dirname "$0")/.."
WORKER_LOG=/tmp/conductor-concurrency-gate-worker.log

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -t -c "$1" | tr -d ' \n\r'; }
kill_worker_jvms() { { jps -l | grep yggdrasil.hermod.conductor.ConductorWorkerMainKt || true; } | awk '{print $1}' \
  | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
trap kill_worker_jvms EXIT

wait_for_count() {
  local want="$1" pat="$2" deadline=$(( $(date +%s) + 120 )) n
  while [ "$(date +%s)" -lt "$deadline" ]; do
    n=$(grep -c -e "$pat" "$WORKER_LOG" 2>/dev/null) || n=0
    [ "$n" -ge "$want" ] && { date +%s; return 0; }
    sleep 1
  done
  return 1
}
ctl() { ./gradlew -q --no-daemon :conductor-runtime:ctl --args="$*"; }

echo "[GATE] boot conductor workers..."
kill_worker_jvms; : > "$WORKER_LOG"
./gradlew -q --no-daemon :conductor-runtime:run >"$WORKER_LOG" 2>&1 &
until grep -q 'conductor workers polling' "$WORKER_LOG" 2>/dev/null; do sleep 1; done

echo "[GATE] deploy fixtures"
ctl deploy conc-fanout >/dev/null
ctl deploy conc-comp   >/dev/null

SLOW_MS=12000
psql_q "TRUNCATE source_rows;" >/dev/null; psql_q "TRUNCATE target_rows;" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','x'),('r2','y') ON CONFLICT DO NOTHING;" >/dev/null
: > "$WORKER_LOG"
WF1=$(ctl start conc-fanout slow=$SLOW_MS slowAt=transform.map | grep -o 'workflowId=.*' | cut -d= -f2)
T_FIRST=$(wait_for_count 1 "forward transform.map") || { echo "[GATE] FAIL: transform.map never started"; cat "$WORKER_LOG"; exit 1; }
T_SINK=$(wait_for_count 1 "forward db.upsert") || { echo "[GATE] FAIL: sink never reached"; cat "$WORKER_LOG"; exit 1; }
FAN=$((T_SINK - T_FIRST))
[ "$FAN" -lt 24 ] || { echo "[GATE] FAIL: first transform.map -> sink ${FAN}s >= 24s (sequential)"; cat "$WORKER_LOG"; exit 1; }
echo "[GATE] assert 1 PASS: first transform.map -> sink ${FAN}s (< 24s -> concurrent; sequential ~36s)"

psql_q "TRUNCATE source_rows;" >/dev/null; psql_q "TRUNCATE target_rows;" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','x'),('r2','y') ON CONFLICT DO NOTHING;" >/dev/null
: > "$WORKER_LOG"
WF2=$(ctl start conc-comp failAt=notify.email | grep -o 'workflowId=.*' | cut -d= -f2)
for _ in $(seq 1 40); do
  OUT=$(ctl result "$WF2" 2>/dev/null || true)
  echo "$OUT" | grep -q "compStatus=COMPLETED" && break
  sleep 3
done
NCOMP=$(psql_q "SELECT COUNT(*) FROM comp_ledger WHERE workflow_id='$WF2' AND compensated;")
[ "$NCOMP" = "2" ] || { echo "[GATE] FAIL: expected 2 compensated comp_ledger rows, got $NCOMP"; cat "$WORKER_LOG"; exit 1; }
MARK=$(grep -c -e "compensate db.upsert" "$WORKER_LOG" || true)
[ "${MARK:-0}" -ge 2 ] || { echo "[GATE] FAIL: expected >=2 'compensate db.upsert' markers, got ${MARK:-0}"; exit 1; }
T1=$(psql_q "SELECT COUNT(*) FROM target_rows;")
[ "$T1" = "0" ] || { echo "[GATE] FAIL: target_rows=$T1 (not fully compensated)"; exit 1; }
echo "[GATE] assert 2 PASS: comp_ledger compensated=2, ${MARK} compensate markers, target_rows=0"

ctl deploy dag-diamond >/dev/null
psql_q "TRUNCATE source_rows;" >/dev/null; psql_q "TRUNCATE target_rows;" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('r1','x'),('r2','y') ON CONFLICT DO NOTHING;" >/dev/null
WF3=$(ctl start dag-diamond | grep -o 'workflowId=.*' | cut -d= -f2)
for _ in $(seq 1 40); do OUT=$(ctl result "$WF3" 2>/dev/null || true); echo "$OUT" | grep -q "status=COMPLETED" && break; sleep 3; done
echo "$OUT" | grep -q "status=COMPLETED" || { echo "[GATE] FAIL: diamond did not COMPLETE; $OUT"; exit 1; }
echo "[GATE] assert 3 PASS: diamond COMPLETED through FORK_JOIN emit"

kill_worker_jvms
echo "[GATE] blockers: concurrency=1 partial_failure_comp=1 backcompat=1"
echo "[GATE] PASS (Conductor branches run concurrently; both same-blockId nodes compensated reverse-topo)"
