#!/usr/bin/env bash
# §6 v0.6a GATE (objective proof): a CONDUCTOR run is triggered via the SAME :authoring-api REST control plane
# and OBSERVED in the SAME Console as Temporal runs, with per-run engine selection recorded in run_index.
#
# This is the v0.6a headline (Node-RED-impossible / single-engine-impossible): an operator POSTs a run with
# {"engine":"conductor"} to the same control plane, the ConductorEnginePort deploys-on-run to the Conductor
# server + starts it (Conductor generates the workflowId), the run is recorded engine='conductor', and the
# Console (GET /api/runs) lists it alongside a Temporal run — each engine-tagged. Approve works on Conductor.
# Node-state is now REAL forward lighting (v0.6b); only compensation-timeline remains a stub (200 [], not 500)
# — deferred to v0.6c.
#
# DUAL-ENGINE STACK (assert 5 mixed-console needs BOTH engines live):
#   Postgres 15432 + Conductor 18088 + Temporal 7233; the Conductor workers (ConductorWorkerMain) AND the
#   Temporal worker (:app); plus :authoring-api with HERMOD_CONDUCTOR_URL=http://localhost:18088/api exported
#   (the EngineConfig default is :8088 but the gate server is :18088 — LOAD-BEARING).
#
# Six hard asserts (ALL must hold for PASS):
#   1 conductor-run-via-REST : save cforward@1.0.0; POST .../run {"engine":"conductor"} -> 200 + runId; the run
#                              EXISTS on the Conductor server with status RUNNING|COMPLETED.
#   2 engine-tagged run_index: exactly one run_index row for that runId with engine='conductor' (psql).
#   3 console-lists-it       : GET /api/runs includes the run with "engine":"conductor" + a non-UNKNOWN status.
#   4 conductor-human-gate   : a gated run on Conductor -> GET /api/runs/{id} RUNNING -> POST .../approve ->
#                              GET .../?wait=true completed:true.
#   5 mixed-console          : also start a Temporal run (default engine); GET /api/runs lists BOTH, each tagged.
#   6 nodes-lit + comp-stub  : GET /api/runs/{conductorRunId}/nodes -> real forward-lit map (states in
#                              PENDING/RUNNING/DONE) and /compensation -> [] (still a v0.6c stub) (200, not 500).
#
# Run from repo root with the stack up + schema applied:
#   docker compose up -d postgres conductor temporal
#   bash scripts/init-db.sh
#   bash scripts/run-conductor-console-gate.sh   # expect: [GATE] PASS run-conductor-console-gate.sh  exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: pin the plugin store dir + DB + poll interval so the forked worker JVM agrees with this shell.
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
# LOAD-BEARING: the EngineConfig default is :8088, but the compose Conductor host port is :18088. Both the
# :authoring-api ConductorEnginePort (HERMOD_CONDUCTOR_URL) and the conductor workers (CONDUCTOR_SERVER_URL)
# must point at the gate server.
export HERMOD_CONDUCTOR_URL="${HERMOD_CONDUCTOR_URL:-http://localhost:18088/api}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
CSERVER="http://localhost:18088"
WORK="build/conductor-console-gate"
mkdir -p "$WORK"
CWORKER_LOG="$WORK/conductor-worker.log"
TWORKER_LOG="$WORK/temporal-worker.log"
API_LOG="$WORK/api.log"

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

