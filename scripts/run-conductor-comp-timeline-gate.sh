#!/usr/bin/env bash
# §6 v0.6c GATE (objective proof): the Conductor saga's reverse-topo compensation is exposed as an ordered,
# per-step RESULT timeline, and compensation is BEST-EFFORT (a failed compensate step is recorded FAILED and
# the unwind continues).
#
# v0.6c made GET /api/runs/{runId}/compensation return an ordered JSON list for a FAILED Conductor run:
#   [{index, nodeId, blockId, version, outcome, atMillis}, ...]   (outcome in COMPENSATED/FAILED)
#
# Two hard asserts (BOTH must hold for PASS):
#   T1 ordered COMPENSATED timeline : run {engine:conductor, failAtBlockId:transform.map}; the failureWorkflow
#                                     compensates reverse-topo [interlockAck, recordPlan]; /compensation returns
#                                     a length-2 list, idx0=interlockAck(notify.email)=COMPENSATED,
#                                     idx1=recordPlan(db.upsert)=COMPENSATED, both atMillis>0.
#   T2 COMP_FAILED best-effort      : with a compensate-fault armed on notify.email, the interlockAck entry is
#                                     FAILED AND recordPlan is still COMPENSATED (a failed compensation did NOT
#                                     abort the unwind chain).
#
# CONDUCTOR-ONLY STACK (no Temporal worker needed):
#   Postgres 15432 + Conductor 18088; the Conductor workers (ConductorWorkerMain) + :authoring-api with
#   HERMOD_CONDUCTOR_URL=http://localhost:18088/api exported (EngineConfig default is :8088 but the gate
#   server is :18088 — LOAD-BEARING). HERMOD_FAULT_INJECT=1 is exported BEFORE the conductor-runtime worker JVM
#   starts so the compensate-fault hook is armed inside that JVM (LOAD-BEARING: the node-states gate does NOT
#   set it; the fault-inject toggle is read by the conductor worker, not the API).
#
# Fixture: scripts/fixtures/compose/ot-recipe-apply.json — a linear OT recipe:
#   sensorRead(db.read) -> recordPlan(db.upsert, compensable) -> interlockAck(notify.email, compensable)
#   -> preflight(transform.map, the fault point) -> applyPLC(actuate, IRREVERSIBLE).
#   Compensation runs reverse-topo over the compensable steps already done: [interlockAck, recordPlan].
#
# Run from repo root with the stack up + schema applied:
#   docker compose up -d postgres conductor
#   bash scripts/init-db.sh
#   bash scripts/run-conductor-comp-timeline-gate.sh   # expect: [GATE] PASS run-conductor-comp-timeline-gate.sh  exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: pin the plugin store dir + DB + poll interval so the forked worker JVM agrees with this shell.
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
# LOAD-BEARING: arm the worker's test-only fault_inject toggle. The node-states gate does NOT set this; the
# compensate-fault hook (assert T2) is read inside the CONDUCTOR worker JVM, so it must be exported BEFORE
# :conductor-runtime:run starts below.
export HERMOD_FAULT_INJECT=1
# LOAD-BEARING: the EngineConfig default is :8088, but the compose Conductor host port is :18088. Both the
# :authoring-api ConductorEnginePort (HERMOD_CONDUCTOR_URL) and the conductor workers (CONDUCTOR_SERVER_URL)
# must point at the gate server.
export HERMOD_CONDUCTOR_URL="${HERMOD_CONDUCTOR_URL:-http://localhost:18088/api}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
CSERVER="http://localhost:18088"
WORK="build/conductor-comp-timeline-gate"
mkdir -p "$WORK"
CWORKER_LOG="$WORK/conductor-worker.log"
API_LOG="$WORK/api.log"

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

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
cleanup() { kill_api_jvms || true; kill_conductor_worker_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- conductor worker log tail ---"; tail -40 "$CWORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---"; tail -40 "$API_LOG" 2>/dev/null || true
  exit 1
}

