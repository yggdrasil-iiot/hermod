#!/usr/bin/env bash
# §5 v0.4b GATE (objective proof): operator INTERVENTIONS on REAL Temporal. On an opt-in interactive run a
# failed node PARKS for an operator decision instead of auto-compensating; the operator can RETRY it (recover a
# transient failure) or ABORT the run (graceful reverse-topo compensation). The default (non-interactive) run
# still auto-compensates with no operator (no hang).
#   1 park-on-failure  : interactive run, faulted sink reaches PARKED; upstream upsert DONE; nothing COMPENSATED;
#                        run still RUNNING (proves failure parked, did NOT auto-unwind).
#   2 retry recovers   : disarm fault + POST /retry{nodeId:sink} -> sink DONE -> run COMPLETED.
#   3 abort unwinds    : re-arm + second interactive run -> PARKED -> POST /abort -> mid COMPENSATED, target clean.
#   4 non-interactive  : run with failAtBlockId, NO interactive -> auto-compensates to completed=false, no signal.
#
# Temporal-only (consistent with run-console-gate.sh). Needs Postgres 15432 + Temporal 7233. Bring-up is cloned
# from run-console-gate.sh (env, psql_q, JVM launch/kill helpers, cleanup trap, step-0 schema/reset/seed, worker
# start + poll-bind wait) with HERMOD_FAULT_INJECT=1 exported so the worker honors the fault_inject toggle.
# Boundary + full prior-gate regression are controller-verified (Chunk 6), not in-script.
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-intervention-gate.sh   # expect: [GATE] PASS run-intervention-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1   # v0.4b: arm the worker's test-only fault_inject toggle

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/intervention-gate"
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
echo "[GATE] step 0: ensure schemas (registry + fault_inject) + reset state + seed source_rows"
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
echo "[GATE] step 3: save intervention@1.0.0 + wait for the LIVE worker to poll-bind it (no restart)"
SAVE_CODE=$(curl -s -o "$WORK/save_intv.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/intervention.json")
echo "[GATE] save intervention@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save_intv.json")"
[ "$SAVE_CODE" = "200" ] || fail "save intervention@1.0.0 expected 200, got $SAVE_CODE"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind intervention@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow intervention@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind intervention@1.0.0"
echo "[GATE] worker poll-bound intervention@1.0.0 WITHOUT restart"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 1: failure PARKS (interactive) instead of auto-compensating ====="
psql_q "INSERT INTO fault_inject(block_id) VALUES ('transform.map') ON CONFLICT DO NOTHING" >/dev/null
RUN_PARK="intv-park-1"
curl -fsS -X POST "$API/api/workflows/intervention/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_PARK\",\"interactive\":true}" >/dev/null || fail "start interactive run failed"
PARKED=0; NS=""
for i in $(seq 1 40); do
  NS=$(curl -fsS "$API/api/runs/$RUN_PARK/nodes" || true)
  if echo "$NS" | grep -q '"sink":"PARKED"'; then PARKED=1; break; fi
  sleep 1
done
[ "$PARKED" = "1" ] || fail "sink never reached PARKED (states=$NS)"
echo "$NS" | grep -q '"mid":"DONE"' || fail "upstream upsert (mid) should be DONE before the sink parks (states=$NS)"
if echo "$NS" | grep -q 'COMPENSATED'; then fail "nothing should be COMPENSATED while parked (no auto-unwind) (states=$NS)"; fi
curl -fsS "$API/api/runs/$RUN_PARK" | grep -qE '"status":"WORKFLOW_EXECUTION_STATUS_RUNNING"' || fail "parked run should still be RUNNING"
echo "[GATE] Assert 1 OK: failed node PARKED, run still RUNNING, nothing compensated"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 2: operator RETRY recovers the run ====="
psql_q "DELETE FROM fault_inject WHERE block_id='transform.map'" >/dev/null   # disarm (transient cause cleared)
curl -fsS -X POST "$API/api/runs/$RUN_PARK/retry" -H 'Content-Type: application/json' -d '{"nodeId":"sink"}' >/dev/null || fail "retry signal failed"
curl -fsS "$API/api/runs/$RUN_PARK?wait=true" | grep -q '"completed":true' || fail "run did not complete after retry"
curl -fsS "$API/api/runs/$RUN_PARK/nodes" | grep -q '"sink":"DONE"' || fail "sink not DONE after retry"
[ "$(psql_q "SELECT count(*) FROM target_rows")" != "0" ] || fail "expected upstream upsert rows to persist through retry"
echo "[GATE] Assert 2 OK: retry resumed the parked node -> COMPLETED"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 3: operator ABORT unwinds (reverse-topo compensation) ====="
psql_q "TRUNCATE target_rows" >/dev/null
psql_q "INSERT INTO fault_inject(block_id) VALUES ('transform.map') ON CONFLICT DO NOTHING" >/dev/null   # re-arm
RUN_ABORT="intv-abort-1"
curl -fsS -X POST "$API/api/workflows/intervention/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_ABORT\",\"interactive\":true}" >/dev/null || fail "start abort-run failed"
PARKED2=0
for i in $(seq 1 40); do
  if curl -fsS "$API/api/runs/$RUN_ABORT/nodes" | grep -q '"sink":"PARKED"'; then PARKED2=1; break; fi
  sleep 1
done
[ "$PARKED2" = "1" ] || fail "abort-run sink never PARKED"
curl -fsS -X POST "$API/api/runs/$RUN_ABORT/abort" >/dev/null || fail "abort signal failed"
curl -fsS "$API/api/runs/$RUN_ABORT?wait=true" | grep -q '"completed":false' || fail "aborted run should report completed=false"
curl -fsS "$API/api/runs/$RUN_ABORT/nodes" | grep -q '"mid":"COMPENSATED"' || fail "mid (db.upsert) should be COMPENSATED after abort"
[ "$(psql_q "SELECT count(*) FROM target_rows")" = "0" ] || fail "target_rows not cleaned by reverse-topo compensation"
psql_q "DELETE FROM fault_inject WHERE block_id='transform.map'" >/dev/null
echo "[GATE] Assert 3 OK: abort -> reverse-topo compensation, target_rows cleaned"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 4: non-interactive run still AUTO-compensates (no operator, no hang) ====="
psql_q "TRUNCATE target_rows" >/dev/null
RUN_AUTO="intv-auto-1"
curl -fsS -X POST "$API/api/workflows/intervention/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_AUTO\",\"failAtBlockId\":\"transform.map\"}" >/dev/null || fail "start non-interactive run failed"
curl -fsS "$API/api/runs/$RUN_AUTO?wait=true" | grep -q '"completed":false' || fail "non-interactive failing run should auto-compensate to completed=false"
curl -fsS "$API/api/runs/$RUN_AUTO/nodes" | grep -q '"mid":"COMPENSATED"' || fail "non-interactive run should auto-compensate mid"
echo "[GATE] Assert 4 OK: default (non-interactive) model unchanged — auto-compensated with no operator action"

# ---------------------------------------------------------------------------
echo "[GATE] PASS run-intervention-gate.sh"
exit 0
