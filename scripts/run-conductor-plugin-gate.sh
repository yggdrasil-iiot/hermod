#!/usr/bin/env bash
# GATE (objective): a published PLUGIN block runs on CONDUCTOR, and a block published while the Conductor worker
# is already running is picked up without a restart (the worker re-reads the registry every HERMOD_WF_POLL_MS).
#
# Order matters: the Conductor worker starts BEFORE the plugin is published, so the plugin's task type can only
# be polled through the registry refresh.
#
# Hard asserts (must ALL hold for PASS):
#   (a) BOOT     : the worker's boot line does NOT list io.example.greet (the block is not published yet).
#   (b) REFRESH  : after publish, the worker logs `now polling newly published block types=[io.example.greet]`.
#   (c) EXECUTION: the worker log shows `[plugin] forward io.example.greet` (only the plugin prints this).
#   (d) ISOLATION: the worker log shows `[PluginLoader] loaded io.example.GreetBlock via ...URLClassLoader...`.
#   (e) DONE     : demo-with-greet (db.read -> ... -> greet -> actuate WAIT) reaches COMPLETED after approve.
#
# Run from repo root with the stack up + schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-conductor-plugin-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

# The CLI publish and the Conductor worker must agree on the DB; the jar path is stored absolute, so the
# worker loads it wherever its working directory is. Paths reach the forked JVMs in Windows form (cygpath -w).
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export CONDUCTOR_SERVER_URL="${CONDUCTOR_SERVER_URL:-http://localhost:18088/api}"
export HERMOD_WF_POLL_MS=1000

PLUGIN_JAR="examples/greet-plugin/build/libs/greet-plugin.jar"
PLUGIN_JAR_ABS="$(cygpath -w "$(pwd)/$PLUGIN_JAR" 2>/dev/null || echo "$(pwd)/$PLUGIN_JAR")"
WORKER_LOG=/tmp/conductor-plugin-gate-worker.log

ctl() { ./gradlew -q --no-daemon :conductor-runtime:ctl --args="$*"; }
psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tA -c "$1" | tr -d ' \n\r'; }

kill_worker_jvms() {
  local pids
  pids=$(jps -l 2>/dev/null | grep 'ConductorWorkerMainKt' | awk '{print $1}' || true)
  for p in $pids; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done
}
trap kill_worker_jvms EXIT

wait_log() {   # wait_log <pattern> <timeout_s>
  local deadline=$(( $(date +%s) + $2 ))
  until grep -qE "$1" "$WORKER_LOG" 2>/dev/null; do
    [ "$(date +%s)" -ge "$deadline" ] && return 1
    sleep 2
  done
}

poll_status() {   # poll_status <workflowId> <STATUS> <timeout_s>
  local deadline=$(( $(date +%s) + $3 )) out status
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "result $1" 2>/dev/null || true)
    status=$(echo "$out" | grep '^status=' | cut -d= -f2 | tr -d '\r')
    [ "$status" = "$2" ] && { echo "$out"; return 0; }
    case "$status" in COMPLETED|FAILED|TERMINATED|TIMED_OUT) echo "$out"; return 1 ;; esac
    sleep 3
  done
  echo "$out"; return 1
}

poll_approve() {   # poll_approve <workflowId> <timeout_s>
  local deadline=$(( $(date +%s) + $2 )) out=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out=$(ctl "approve $1" 2>/dev/null || true)
    echo "$out" | grep -q "approve=true" && { echo "$out"; return 0; }
    sleep 3
  done
  echo "$out"; return 1
}

echo "[GATE] waiting for Conductor at http://localhost:18088/health..."
HEALTH_DEADLINE=$(( $(date +%s) + 180 ))
until curl -sf http://localhost:18088/health 2>/dev/null | grep -q '"healthy":true'; do
  [ "$(date +%s)" -ge "$HEALTH_DEADLINE" ] && { echo "[GATE] FAIL: Conductor not healthy within 3 minutes"; exit 1; }
  sleep 3
done

echo "[GATE] step 0: ensure registry schema, unpublish io.example.greet, seed a source row"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "DELETE FROM block_index WHERE id='io.example.greet';" >/dev/null
rm -rf "$PLUGIN_DIR_SH/io.example.greet"
psql_q "DELETE FROM source_rows;" >/dev/null
psql_q "DELETE FROM target_rows;" >/dev/null
psql_q "INSERT INTO source_rows(id,val) VALUES('A1','x') ON CONFLICT(id) DO UPDATE SET val='x';" >/dev/null