echo "[GATE] db = $HERMOD_DB_URL ; conductor = $HERMOD_CONDUCTOR_URL ; poll = ${HERMOD_WF_POLL_MS}ms ; fault-inject = on"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: apply registry-schema THEN app schema (rebuilds comp_ledger w/ outcome/at_millis/idx + fault_inject), reset state, seed source_rows"
# Order matters: registry-schema first, then app/schema.sql which DROP+CREATEs comp_ledger WITH the new
# outcome/at_millis/idx columns AND fault_inject. Apply schema BEFORE truncate so the tables exist.
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U hermod -d hermod < app/src/main/resources/schema.sql >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, comp_ledger, fault_inject" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"
# sanity: the engine column must exist (registry-schema applied).
ENGINE_COL=$(psql_q "SELECT count(*) FROM information_schema.columns WHERE table_name='run_index' AND column_name='engine'")
[ "$ENGINE_COL" = "1" ] || fail "run_index.engine column missing (registry-schema not applied?)"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: wait for Conductor health, then build conductor-runtime + API"
HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
until curl -sf "$CSERVER/health" 2>/dev/null | grep -q '"healthy":true'; do
  [ "$(date +%s)" -lt "$HEALTH_DEADLINE" ] || fail "Conductor not healthy within 3m (is 'docker compose up -d conductor' running?)"
  sleep 3
done
echo "[GATE] Conductor healthy"

./gradlew -q --no-daemon :conductor-runtime:build -x test :authoring-api:build -x test >/dev/null

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the Conductor workers (fault-inject armed env; poll the per-blockId task types)"
: > "$CWORKER_LOG"
HERMOD_WORKER_NAME=ctgate-cw ./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
CWUP=0
for i in $(seq 1 60); do
  if grep -q "conductor workers polling" "$CWORKER_LOG" 2>/dev/null; then CWUP=1; break; fi
  sleep 2
done
[ "$CWUP" = "1" ] || fail "conductor workers did not reach 'conductor workers polling' within ~120s"
echo "[GATE] conductor workers polling"

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

# Save the linear OT recipe fixture.
SAVE_R=$(curl -s -o "$WORK/save_recipe.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-apply.json")
echo "[GATE] save ot-recipe-apply@1.0.0 http=$SAVE_R body=$(cat "$WORK/save_recipe.json")"
[ "$SAVE_R" = "200" ] || fail "save ot-recipe-apply@1.0.0 expected 200, got $SAVE_R"

