#!/usr/bin/env bash
# §5 v0.4a GATE (objective proof): the operator console's run-history persistence + list/detail seams are
# real and LIVE on REAL Temporal. After a run, GET /api/runs lists it (persisted in run_index) and the
# existing detail seams (GET /api/runs/{id}, /nodes) answer for it — including a PAST failure's reverse-topo
# compensation lighting, observable after the fact, and a cross-run (newest-first) history.
#   1 recorded+listed : a successful diamond run lands in run_index AND in GET /api/runs.
#   2 detail seams     : GET /api/runs/{id} status + /nodes (all DONE) answer for the recorded run.
#   3 past failure     : a failed run (unique notify.email sink) is recorded; its /nodes shows sink FAILED +
#                        compensable b COMPENSATED (reverse-topo) — observable from the console after the fact.
#   4 cross-run        : GET /api/runs lists BOTH runs, newest-first.
#
# Temporal-only (consistent with run-node-states-gate.sh / run-compose-run-gate.sh — compile stays
# engine-neutral; Conductor run-tracking deferred). Needs Postgres 15432 + Temporal 7233. Bring-up is cloned
# VERBATIM from run-node-states-gate.sh (env, psql_q, JVM launch/kill helpers, cleanup trap, step-0
# schema/reset/seed incl. TRUNCATE workflow_def, worker start + diamond save + poll-bind wait). Only the
# ASSERTIONS differ. Boundary + full prior-gate regression are controller-verified (Task 5.2), not in-script.
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-console-gate.sh   # expect: [GATE] PASS run-console-gate.sh ... exit 0
#
# WINDOWS KILL NOTE (REF run-node-states-gate.sh): `./gradlew run` forks a separate JVM; $! is the wrapper PID,
# not the JVM. We resolve the real JVM via `jps` and taskkill by PID.
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: pin the plugin store dir + DB + poll interval so the forked worker JVM agrees with this
# --- shell (forwardHermodEnv in app/build.gradle.kts propagates HERMOD_* incl. HERMOD_WF_POLL_MS). ---
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/console-gate"
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

echo "[GATE] db = $HERMOD_DB_URL ; poll = ${HERMOD_WF_POLL_MS}ms"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: ensure registry schema (workflow_def + run_index) + reset state + seed source_rows"
# init-db.sh applies the v0.1 app schema; the registry schema (workflow_def + block_index + run_index) is NOT in init-db.
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
# TRUNCATE BEFORE the first save so re-runs don't trip the (name,version) immutability 400. run_index too so counts are clean.
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start the worker (background) BEFORE any workflow is composed; capture log; then start API"
: > "$WORKER_LOG"
# --no-daemon so the launcher (and forked run JVM) inherits THIS shell's HERMOD_* env (incl. HERMOD_WF_POLL_MS).
HERMOD_WORKER_NAME=gate-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
# wait for the worker to actually start polling Temporal (classpath YAML bind + factory.start)
WUP=0
for i in $(seq 1 60); do
  if grep -q "starting; polling" "$WORKER_LOG" 2>/dev/null; then WUP=1; break; fi
  sleep 2
done
[ "$WUP" = "1" ] || fail "worker did not reach 'starting; polling' within ~120s"
echo "[GATE] worker up (started before any workflow_def was composed)"

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
echo "[GATE] step 3: save diamond@1.0.0 + wait for the LIVE worker to poll-bind it (no restart)"
SAVE_CODE=$(curl -s -o "$WORK/save_diamond.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/diamond.json")
echo "[GATE] save diamond@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save_diamond.json")"
[ "$SAVE_CODE" = "200" ] || fail "save diamond@1.0.0 expected 200, got $SAVE_CODE"
# Sleep one poll interval + margin so the already-running worker's background poll binds the new NAME.
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s (one poll interval + margin) for the worker to poll-bind diamond@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow diamond@1.0.0" "$WORKER_LOG" || fail "worker log missing '[worker] bound workflow diamond@1.0.0' (poll-bind did not occur)"
echo "[GATE] worker poll-bound diamond@1.0.0 WITHOUT restart:"
grep "\[worker\] bound workflow diamond@1.0.0" "$WORKER_LOG" | head -1

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 1: a successful run is recorded in run_index AND listed by GET /api/runs ====="
RUN_OK="console-ok-1"
curl -fsS -X POST "$API/api/workflows/diamond/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_OK\",\"slowMs\":600}" >/dev/null || fail "run diamond (success) failed (http)"
curl -fsS "$API/api/runs/$RUN_OK?wait=true" | grep -q '"completed":true' || fail "ok run not completed"
# persisted in the table
ROWS=$(psql_q "SELECT count(*) FROM run_index WHERE run_id='$RUN_OK'")
[ "$ROWS" = "1" ] || fail "run_index missing row for $RUN_OK (got count=$ROWS)"
# listed by the API with the right name/version and a resolved status
RUNS=$(curl -fsS "$API/api/runs")
echo "[GATE] GET /api/runs => $RUNS"
echo "$RUNS" | grep -q "\"runId\":\"$RUN_OK\"" || fail "GET /api/runs did not list $RUN_OK"
echo "$RUNS" | grep -q "\"name\":\"diamond\""  || fail "listed run missing name=diamond"
echo "[GATE] Assert 1 OK: $RUN_OK persisted + listed"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 2: detail seams answer for the recorded run ====="
# queryStatus returns the Temporal proto enum NAME (e.g. WORKFLOW_EXECUTION_STATUS_COMPLETED), not a short form.
curl -fsS "$API/api/runs/$RUN_OK" | grep -qE '"status":"(WORKFLOW_EXECUTION_STATUS_(COMPLETED|RUNNING)|UNKNOWN)"' || fail "GET /api/runs/{id} returned no status"
NODES=$(curl -fsS "$API/api/runs/$RUN_OK/nodes")
echo "[GATE] node states (ok): $NODES"
for k in src b c join sink; do
  echo "$NODES" | grep -q "\"$k\":\"DONE\"" || fail "node $k not DONE on recorded run"