echo "[GATE] step 1: build the external greet-plugin (host :core + :sdk to mavenLocal first)"
./gradlew -q --no-daemon :core:publishToMavenLocal :sdk:publishToMavenLocal
( cd examples/greet-plugin && ./gradlew -q build )
[ -f "$PLUGIN_JAR" ] || { echo "[GATE] FAIL: plugin jar not built at $PLUGIN_JAR"; exit 1; }

echo "[GATE] step 2: start the Conductor worker BEFORE the plugin is published"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=plgate-cw ./gradlew -q --no-daemon :conductor-runtime:run >"$WORKER_LOG" 2>&1 &
wait_log 'conductor workers polling' 90 || { echo "[GATE] FAIL: worker did not start polling"; cat "$WORKER_LOG"; exit 1; }
BOOT_LINE=$(grep 'task types=' "$WORKER_LOG" | head -1)
echo "[GATE]   $BOOT_LINE"
if echo "$BOOT_LINE" | grep -q 'io.example.greet'; then BOOT=0; else BOOT=1; fi

echo "[GATE] step 3: publish the plugin jar via the SDK CLI while the worker runs"
PUBLISH_OUT=$(./gradlew -q --no-daemon :app:cli --args="publish '$PLUGIN_JAR_ABS'")
echo "$PUBLISH_OUT" | grep -q "Published greet-plugin.jar successfully." \
  || { echo "[GATE] FAIL: CLI publish did not report success: $PUBLISH_OUT"; exit 1; }

echo "[GATE] step 4: the worker picks the new block type up without a restart"
if wait_log 'now polling newly published block types=\[io\.example\.greet\]' 30; then REFRESH=1; else REFRESH=0; fi
[ "$REFRESH" = "1" ] || { echo "[GATE] FAIL: no refresh line within 30s"; cat "$WORKER_LOG"; exit 1; }

echo "[GATE] step 5: deploy + run demo-with-greet on Conductor, approve the actuate gate"
ctl "deploy demo-with-greet"
WF_ID=$(ctl "start demo-with-greet" | grep '^workflowId=' | cut -d= -f2 | tr -d '\r')
[ -n "$WF_ID" ] || { echo "[GATE] FAIL: start did not return a workflowId"; exit 1; }
echo "[GATE]   started $WF_ID"
poll_status "$WF_ID" RUNNING 90 >/dev/null || { echo "[GATE] FAIL: never RUNNING"; exit 1; }
poll_approve "$WF_ID" 120 >/dev/null || { echo "[GATE] FAIL: the actuate WAIT never became approvable (is greet stuck?)"; ctl "result $WF_ID"; exit 1; }
RESULT=$(poll_status "$WF_ID" COMPLETED 90) || true
echo "[GATE]   $(echo "$RESULT" | grep '^status=')"
if echo "$RESULT" | grep -q '^status=COMPLETED'; then DONE=1; else DONE=0; fi

kill_worker_jvms

echo "[GATE] === evidence from worker log ($WORKER_LOG) ==="
grep -E 'now polling newly published|\[PluginLoader\] loaded io.example.GreetBlock|\[plugin\] forward io.example.greet' "$WORKER_LOG" || true
echo "[GATE] ==========================================="
if grep -q '\[plugin\] forward io.example.greet' "$WORKER_LOG"; then EXEC=1; else EXEC=0; fi
if grep -qE '\[PluginLoader\] loaded io.example.GreetBlock via .*URLClassLoader' "$WORKER_LOG"; then ISO=1; else ISO=0; fi

echo "[GATE] blockers: boot_without_plugin=$BOOT refresh=$REFRESH execution=$EXEC isolation=$ISO completed=$DONE"
if [ "$BOOT" = 1 ] && [ "$REFRESH" = 1 ] && [ "$EXEC" = 1 ] && [ "$ISO" = 1 ] && [ "$DONE" = 1 ]; then
  echo "[GATE] PASS (plugin published while the Conductor worker ran, picked up without restart, ran on Conductor; COMPLETED)"
  exit 0
fi
echo "[GATE] FAIL (see $WORKER_LOG)"
exit 1