# ===========================================================================
echo "[GATE] ===== Assert T1: ordered COMPENSATED timeline (reverse-topo [interlockAck, recordPlan]) ====="
RUN_T1=$(curl -s -o "$WORK/run_t1.json" -w '%{http_code}' -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"engine":"conductor","failAtBlockId":"transform.map"}')
echo "[GATE] run T1 {engine:conductor, failAtBlockId:transform.map} http=$RUN_T1 body=$(cat "$WORK/run_t1.json")"
[ "$RUN_T1" = "200" ] || fail "run T1 (conductor) expected 200, got $RUN_T1; body=$(cat "$WORK/run_t1.json")"
RUNID=$(python - "$WORK/run_t1.json" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$RUNID" ] || fail "run T1 returned no runId"
echo "[GATE] T1 conductor runId = $RUNID"

# Poll /compensation until the failureWorkflow's compensate tasks have run + been indexed (~90s deadline).
T1OK=0
for i in $(seq 1 45); do
  curl -sf "$API/api/runs/$RUNID/compensation" -o "$WORK/comp_t1.json" 2>/dev/null || true
  if python - "$WORK/comp_t1.json" 2>/dev/null <<'PY'
import sys, json
t = json.load(open(sys.argv[1], encoding="utf-8"))
ok = (isinstance(t, list) and len(t) == 2
      and t[0]["index"] == 0 and t[0]["nodeId"] == "interlockAck"
      and t[0]["blockId"] == "notify.email" and t[0]["outcome"] == "COMPENSATED" and t[0]["atMillis"] > 0
      and t[1]["index"] == 1 and t[1]["nodeId"] == "recordPlan"
      and t[1]["blockId"] == "db.upsert" and t[1]["outcome"] == "COMPENSATED" and t[1]["atMillis"] > 0)
sys.exit(0 if ok else 1)
PY
  then T1OK=1; break; fi
  sleep 2
done
echo "[GATE] T1 /compensation = $(cat "$WORK/comp_t1.json" 2>/dev/null)"
[ "$T1OK" = "1" ] || fail "T1 /compensation did not reach ordered [interlockAck=COMPENSATED, recordPlan=COMPENSATED] within ~90s; last=$(cat "$WORK/comp_t1.json" 2>/dev/null)"
echo "[GATE] assert T1 PASS"

# ===========================================================================
echo "[GATE] ===== Assert T2: COMP_FAILED best-effort — a failed compensate step is FAILED and the unwind continues ====="
psql_q "INSERT INTO fault_inject(block_id,phase) VALUES ('notify.email','compensate') ON CONFLICT DO NOTHING" >/dev/null
RUN_T2=$(curl -s -o "$WORK/run_t2.json" -w '%{http_code}' -X POST "$API/api/workflows/ot-recipe-apply/1.0.0/run" -H 'Content-Type: application/json' --data-binary '{"engine":"conductor","failAtBlockId":"transform.map"}')
echo "[GATE] run T2 {engine:conductor, failAtBlockId:transform.map} http=$RUN_T2 body=$(cat "$WORK/run_t2.json")"
[ "$RUN_T2" = "200" ] || fail "run T2 (conductor) expected 200, got $RUN_T2; body=$(cat "$WORK/run_t2.json")"
RUNID2=$(python - "$WORK/run_t2.json" <<'PY'
import sys, json
print(json.load(open(sys.argv[1], encoding="utf-8"))["runId"])
PY
)
[ -n "$RUNID2" ] || fail "run T2 returned no runId"
echo "[GATE] T2 conductor runId = $RUNID2"

# Poll /compensation: interlockAck must be FAILED (its compensate faulted) AND recordPlan still COMPENSATED
# (the chain continued past the failed step). Find entries by nodeId, do not assume index.
T2OK=0
for i in $(seq 1 45); do
  curl -sf "$API/api/runs/$RUNID2/compensation" -o "$WORK/comp_t2.json" 2>/dev/null || true
  if python - "$WORK/comp_t2.json" 2>/dev/null <<'PY'
import sys, json
t = json.load(open(sys.argv[1], encoding="utf-8"))
if not isinstance(t, list):
    sys.exit(1)
by_node = {e.get("nodeId"): e for e in t}
ia = by_node.get("interlockAck")
rp = by_node.get("recordPlan")
ok = (ia is not None and ia.get("outcome") == "FAILED"
      and rp is not None and rp.get("outcome") == "COMPENSATED")
sys.exit(0 if ok else 1)
PY
  then T2OK=1; break; fi
  sleep 2
done
echo "[GATE] T2 /compensation = $(cat "$WORK/comp_t2.json" 2>/dev/null)"
psql_q "DELETE FROM fault_inject WHERE block_id='notify.email' AND phase='compensate'" >/dev/null
[ "$T2OK" = "1" ] || fail "T2 /compensation did not reach interlockAck=FAILED + recordPlan=COMPENSATED (best-effort continue) within ~90s; last=$(cat "$WORK/comp_t2.json" 2>/dev/null)"
echo "[GATE] assert T2 PASS"

# ---------------------------------------------------------------------------
echo ""
echo "[GATE] blockers: ordered_compensated_timeline=1 comp_failed_best_effort=1"
echo "[GATE] PASS run-conductor-comp-timeline-gate.sh"
exit 0
