#!/usr/bin/env bash
# §5 v0.4c GATE (objective proof): the saga's reverse-topo compensation is exposed as an ordered, per-step
# RESULT timeline on REAL Temporal, and compensation is BEST-EFFORT (a failed step is recorded FAILED and the
# unwind continues).
#   1 ordered timeline : a failing run (sink transform.map fails) auto-compensates [notify.email, db.upsert];
#                        GET /api/runs/{id}/compensation returns them in reverse-topo order, all COMPENSATED.
#   2 best-effort      : with a compensate-fault armed on notify.email, its event is FAILED (node COMP_FAILED)
#                        AND db.upsert still COMPENSATED (unwind continued past the failed step).
#
# Temporal-only. Needs Postgres 15432 + Temporal 7233. Bring-up cloned from run-intervention-gate.sh (env incl.
# HERMOD_FAULT_INJECT=1, psql_q, kill/cleanup, step-0 schema/reset, worker+API, save+poll-bind). Boundary +
# full prior-gate regression are controller-verified (Chunk 6), not in-script.
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-compensation-timeline-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1   # arm the worker's test-only fault_inject toggle (compensate-fault for assert 2)

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/comp-timeline-gate"
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
echo "[GATE] step 0: ensure schemas (registry + fault_inject phase) + reset state + seed source_rows"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index" >/dev/null
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
echo "[GATE] step 3: save comp-timeline@1.0.0 + wait for the LIVE worker to poll-bind it (no restart)"
SAVE_CODE=$(curl -s -o "$WORK/save_ct.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/comp-timeline.json")
echo "[GATE] save comp-timeline@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save_ct.json")"
[ "$SAVE_CODE" = "200" ] || fail "save comp-timeline@1.0.0 expected 200, got $SAVE_CODE"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for the worker to poll-bind comp-timeline@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow comp-timeline@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind comp-timeline@1.0.0"
echo "[GATE] worker poll-bound comp-timeline@1.0.0 WITHOUT restart"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 1: ordered compensation timeline (all COMPENSATED, reverse-topo) ====="
psql_q "TRUNCATE fault_inject" >/dev/null
RUN_TL="comp-tl-1"
curl -fsS -X POST "$API/api/workflows/comp-timeline/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_TL\",\"failAtBlockId\":\"transform.map\"}" >/dev/null || fail "start comp-timeline run failed"
curl -fsS "$API/api/runs/$RUN_TL?wait=true" | grep -q '"completed":false' || fail "comp-timeline run should have compensated"
TL=$(curl -fsS "$API/api/runs/$RUN_TL/compensation")
echo "[GATE] timeline => $TL"
# reverse-topo: index 0 = notify.email, index 1 = db.upsert; both COMPENSATED (per-field greps, order-tolerant)
echo "$TL" | grep -qE '"index":0[^}]*"blockId":"notify.email"' || fail "event 0 is not notify.email"
echo "$TL" | grep -qE '"blockId":"notify.email"[^}]*"outcome":"COMPENSATED"' || fail "notify.email not COMPENSATED in timeline"
echo "$TL" | grep -qE '"blockId":"db.upsert"[^}]*"outcome":"COMPENSATED"' || fail "db.upsert not COMPENSATED in timeline"
[ "$(psql_q "SELECT count(*) FROM target_rows")" = "0" ] || fail "db.upsert compensation did not clean target_rows"
echo "[GATE] Assert 1 OK: ordered timeline matches reverse-topo compensation"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 2: best-effort — a failed compensation is recorded FAILED and the unwind continues ====="
psql_q "INSERT INTO fault_inject(block_id, phase) VALUES ('notify.email','compensate') ON CONFLICT DO NOTHING" >/dev/null
psql_q "TRUNCATE target_rows" >/dev/null
RUN_CF="comp-tl-fail-1"
curl -fsS -X POST "$API/api/workflows/comp-timeline/1.0.0/run" -H 'Content-Type: application/json' \
  -d "{\"runId\":\"$RUN_CF\",\"failAtBlockId\":\"transform.map\"}" >/dev/null || fail "start comp-fault run failed"
curl -fsS "$API/api/runs/$RUN_CF?wait=true" | grep -q '"completed":false' || fail "comp-fault run should have compensated"
TLF=$(curl -fsS "$API/api/runs/$RUN_CF/compensation")
echo "[GATE] timeline (comp-fault) => $TLF"
echo "$TLF" | grep -qE '"blockId":"notify.email"[^}]*"outcome":"FAILED"' || fail "notify.email compensation not recorded FAILED"
echo "$TLF" | grep -qE '"blockId":"db.upsert"[^}]*"outcome":"COMPENSATED"' || fail "unwind did not continue to db.upsert after the failed step"
curl -fsS "$API/api/runs/$RUN_CF/nodes" | grep -q '"n":"COMP_FAILED"' || fail "node n not COMP_FAILED"
[ "$(psql_q "SELECT count(*) FROM target_rows")" = "0" ] || fail "db.upsert should still have compensated (best-effort continue)"
psql_q "DELETE FROM fault_inject WHERE block_id='notify.email' AND phase='compensate'" >/dev/null
echo "[GATE] Assert 2 OK: failed compensation recorded + unwind continued (best-effort)"

# ---------------------------------------------------------------------------
echo "[GATE] PASS run-compensation-timeline-gate.sh"
exit 0