kill_temporal_worker_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.app.WorkerKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_conductor_worker_jvms() {
  { jps -l 2>/dev/null | grep "ConductorWorkerMainKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
kill_api_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
cleanup() { kill_api_jvms || true; kill_conductor_worker_jvms || true; kill_temporal_worker_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- conductor worker log tail ---"; tail -40 "$CWORKER_LOG" 2>/dev/null || true
  echo "--- temporal worker log tail ---"; tail -40 "$TWORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---"; tail -40 "$API_LOG" 2>/dev/null || true
  exit 1
}

echo "[GATE] db = $HERMOD_DB_URL ; conductor = $HERMOD_CONDUCTOR_URL ; poll = ${HERMOD_WF_POLL_MS}ms"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: registry schema (incl. run_index.engine) + reset state + seed source_rows"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index" >/dev/null
# comp_ledger only exists once the registry/app schema has it; ignore if absent.
psql_q "TRUNCATE comp_ledger" >/dev/null 2>&1 || true
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"
# sanity: the engine column must exist (Chunk 1).
ENGINE_COL=$(psql_q "SELECT count(*) FROM information_schema.columns WHERE table_name='run_index' AND column_name='engine'")
[ "$ENGINE_COL" = "1" ] || fail "run_index.engine column missing (registry-schema not applied?)"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: wait for Conductor + Temporal health, then build worker + API"
HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
until curl -sf "$CSERVER/health" 2>/dev/null | grep -q '"healthy":true'; do
  [ "$(date +%s)" -lt "$HEALTH_DEADLINE" ] || fail "Conductor not healthy within 3m (is 'docker compose up -d conductor' running?)"
  sleep 3
done
echo "[GATE] Conductor healthy"

./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test :conductor-runtime:build -x test >/dev/null

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the Conductor workers (poll the per-blockId task types) + Temporal worker"
: > "$CWORKER_LOG"
HERMOD_WORKER_NAME=cgate-cw ./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
CWUP=0
for i in $(seq 1 60); do
  if grep -q "conductor workers polling" "$CWORKER_LOG" 2>/dev/null; then CWUP=1; break; fi
  sleep 2
done
[ "$CWUP" = "1" ] || fail "conductor workers did not reach 'conductor workers polling' within ~120s"
echo "[GATE] conductor workers polling"

: > "$TWORKER_LOG"
HERMOD_WORKER_NAME=cgate-tw ./gradlew -q --no-daemon :app:run >"$TWORKER_LOG" 2>&1 &
TWUP=0
for i in $(seq 1 60); do
  if grep -q "starting; polling" "$TWORKER_LOG" 2>/dev/null; then TWUP=1; break; fi
  sleep 2
done
[ "$TWUP" = "1" ] || fail "temporal worker did not reach 'starting; polling' within ~120s"
echo "[GATE] temporal worker up"

# ---------------------------------------------------------------------------
echo "[GATE] step 3: start :authoring-api (EngineRouter; HERMOD_CONDUCTOR_URL=$HERMOD_CONDUCTOR_URL)"
: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0
for i in $(seq 1 60); do
  if curl -sf "$API/api/workflows" >/dev/null 2>&1; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || fail "API did not answer GET /api/workflows within ~120s"
echo "[GATE] API up on 18090"

SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))

# ===========================================================================
echo "[GATE] ===== Assert 1: Conductor run via REST -> 200 + runId; run EXISTS on Conductor server ====="
SAVE_F=$(curl -s -o "$WORK/save_cforward.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/conductor-forward.json")
echo "[GATE] save cforward@1.0.0 http=$SAVE_F body=$(cat "$WORK/save_cforward.json")"
[ "$SAVE_F" = "200" ] || fail "save cforward@1.0.0 expected 200, got $SAVE_F"

RUN_F=$(curl -s -o "$WORK/run_cforward.json" -w '%{http_code}' -X POST "$API/api/workflows/cforward/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"engine":"conductor"}')
echo "[GATE] run cforward {engine:conductor} http=$RUN_F body=$(cat "$WORK/run_cforward.json")"
[ "$RUN_F" = "200" ] || fail "run cforward (conductor) expected 200, got $RUN_F; body=$(cat "$WORK/run_cforward.json")"
CFWD_ID=$(python - "$WORK/run_cforward.json" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$CFWD_ID" ] || fail "run cforward returned no runId"
echo "[GATE] conductor forward runId (Conductor-generated) = $CFWD_ID"

# Confirm the run EXISTS on the Conductor server itself (status RUNNING or COMPLETED), polling for the index.
CFWD_STATUS=""
for i in $(seq 1 30); do
  CFWD_STATUS=$(curl -sf "$CSERVER/api/workflow/$CFWD_ID?includeTasks=false" 2>/dev/null \
    | python -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || true)
  case "$CFWD_STATUS" in RUNNING|COMPLETED) break;; esac
  sleep 2
done
echo "[GATE] conductor server reports status=$CFWD_STATUS for $CFWD_ID"
case "$CFWD_STATUS" in
  RUNNING|COMPLETED) ;;
  *) fail "conductor run $CFWD_ID not RUNNING|COMPLETED on the Conductor server (got '$CFWD_STATUS')";;
esac
echo "[GATE] assert 1 PASS: Conductor run triggered via REST + exists on Conductor (status=$CFWD_STATUS)"

# ===========================================================================
echo "[GATE] ===== Assert 2: run_index has exactly one row for that runId with engine='conductor' ====="
ROW_CNT=$(psql_q "SELECT count(*) FROM run_index WHERE run_id='$CFWD_ID'")
ENGINE_VAL=$(psql_q "SELECT engine FROM run_index WHERE run_id='$CFWD_ID'")
echo "[GATE] run_index rows for $CFWD_ID = $ROW_CNT ; engine = '$ENGINE_VAL'"
[ "$ROW_CNT" = "1" ] || fail "expected exactly 1 run_index row for $CFWD_ID, got $ROW_CNT"
[ "$ENGINE_VAL" = "conductor" ] || fail "expected run_index.engine='conductor' for $CFWD_ID, got '$ENGINE_VAL'"
echo "[GATE] assert 2 PASS: run_index tags the run engine='conductor'"