done
echo "[GATE] Assert 2 OK: status + per-node detail answer for $RUN_OK"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 3: a past FAILURE is recorded + its compensation lighting is observable after the fact ====="
# Save the compensable diamond (unique notify.email sink so FAILED->node is unambiguous; b = compensable db.upsert).
SAVE_CODE2=$(curl -s -o "$WORK/save_comp.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{"name":"diamond-comp","steps":[
  {"blockId":"db.read","pinnedVersion":"1.0.0","id":"src","params":{"table":"source_rows"}},
  {"blockId":"db.upsert","pinnedVersion":"1.2.0","id":"b","params":{"table":"target_rows"},"wiring":{"rows":"src.rows"}},
  {"blockId":"transform.map","pinnedVersion":"1.0.0","id":"c","wiring":{"rows":"src.rows"}},
  {"blockId":"merge","pinnedVersion":"1.0.0","id":"join","wiring":{"left":"b.written","right":"c.rows"}},
  {"blockId":"notify.email","pinnedVersion":"1.0.0","id":"sink","wiring":{"rows":"join.out"}}
]}
JSON
)
echo "[GATE] save diamond-comp@1.0.0 http=$SAVE_CODE2 body=$(cat "$WORK/save_comp.json")"
[ "$SAVE_CODE2" = "200" ] || fail "save diamond-comp@1.0.0 expected 200, got $SAVE_CODE2"
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind diamond-comp@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow diamond-comp@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind diamond-comp@1.0.0"

RUN_FAIL="console-fail-1"
curl -fsS -X POST "$API/api/workflows/diamond-comp/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_FAIL\",\"failAtBlockId\":\"notify.email\"}" >/dev/null || fail "run diamond-comp (fail) failed (http)"
curl -fsS "$API/api/runs/$RUN_FAIL?wait=true" | grep -q '"completed":false' || fail "failing run reported completed"
ROWS2=$(psql_q "SELECT count(*) FROM run_index WHERE run_id='$RUN_FAIL'")
[ "$ROWS2" = "1" ] || fail "run_index missing row for $RUN_FAIL (got count=$ROWS2)"
NODES2=$(curl -fsS "$API/api/runs/$RUN_FAIL/nodes")
echo "[GATE] node states (fail): $NODES2"
echo "$NODES2" | grep -q '"sink":"FAILED"'   || fail "sink not FAILED on recorded failure run"
echo "$NODES2" | grep -q '"b":"COMPENSATED"' || fail "b not COMPENSATED on recorded failure run"
echo "[GATE] Assert 3 OK: past failure recorded + reverse-topo compensation observable from the console seams"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 4: cross-run list shows BOTH runs newest-first ====="
RUNS2=$(curl -fsS "$API/api/runs")
echo "[GATE] GET /api/runs (cross-run) => $RUNS2"
echo "$RUNS2" | grep -q "\"runId\":\"$RUN_OK\""   || fail "cross-run list missing $RUN_OK"
echo "$RUNS2" | grep -q "\"runId\":\"$RUN_FAIL\"" || fail "cross-run list missing $RUN_FAIL"
# newest-first: the failure run (started later) must appear before the success run in the JSON array.
POS_FAIL=$(echo "$RUNS2" | grep -bo "\"runId\":\"$RUN_FAIL\"" | head -1 | cut -d: -f1)
POS_OK=$(echo "$RUNS2" | grep -bo "\"runId\":\"$RUN_OK\"" | head -1 | cut -d: -f1)
[ -n "$POS_FAIL" ] && [ -n "$POS_OK" ] && [ "$POS_FAIL" -lt "$POS_OK" ] || fail "list not newest-first ($RUN_FAIL should precede $RUN_OK)"
echo "[GATE] Assert 4 OK: cross-run history, newest-first"

# ---------------------------------------------------------------------------
echo "[GATE] PASS run-console-gate.sh"
exit 0
