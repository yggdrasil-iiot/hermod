#!/usr/bin/env bash
# §5 v0.3f GATE (objective proof): the Chunk-1 node-state backend slice is real and LIVE on REAL Temporal.
# GET /api/runs/{runId}/nodes reports per-node states that:
#   1 all-DONE       : on a successful diamond run every node (src,b,c,join,sink) reaches DONE.
#   2 FAILED+COMP    : on a partial failure (fail at the UNIQUE notify.email sink) the failing node reports
#                      FAILED and the compensable upstream db.upsert node (b) reports COMPENSATED.
#   3 live snapshot  : the states are observable MID-RUN (a non-terminal RUNNING/PENDING node is seen before
#                      the run completes), proving the snapshot is live, not just a final post-mortem.
#
# Temporal-only (consistent with run-compose-run-gate.sh — compile stays engine-neutral; Conductor run deferred).
# Needs Postgres 15432 + Temporal 7233 (real workflow execution). Bring-up is cloned VERBATIM from
# run-compose-run-gate.sh (env, psql_q, JVM launch/kill helpers, cleanup trap, step-0 schema/reset/seed incl.
# TRUNCATE workflow_def, worker start + diamond save + poll-bind wait). Only the ASSERTIONS differ.
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-node-states-gate.sh   # expect: [GATE] PASS run-node-states-gate.sh ... exit 0
#
# WINDOWS KILL NOTE (REF run-compose-run-gate.sh): `./gradlew run` forks a separate JVM; $! is the wrapper PID,
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
WORK="build/node-states-gate"
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
echo "[GATE] step 0: ensure registry schema (workflow_def) + reset state + seed source_rows"
# init-db.sh applies the v0.1 app schema; the registry schema (workflow_def + block_index) is NOT in init-db.
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
# TRUNCATE BEFORE the first save so re-runs don't trip the (name,version) immutability 400.
psql_q "TRUNCATE target_rows, source_rows, workflow_def" >/dev/null
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
echo "[GATE] ===== Assert 1: success run -> every node (src,b,c,join,sink) reaches DONE ====="
RUN_ID="ns-success-1"
curl -fsS -X POST "$API/api/workflows/diamond/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_ID\",\"slowMs\":600}" >/dev/null || fail "run diamond (success) failed (http)"
curl -fsS "$API/api/runs/$RUN_ID?wait=true" | grep -q '"completed":true' || fail "run not completed"
NODES=$(curl -fsS "$API/api/runs/$RUN_ID/nodes")
echo "[GATE] node states (success): $NODES"
for k in src b c join sink; do
  echo "$NODES" | grep -q "\"$k\":\"DONE\"" || fail "node $k not DONE"
done
echo "[GATE] Assert 1 OK: all nodes DONE"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 2: partial failure -> sink FAILED + compensable b COMPENSATED ====="
# Save a compensable diamond whose sink is the UNIQUE blockId notify.email (so the FAILED->node mapping is
# unambiguous) and whose b node is the compensable db.upsert. Then wait for the worker to poll-bind it.
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
# wait one poll interval + margin for the worker to bind diamond-comp@1.0.0 (assert its marker, like diamond)
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind diamond-comp@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow diamond-comp@1.0.0" "$WORKER_LOG" || fail "worker log missing '[worker] bound workflow diamond-comp@1.0.0' (poll-bind did not occur)"
echo "[GATE] worker poll-bound diamond-comp@1.0.0:"
grep "\[worker\] bound workflow diamond-comp@1.0.0" "$WORKER_LOG" | head -1

RUN_ID2="ns-fail-1"
curl -fsS -X POST "$API/api/workflows/diamond-comp/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_ID2\",\"failAtBlockId\":\"notify.email\"}" >/dev/null || fail "run diamond-comp (fail) failed (http)"
curl -fsS "$API/api/runs/$RUN_ID2?wait=true" | grep -q '"completed":false' || fail "failing run reported completed"
NODES2=$(curl -fsS "$API/api/runs/$RUN_ID2/nodes")
echo "[GATE] node states (failure): $NODES2"
echo "$NODES2" | grep -q '"sink":"FAILED"'   || fail "sink not FAILED"
echo "$NODES2" | grep -q '"b":"COMPENSATED"' || fail "b not COMPENSATED"
echo "[GATE] Assert 2 OK: sink FAILED + b COMPENSATED"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 3: live (non-terminal) snapshot observed mid-run ====="
# Timing-tolerant: slowMs widens the window so we can catch a RUNNING/PENDING node before terminal.
RUN_ID3="ns-live-1"
curl -fsS -X POST "$API/api/workflows/diamond/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_ID3\",\"slowMs\":1500}" >/dev/null || fail "run diamond (live) failed (http)"
SAW_LIVE=0
for i in $(seq 1 20); do
  S=$(curl -fsS "$API/api/runs/$RUN_ID3/nodes" || echo '{}')
  if echo "$S" | grep -qE '"(RUNNING|PENDING)"'; then SAW_LIVE=1; break; fi
  sleep 0.2
done
[ "$SAW_LIVE" = "1" ] || fail "never observed a live (RUNNING/PENDING) node state"
curl -fsS "$API/api/runs/$RUN_ID3?wait=true" | grep -q '"completed":true' || fail "live-snapshot run did not complete"
echo "[GATE] observed live node state mid-run"

# ---------------------------------------------------------------------------
echo "[GATE] PASS run-node-states-gate.sh"
exit 0