# ===========================================================================
echo "[GATE] ===== Assert 3: GET /api/runs includes the run, engine:conductor + non-UNKNOWN status ====="
curl -sf "$API/api/runs" -o "$WORK/runs_after_cforward.json" || fail "GET /api/runs failed"
echo "[GATE] /api/runs = $(cat "$WORK/runs_after_cforward.json")"
python - "$WORK/runs_after_cforward.json" "$CFWD_ID" <<'PY' || fail "Assert 3: console did not list the conductor run engine-tagged + non-UNKNOWN"
import sys, json
runs = json.load(open(sys.argv[1], encoding="utf-8")); rid = sys.argv[2]
m = [r for r in runs if r["runId"] == rid]
assert len(m) == 1, f"expected exactly 1 console row for {rid}, got {len(m)}: {runs}"
r = m[0]
assert r["engine"] == "conductor", f"console row engine expected 'conductor', got {r['engine']}: {r}"
assert r["status"] != "UNKNOWN", f"console row status should be non-UNKNOWN (live conductor query), got {r['status']}: {r}"
print(f"[GATE] console lists conductor run: engine={r['engine']} status={r['status']}")
PY
echo "[GATE] assert 3 PASS: console lists the conductor run, engine-tagged + live status"

# ===========================================================================
echo "[GATE] ===== Assert 4: Conductor human-gate -> RUNNING -> approve -> wait completed:true ====="
SAVE_G=$(curl -s -o "$WORK/save_gated.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/gated.json")
echo "[GATE] save gated@1.0.0 http=$SAVE_G body=$(cat "$WORK/save_gated.json")"
[ "$SAVE_G" = "200" ] || fail "save gated@1.0.0 expected 200, got $SAVE_G"

