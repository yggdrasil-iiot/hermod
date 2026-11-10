#!/usr/bin/env bash
# §9.2 crash-recovery objective proof (DoD #2): kill the worker WHILE the db.upsert activity is in
# flight, restart, and prove the workflow still completes with a consistent final state. This is the
# core Node-RED-impossible property: durable state survives process death.
#   PRIMARY signal  : workflow reaches completed=true even though the worker running it was killed
#                     mid-activity -> state lived OUTSIDE the worker (Temporal); a fresh worker resumed.
#   SECONDARY signal: final state is consistent despite Temporal's at-least-once activity retry
#                     -> idempotent UPSERT converged (exactly one row, value "X"). The
#                        "idempotency makes at-least-once safe" thesis, not the recovery itself.
#
# Run from repo root with the stack up + schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-crash-recovery.sh   # expect: [CRASH] PASS ... exit 0
#
# WINDOWS KILL NOTE: `./gradlew --no-daemon run` forks a separate JVM (mainClass yggdrasil.hermod.app.WorkerKt)
# from the wrapper. $! is the wrapper PID, NOT the JVM. We therefore resolve the actual JVM PID via
# `jps` and taskkill it by PID, then verify with jps that none survive. This makes the mid-activity
# kill meaningful (the JVM running the activity really died) and proves recovery is by a FRESH process.
set -euo pipefail
cd "$(dirname "$0")/.."

WF="crash-$(date +%s)"
SLOW_MS=8000   # db.upsert sleeps this long so the kill lands mid-activity

# Guard (b): kill every yggdrasil.hermod.app.WorkerKt JVM by resolved PID and verify none survive.
kill_worker_jvms() {
  local pids
  pids=$({ jps -l | grep "yggdrasil.hermod.app.WorkerKt" || true; } | awk '{print $1}')
  for p in $pids; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done
  sleep 2
  local left
  left=$({ jps -l | grep -c "yggdrasil.hermod.app.WorkerKt"; } || true)
  echo "[CRASH] worker JVMs after kill: $left"
}

echo "[CRASH] reset tables, seed one source row"
# Hermetic reset: also TRUNCATE source_rows so a prior gate's seed (e.g. run-dag-gate's r1/r2) can't
# leak in via db.read and inflate target_rows — this gate assumes exactly its own single A1 row.
docker compose exec -T postgres psql -U hermod -d hermod -c "TRUNCATE target_rows; TRUNCATE source_rows;" >/dev/null
docker compose exec -T postgres psql -U hermod -d hermod -c "INSERT INTO source_rows(id,val) VALUES('A1','x') ON CONFLICT(id) DO UPDATE SET val='x';" >/dev/null

echo "[CRASH] start worker w1 (--no-daemon; resolve real JVM via jps)"
HERMOD_WORKER_NAME=w1 ./gradlew -q --no-daemon run >/tmp/crash-worker.log 2>&1 &
sleep 20   # cold --no-daemon JVM start + begin polling

echo "[CRASH] start workflow $WF with slow upsert (${SLOW_MS}ms)"
./gradlew -q --no-daemon starter --args="start $WF - $SLOW_MS" >/dev/null
sleep 6    # let it run db.read + transform.map and ENTER the slow db.upsert activity

echo "[CRASH] kill worker w1 MID-ACTIVITY (guard a: by resolved JVM PID)"
kill_worker_jvms   # guard (b): asserts "worker JVMs after kill: 0" BEFORE we restart

echo "[CRASH] restart worker w1b -> Temporal redelivers the in-flight db.upsert activity task"
HERMOD_WORKER_NAME=w1b ./gradlew -q --no-daemon run >/tmp/crash-worker2.log 2>&1 &
sleep 22   # cold JVM start + redeliver + 8s upsert re-run + park at the actuate (IRREVERSIBLE) gate

echo "[CRASH] approve the actuate human gate and fetch result"
./gradlew -q --no-daemon starter --args="approve $WF" >/dev/null
RESULT=$(./gradlew -q --no-daemon starter --args="result $WF" | grep -E "completed=")
echo "[CRASH] $RESULT"

# Guard (a) evidence: the RECOVERY worker (w1b) log must show it ran the db.upsert forward, i.e. the
# kill demonstrably landed in-flight and a fresh process re-executed the activity.
if grep -q "\[w1b\] forward db.upsert" /tmp/crash-worker2.log; then
  echo "[CRASH] recovery worker re-ran the in-flight activity: [w1b] forward db.upsert"
else
  echo "[CRASH] WARN: recovery worker log did not show 'forward db.upsert' (mid-activity guard weak)"
fi

COUNT=$(docker compose exec -T postgres psql -U hermod -d hermod -tAc "SELECT count(*) FROM target_rows;" | tr -d '[:space:]')
VAL=$(docker compose exec -T postgres psql -U hermod -d hermod -tAc "SELECT val FROM target_rows WHERE id='A1';" | tr -d '[:space:]')
kill_worker_jvms

# PASS: completed after the mid-activity kill (recovery) AND consistent final state (idempotent convergence).
if echo "$RESULT" | grep -q "completed=true" \
   && [ "$COUNT" = "1" ] && [ "$VAL" = "X" ] \
   && grep -q "\[w1b\] forward db.upsert" /tmp/crash-worker2.log; then
  echo "[CRASH] PASS (completed after mid-activity kill; recovery re-ran db.upsert; target_rows=$COUNT val=$VAL)"; exit 0
else
  echo "[CRASH] FAIL (result='$RESULT', target_rows=$COUNT, val='$VAL'; see /tmp/crash-worker2.log)"; exit 1
fi
