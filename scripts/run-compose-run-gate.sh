#!/usr/bin/env bash
# §15 v0.3e GATE (objective proof): the OPERATOR workflow compose -> validate -> save(=deploy) -> run-on-
# engine control plane is real and LIVE, and a NEWLY-COMPOSED workflow runs on an ALREADY-RUNNING worker
# WITHOUT a restart (the v0.3e headline: deploy -> background poll-bind -> run, one worker process).
#
# This is the Node-RED-impossible property for v0.3e: an operator composes a brand-new workflow NAME via the
# API, saves it, and it becomes runnable on a long-lived worker via a 3s background poll — no Temporal type
# re-registration, no worker bounce. Run-trigger is Temporal-only (compile stays engine-neutral; Conductor
# run deferred). Needs Postgres 15432 + Temporal 7233 (real workflow execution).
#
# Five hard asserts (ALL must hold for PASS):
#   1 validate : cycle.json -> valid:false + diagnostic; unwired.json -> valid:false; diamond.json ->
#                valid:true with nodeCount==5.
#   2 save+immut : POST diamond@1.0.0 -> 200; workflow_def has exactly 1 row for diamond@1.0.0; re-POST -> 400.
#   3 ★ poll-bind+run (NO worker restart): worker was started BEFORE the workflow existed; after one poll
#                interval the worker log shows `[worker] bound workflow diamond@1.0.0`; run diamond -> the
#                GET .../run?wait=true completed boolean is true; target_rows count > 0 (the composed sink wrote).
#   4 human-gate : save+run gated.json (ends in IRREVERSIBLE actuate); GET .../runs shows RUNNING (parked at
#                the approval gate); approve -> wait=true completed:true.
#   5 boundary : built :core/:registry/:compiler/:dispatch jars carry 0 org.springframework (Spring confined
#                to :authoring-api edge — the v0.3d assert, unchanged).
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-compose-run-gate.sh   # expect: [GATE] PASS ... exit 0
#
# WINDOWS KILL NOTE (REF run-add-block-gate.sh / run-authoring-gate.sh): `./gradlew run` forks a separate JVM;
# $! is the wrapper PID, not the JVM. We resolve the real JVM via `jps` and taskkill by PID.
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
WORK="build/compose-run-gate"
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
echo "[GATE] ===== Assert 1: validate cycle/unwired -> valid:false ; diamond -> valid:true nodeCount==5 ====="
curl -sf -X POST "$API/api/workflows/validate" -H 'Content-Type: application/json' --data-binary "@$FIX/cycle.json" -o "$WORK/v_cycle.json" || fail "validate cycle failed (http)"
curl -sf -X POST "$API/api/workflows/validate" -H 'Content-Type: application/json' --data-binary "@$FIX/unwired.json" -o "$WORK/v_unwired.json" || fail "validate unwired failed (http)"
curl -sf -X POST "$API/api/workflows/validate" -H 'Content-Type: application/json' --data-binary "@$FIX/diamond.json" -o "$WORK/v_diamond.json" || fail "validate diamond failed (http)"
echo "[GATE] cycle=$(cat "$WORK/v_cycle.json")"
echo "[GATE] unwired=$(cat "$WORK/v_unwired.json")"
echo "[GATE] diamond=$(cat "$WORK/v_diamond.json")"
python - "$WORK/v_cycle.json" "$WORK/v_unwired.json" "$WORK/v_diamond.json" <<'PY' || fail "Assert 1: validate results not as expected"
import sys, json
cyc, unw, dia = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:4])
assert cyc["valid"] is False and cyc["diagnostics"], f"cycle should be invalid w/ diagnostic: {cyc}"
assert unw["valid"] is False and unw["diagnostics"], f"unwired should be invalid w/ diagnostic: {unw}"
assert dia["valid"] is True, f"diamond should be valid: {dia}"
assert dia["nodeCount"] == 5, f"diamond nodeCount expected 5, got {dia['nodeCount']}"
print(f"[GATE] validate OK: cycle.valid={cyc['valid']} unwired.valid={unw['valid']} diamond.valid={dia['valid']} nodeCount={dia['nodeCount']}")
PY

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 2: save diamond@1.0.0 -> 200; workflow_def count==1; re-POST -> 400 ====="
SAVE_CODE=$(curl -s -o "$WORK/save1.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/diamond.json")
echo "[GATE] save diamond@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save1.json")"
[ "$SAVE_CODE" = "200" ] || fail "save diamond@1.0.0 expected 200, got $SAVE_CODE"
WF_COUNT=$(psql_q "SELECT count(*) FROM workflow_def WHERE name='diamond' AND version='1.0.0'")
echo "[GATE] workflow_def count for diamond@1.0.0 = $WF_COUNT"
[ "$WF_COUNT" = "1" ] || fail "expected exactly 1 workflow_def row for diamond@1.0.0, got $WF_COUNT"
RESAVE_CODE=$(curl -s -o "$WORK/save_dup.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/diamond.json")
echo "[GATE] re-save diamond@1.0.0 http=$RESAVE_CODE body=$(cat "$WORK/save_dup.json")"
[ "$RESAVE_CODE" = "400" ] || fail "re-save diamond@1.0.0 expected 400 (immutable), got $RESAVE_CODE"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 3 ★: poll-bind (no worker restart) -> run diamond -> completed:true ; target_rows>0 ====="
# Sleep one poll interval + margin so the already-running worker's background poll binds the new NAME.
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s (one poll interval + margin) for the worker to poll-bind diamond@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow diamond@1.0.0" "$WORKER_LOG" || fail "worker log missing '[worker] bound workflow diamond@1.0.0' (poll-bind did not occur)"
echo "[GATE] worker poll-bound diamond@1.0.0 WITHOUT restart:"
grep "\[worker\] bound workflow diamond@1.0.0" "$WORKER_LOG" | head -1

