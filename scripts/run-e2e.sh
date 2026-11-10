#!/usr/bin/env bash
# v0.5b E2E demo GIF wrapper: boots the full stack (Docker + worker + authoring-api +
# ot-recipe-apply deployed) then hands off to Playwright (authoring-ui/e2e/) for browser-level
# E2E specs against the Vite dev server (:5173 → /api proxied to :18090). Records video per
# test; gen-demo-gifs.sh converts the webms to docs/demo/*.gif.
#
# Run from repo root with the Docker stack up:
#   docker compose up -d postgres temporal conductor
#   bash scripts/run-e2e.sh   # expect: [E2E] PASS run-e2e.sh exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1   # arm the worker's test-only fault_inject toggle
# Conductor (engine-neutral spec): load-bearing — EngineConfig default is :8088, compose host port is :18088.
export HERMOD_CONDUCTOR_URL="${HERMOD_CONDUCTOR_URL:-http://localhost:18088/api}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/e2e"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

kill_worker_jvms() {
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
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_conductor_worker_jvms || true; }
trap cleanup EXIT

fail() { echo "[E2E] FAIL: $*"; echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true; echo "--- api log tail ---"; tail -40 "$API_LOG" 2>/dev/null || true; exit 1; }

echo "[E2E] db = $HERMOD_DB_URL ; poll = ${HERMOD_WF_POLL_MS}ms ; fault-inject = on"

# ---------------------------------------------------------------------------
echo "[E2E] step 0: ensure schemas (registry + fault_inject phase) + reset state + seed source_rows"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES ('a','x'),('b','y') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[E2E] seeded source_rows count=$(psql_q "SELECT count(*) FROM source_rows")"

# ---------------------------------------------------------------------------
echo "[E2E] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :conductor-runtime:build -x test :authoring-api:build -x test >/dev/null

echo "[E2E] step 2: start the worker (background, fault-inject armed env) BEFORE composing; then start API"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=gate-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
WUP=0
for i in $(seq 1 60); do
  if grep -q "starting; polling" "$WORKER_LOG" 2>/dev/null; then WUP=1; break; fi
  sleep 2
done
[ "$WUP" = "1" ] || fail "worker did not reach 'starting; polling' within ~120s"
echo "[E2E] worker up"

echo "[E2E] step 2b: start the Conductor workers (engine-neutral spec runs on Conductor)"
CWORKER_LOG="$WORK/conductor-worker.log"; : > "$CWORKER_LOG"
HERMOD_WORKER_NAME=e2e-cw ./gradlew -q --no-daemon :conductor-runtime:run >"$CWORKER_LOG" 2>&1 &
CWUP=0
for i in $(seq 1 60); do
  if grep -q "conductor workers polling" "$CWORKER_LOG" 2>/dev/null; then CWUP=1; break; fi
  sleep 2
done
[ "$CWUP" = "1" ] || fail "conductor workers did not reach 'conductor workers polling' within ~120s"
echo "[E2E] conductor workers polling"

: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0
for i in $(seq 1 60); do
  if curl -sf "$API/api/workflows" >/dev/null 2>&1; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || fail "API did not answer GET /api/workflows within ~120s"
echo "[E2E] API up on 18090"

# ---------------------------------------------------------------------------
echo "[E2E] step 3: save ot-recipe-apply@1.0.0 + wait for the LIVE worker to poll-bind it (no restart)"
SAVE_CODE=$(curl -s -o "$WORK/save_ora.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-apply.json")
echo "[E2E] save ot-recipe-apply@1.0.0 http=$SAVE_CODE body=$(cat "$WORK/save_ora.json")"
[ "$SAVE_CODE" = "200" ] || fail "save ot-recipe-apply@1.0.0 expected 200, got $SAVE_CODE"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[E2E] sleeping ${SLEEP_S}s for the worker to poll-bind ot-recipe-apply@1.0.0..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-apply@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind ot-recipe-apply@1.0.0"
echo "[E2E] worker poll-bound ot-recipe-apply@1.0.0 WITHOUT restart"

# ---------------------------------------------------------------------------
echo "[E2E] stack up + ot-recipe-apply deployed; running Playwright..."
( cd authoring-ui && npm run test:e2e ); E2E_RC=$?
[ "$E2E_RC" = "0" ] || fail "playwright e2e failed (rc=$E2E_RC)"
echo "[E2E] specs green; generating GIFs..."
if [ -f scripts/gen-demo-gifs.sh ]; then bash scripts/gen-demo-gifs.sh; else echo "[gifs] (stub — gen-demo-gifs.sh added in Chunk 5)"; fi
echo "[E2E] PASS run-e2e.sh"