RUN_G=$(curl -s -o "$WORK/run_gated.json" -w '%{http_code}' -X POST "$API/api/workflows/gated/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"engine":"conductor"}')
echo "[GATE] run gated {engine:conductor} http=$RUN_G body=$(cat "$WORK/run_gated.json")"
[ "$RUN_G" = "200" ] || fail "run gated (conductor) expected 200, got $RUN_G; body=$(cat "$WORK/run_gated.json")"
GATED_ID=$(python - "$WORK/run_gated.json" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$GATED_ID" ] || fail "run gated returned no runId"
echo "[GATE] conductor gated runId = $GATED_ID"

# poll GET /api/runs/{id} until RUNNING (parked at the actuate WAIT gate)
PARKED=0
for i in $(seq 1 30); do
  curl -sf "$API/api/runs/$GATED_ID" -o "$WORK/status_gated.json" 2>/dev/null || true
  if grep -q "RUNNING" "$WORK/status_gated.json" 2>/dev/null; then PARKED=1; break; fi
  sleep 2
done
echo "[GATE] gated status via API = $(cat "$WORK/status_gated.json" 2>/dev/null)"
[ "$PARKED" = "1" ] || fail "gated conductor run did not reach RUNNING (parked at gate) within ~60s"

# approve — poll: the WAIT task may lag a few seconds before it's IN_PROGRESS (approve no-ops until then).
# Then confirm completion via GET .../?wait=true. We retry approve until the wait confirms completed:true.
APPROVED=0
for i in $(seq 1 30); do
  APP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/runs/$GATED_ID/approve")
  [ "$APP_CODE" = "200" ] || true
  curl -sf "$API/api/runs/$GATED_ID?wait=true" -o "$WORK/wait_gated.json" 2>/dev/null || true
  if python - "$WORK/wait_gated.json" 2>/dev/null <<'PY'
import sys, json
r = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if r.get("completed") is True else 1)
PY
  then APPROVED=1; break; fi
  sleep 3
done
echo "[GATE] gated wait result = $(cat "$WORK/wait_gated.json" 2>/dev/null)"
[ "$APPROVED" = "1" ] || fail "gated conductor run did not COMPLETE after approve within ~90s"
echo "[GATE] assert 4 PASS: Conductor human-gate RUNNING -> approve -> completed:true"

# ===========================================================================
echo "[GATE] ===== Assert 5: mixed console — also start a Temporal run; GET /api/runs lists BOTH, each tagged ====="
# Save + poll-bind a Temporal workflow on the :app worker (no restart), then run it with the DEFAULT engine.
SAVE_D=$(curl -s -o "$WORK/save_diamond.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/diamond.json")
echo "[GATE] save diamond@1.0.0 http=$SAVE_D body=$(cat "$WORK/save_diamond.json")"
[ "$SAVE_D" = "200" ] || fail "save diamond@1.0.0 expected 200, got $SAVE_D"
echo "[GATE] sleeping ${SLEEP_S}s for the temporal worker to poll-bind diamond@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow diamond@1.0.0" "$TWORKER_LOG" || fail "temporal worker did not poll-bind diamond@1.0.0"

RUN_T=$(curl -s -o "$WORK/run_temporal.json" -w '%{http_code}' -X POST "$API/api/workflows/diamond/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"runId":"cgate-temporal-1","engine":"temporal"}')
echo "[GATE] run diamond {engine:temporal} http=$RUN_T body=$(cat "$WORK/run_temporal.json")"
[ "$RUN_T" = "200" ] || fail "run diamond (temporal) expected 200, got $RUN_T"
TEMP_ID=$(python - "$WORK/run_temporal.json" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
echo "[GATE] temporal runId = $TEMP_ID"

# wait for the temporal run to complete so its console status is non-UNKNOWN/terminal-friendly
curl -sf "$API/api/runs/$TEMP_ID?wait=true" -o "$WORK/wait_temporal.json" 2>/dev/null || true
echo "[GATE] temporal wait result = $(cat "$WORK/wait_temporal.json" 2>/dev/null)"

curl -sf "$API/api/runs" -o "$WORK/runs_mixed.json" || fail "GET /api/runs (mixed) failed"
echo "[GATE] /api/runs (mixed) = $(cat "$WORK/runs_mixed.json")"
python - "$WORK/runs_mixed.json" "$CFWD_ID" "$TEMP_ID" <<'PY' || fail "Assert 5: mixed console did not list BOTH engine-tagged runs"
import sys, json
runs = json.load(open(sys.argv[1], encoding="utf-8"))
cid, tid = sys.argv[2], sys.argv[3]
by = {r["runId"]: r for r in runs}
assert cid in by, f"conductor run {cid} missing from mixed console: {list(by)}"
assert tid in by, f"temporal run {tid} missing from mixed console: {list(by)}"
assert by[cid]["engine"] == "conductor", f"conductor run mis-tagged: {by[cid]}"
assert by[tid]["engine"] == "temporal", f"temporal run mis-tagged: {by[tid]}"
print(f"[GATE] mixed console: conductor={cid}(engine={by[cid]['engine']},status={by[cid]['status']}) "
      f"temporal={tid}(engine={by[tid]['engine']},status={by[tid]['status']})")
PY
echo "[GATE] assert 5 PASS: mixed console lists BOTH engines, each engine-tagged"

# ===========================================================================
echo "[GATE] ===== Assert 6: nodes-lit + comp-stub — /nodes -> forward-lit map , /compensation -> [] (200, not 500) ====="
NODES_CODE=$(curl -s -o "$WORK/nodes_cforward.json" -w '%{http_code}' "$API/api/runs/$CFWD_ID/nodes")
COMP_CODE=$(curl -s -o "$WORK/comp_cforward.json" -w '%{http_code}' "$API/api/runs/$CFWD_ID/compensation")
echo "[GATE] /nodes http=$NODES_CODE body=$(cat "$WORK/nodes_cforward.json")"
echo "[GATE] /compensation http=$COMP_CODE body=$(cat "$WORK/comp_cforward.json")"
[ "$NODES_CODE" = "200" ] || fail "nodes stub expected 200 (not 500), got $NODES_CODE"
[ "$COMP_CODE" = "200" ] || fail "compensation stub expected 200 (not 500), got $COMP_CODE"
python - "$WORK/nodes_cforward.json" "$WORK/comp_cforward.json" <<'PY' || fail "Assert 6: nodes not forward-lit / compensation not []"
import sys, json
nodes = json.load(open(sys.argv[1], encoding="utf-8"))
comp = json.load(open(sys.argv[2], encoding="utf-8"))
# v0.6b: /nodes is now REAL forward lighting. cforward (src->m->up, all SIMPLE) has no failure/compensation,
# so every state must be a forward state; accept the in-flight set (no strict all-DONE) to avoid timing flakiness.
assert nodes != {}, f"nodes expected real forward-lit map, got empty {nodes}"
allowed = {"PENDING", "RUNNING", "AWAITING_APPROVAL", "DONE"}
bad = {k: v for k, v in nodes.items() if v not in allowed}
assert not bad, f"nodes has non-forward states {bad} (expected subset of {allowed}): {nodes}"
# /compensation is still a v0.6c stub.
assert comp == [], f"compensation stub expected [], got {comp}"
print(f"[GATE] nodes forward-lit={nodes} compensation=[] (200)")
PY
echo "[GATE] assert 6 PASS: nodes forward-lit (states subset of PENDING/RUNNING/DONE), compensation=[] stub, 200 not 500"

# ---------------------------------------------------------------------------
echo ""
echo "[GATE] blockers: conductor_run_via_rest=1 engine_tagged_run_index=1 console_lists_it=1 conductor_human_gate=1 mixed_console=1 nodes_lit_comp_stub=1"
echo "[GATE] PASS run-conductor-console-gate.sh"
exit 0