RUN_CODE=$(curl -s -o "$WORK/run_diamond.json" -w '%{http_code}' -X POST "$API/api/workflows/diamond/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"runId":"gate-diamond-1"}')
echo "[GATE] run diamond http=$RUN_CODE body=$(cat "$WORK/run_diamond.json")"
[ "$RUN_CODE" = "200" ] || fail "run diamond expected 200, got $RUN_CODE"
curl -sf "$API/api/runs/gate-diamond-1?wait=true" -o "$WORK/wait_diamond.json" || fail "GET /api/runs/gate-diamond-1?wait=true failed"
echo "[GATE] diamond wait result=$(cat "$WORK/wait_diamond.json")"
python - "$WORK/wait_diamond.json" <<'PY' || fail "Assert 3: diamond did not complete"
import sys, json
r = json.load(open(sys.argv[1], encoding="utf-8"))
assert r["completed"] is True, f"diamond completed expected true, got {r}"
print(f"[GATE] diamond run completed={r['completed']} (no worker restart)")
PY
TGT=$(psql_q "SELECT count(*) FROM target_rows")
echo "[GATE] target_rows count after diamond = $TGT"
[ "$TGT" -gt 0 ] 2>/dev/null || fail "expected target_rows > 0 (composed sink wrote), got $TGT"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 4: human-gate run gated.json -> parked RUNNING -> approve -> completed:true ====="
SAVE_G=$(curl -s -o "$WORK/save_gated.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/gated.json")
echo "[GATE] save gated@1.0.0 http=$SAVE_G body=$(cat "$WORK/save_gated.json")"
[ "$SAVE_G" = "200" ] || fail "save gated@1.0.0 expected 200, got $SAVE_G"
# wait one poll interval + margin for the worker to bind gated@1.0.0
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind gated@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow gated@1.0.0" "$WORKER_LOG" || fail "worker log missing '[worker] bound workflow gated@1.0.0'"
RUN_G=$(curl -s -o "$WORK/run_gated.json" -w '%{http_code}' -X POST "$API/api/workflows/gated/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"runId":"gate-gated-1"}')
echo "[GATE] run gated http=$RUN_G body=$(cat "$WORK/run_gated.json")"
[ "$RUN_G" = "200" ] || fail "run gated expected 200, got $RUN_G"
# poll until it shows RUNNING (parked at the actuate approval gate); substring-match (queryStatus returns
# the proto enum name WORKFLOW_EXECUTION_STATUS_RUNNING).
PARKED=0
for i in $(seq 1 30); do
  curl -sf "$API/api/runs/gate-gated-1" -o "$WORK/status_gated.json" 2>/dev/null || true
  if grep -q "RUNNING" "$WORK/status_gated.json" 2>/dev/null; then PARKED=1; break; fi
  sleep 2
done
echo "[GATE] gated status=$(cat "$WORK/status_gated.json" 2>/dev/null)"
[ "$PARKED" = "1" ] || fail "gated run did not reach RUNNING (parked at approval gate) within ~60s"
echo "[GATE] gated parked RUNNING at the actuate gate; approving..."
APP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/runs/gate-gated-1/approve")
echo "[GATE] approve gated http=$APP_CODE"
[ "$APP_CODE" = "200" ] || fail "approve gated expected 200, got $APP_CODE"
curl -sf "$API/api/runs/gate-gated-1?wait=true" -o "$WORK/wait_gated.json" || fail "GET /api/runs/gate-gated-1?wait=true failed"
echo "[GATE] gated wait result=$(cat "$WORK/wait_gated.json")"
python - "$WORK/wait_gated.json" <<'PY' || fail "Assert 4: gated did not complete after approve"
import sys, json
r = json.load(open(sys.argv[1], encoding="utf-8"))
assert r["completed"] is True, f"gated completed expected true after approve, got {r}"
print(f"[GATE] gated run completed={r['completed']} after approve (human-gate honored)")
PY

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 5: boundary — :core/:registry/:compiler/:dispatch jars carry 0 org.springframework ====="
./gradlew -q --no-daemon :core:jar :registry:jar :compiler:jar :dispatch:jar >/dev/null
BOUNDARY_OK=1
for m in core registry compiler dispatch; do
  JARP=$(ls "$m"/build/libs/*.jar 2>/dev/null | head -1 || true)
  [ -n "$JARP" ] || fail "no built jar found for :$m"
  if unzip -l "$JARP" | grep -qi "org/springframework"; then
    echo "[GATE] BOUNDARY VIOLATION: :$m jar ($JARP) references org.springframework"
    BOUNDARY_OK=0
  else
    echo "[GATE] :$m jar clean (no org.springframework)"
  fi
done
[ "$BOUNDARY_OK" = "1" ] || fail "Spring leaked outside :authoring-api"

echo "[GATE] PASS (operator compose -> validate -> save(=deploy) -> poll-bind on a LIVE worker (no restart) -> run-on-Temporal; human-gate honored; Spring confined to :authoring-api)"
exit 0
