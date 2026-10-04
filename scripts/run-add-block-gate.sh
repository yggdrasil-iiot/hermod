#!/usr/bin/env bash
# §15 v0.2 GATE (objective proof): an EXTERNAL engineer's block jar is published, resolved from the
# hybrid registry, isolation-loaded under a child URLClassLoader, and RUN inside a Temporal saga.
#   This is the Node-RED-impossible property for v0.2: a third-party plugin enters the platform through
#   a contract+jar (not a code edit) and executes with classloader isolation + durable orchestration.
#
# Three trivial-pass blockers (must ALL hold for PASS):
#   (a) EXECUTION : worker log shows `[plugin] forward io.example.greet` (the plugin's forward ran;
#                   only the plugin path prints this).
#   (b) ISOLATION : worker log shows `[PluginLoader] loaded io.example.GreetBlock via ...URLClassLoader...`
#                   (the plugin class was loaded by a child loader, NOT the system loader).
#   (c) DURABILITY: the workflow reaches completed=true.
# Plus a HARD pre-assert: the DB block_index has exactly one row for io.example.greet#1.0.0 AND the
# content-addressed jar artifact exists under the shared plugin dir.
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-add-block-gate.sh   # expect: [GATE] PASS ... exit 0
#
# WINDOWS KILL NOTE (REF run-crash-recovery.sh): `./gradlew run` forks a separate JVM (koshei.app.WorkerKt);
# $! is the wrapper PID, not the JVM. We resolve the real JVM via `jps` and taskkill by PID.
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: the CLI `publish` and the worker MUST agree on the plugin store dir AND the DB, or the
# --- worker resolves a jar_path the CLI never wrote. Pin both here so both child JVMs inherit them. ---
# The store dir reaches the forked JVMs as a Windows path (cygpath -w) so java.io.File reads it correctly;
# shell ops (rm -rf) need the MSYS form, kept separately in PLUGIN_DIR_SH.
PLUGIN_DIR_SH="${KOSHEI_PLUGIN_DIR:-$(pwd)/registry-store}"
export KOSHEI_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export KOSHEI_DB_URL="${KOSHEI_DB_URL:-jdbc:postgresql://localhost:15432/koshei}"
export KOSHEI_DB_USER="${KOSHEI_DB_USER:-koshei}"
export KOSHEI_DB_PASS="${KOSHEI_DB_PASS:-koshei}"

WF="greet-$(date +%s)"
PLUGIN_JAR="examples/greet-plugin/build/libs/greet-plugin.jar"
# The :app:cli JavaExec task runs with workingDir=app/, so a relative jar path would resolve under app/.
# Pass an ABSOLUTE path to the CLI publish so it resolves from the repo root regardless of task cwd.
# cygpath -w: the forked JVM is a native Windows process — give it a Windows path, not an MSYS /c/... path
# (which Gradle/JVM would mangle to C:\c\...). Fall back to the raw path on non-cygwin shells.
PLUGIN_JAR_ABS="$(cygpath -w "$(pwd)/$PLUGIN_JAR" 2>/dev/null || echo "$(pwd)/$PLUGIN_JAR")"
WORKER_LOG="/tmp/gate-worker.log"

psql_q() { docker compose exec -T postgres psql -U koshei -d koshei "$@"; }

kill_worker_jvms() {
  local pids
  pids=$({ jps -l | grep "koshei.app.WorkerKt" || true; } | awk '{print $1}')
  for p in $pids; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done
  sleep 2
}

cleanup() { kill_worker_jvms || true; }
trap cleanup EXIT

echo "[GATE] plugin dir = $KOSHEI_PLUGIN_DIR ; db = $KOSHEI_DB_URL"

echo "[GATE] step 0: ensure registry schema (block_index) + reset registry state + seed a source row"
docker compose exec -T postgres psql -U koshei -d koshei < registry/src/main/resources/registry-schema.sql >/dev/null
# Reset so re-runs are clean (publish rejects duplicate id#version; immutable versions).
psql_q -c "DELETE FROM block_index WHERE id='io.example.greet';" >/dev/null
rm -rf "$PLUGIN_DIR_SH/io.example.greet"
psql_q -c "TRUNCATE source_rows;" >/dev/null
psql_q -c "TRUNCATE target_rows;" >/dev/null
psql_q -c "INSERT INTO source_rows(id,val) VALUES('A1','x') ON CONFLICT(id) DO UPDATE SET val='x';" >/dev/null

# --no-daemon throughout: a daemon captures its env at startup, so the gate's exported KOSHEI_* would not
# reach a pre-existing daemon. With --no-daemon the Gradle launcher JVM inherits THIS shell's env, and the
# app build forwards KOSHEI_* into the forked task JVMs (see app/build.gradle.kts forwardKosheiEnv).
echo "[GATE] step 1: publish host :core + :sdk to mavenLocal"
./gradlew -q --no-daemon :core:publishToMavenLocal :sdk:publishToMavenLocal

echo "[GATE] step 2: build the EXTERNAL greet-plugin against mavenLocal sdk"
( cd examples/greet-plugin && ./gradlew -q build )
[ -f "$PLUGIN_JAR" ] || { echo "[GATE] FAIL: plugin jar not built at $PLUGIN_JAR"; exit 1; }

echo "[GATE] step 3: publish the plugin jar via the SDK CLI"
# Single-quote the path INSIDE --args: Gradle splits --args on whitespace (honouring quotes), and the
# repo path may contain spaces.
PUBLISH_OUT=$(./gradlew -q --no-daemon :app:cli --args="publish '$PLUGIN_JAR_ABS'")
echo "$PUBLISH_OUT"
echo "$PUBLISH_OUT" | grep -q "Published greet-plugin.jar successfully." \
  || { echo "[GATE] FAIL: CLI publish did not report success"; exit 1; }

echo "[GATE] step 4: HARD assert — DB block_index row count + jar artifact on disk"
DB_COUNT=$(psql_q -tAc "SELECT count(*) FROM block_index WHERE id='io.example.greet' AND version='1.0.0'" | tr -d '[:space:]')
echo "[GATE] block_index count for io.example.greet#1.0.0 = $DB_COUNT"
[ "$DB_COUNT" = "1" ] || { echo "[GATE] FAIL: expected exactly 1 block_index row, got $DB_COUNT"; exit 1; }
# Strip only CR/LF (not all whitespace): the stored path may legitimately contain spaces.
JAR_PATH=$(psql_q -tAc "SELECT jar_path FROM block_index WHERE id='io.example.greet' AND version='1.0.0'" | tr -d '\r\n')
echo "[GATE] stored jar_path = $JAR_PATH"
# jar_path is stored as a Windows absolute path; convert to MSYS for the shell file test.
JAR_PATH_SH=$(cygpath -u "$JAR_PATH" 2>/dev/null || echo "$JAR_PATH")
[ -f "$JAR_PATH_SH" ] || { echo "[GATE] FAIL: stored jar artifact not found on disk: $JAR_PATH"; exit 1; }

echo "[GATE] step 5: start worker (binds demo + demo-with-greet); resolve real JVM via jps"
: > "$WORKER_LOG"
KOSHEI_WORKER_NAME=gate-w ./gradlew -q --no-daemon run >"$WORKER_LOG" 2>&1 &
sleep 22   # cold --no-daemon JVM start + bind both workflows + begin polling

echo "[GATE] step 6: run the demo-with-greet workflow $WF (db.read -> ... -> greet -> actuate)"
./gradlew -q --no-daemon starter --args="start $WF - 0 demo-with-greet" >/dev/null
sleep 10   # let it run db.read -> transform.map -> db.upsert -> notify.email -> greet, park at actuate gate

echo "[GATE] step 7: approve the actuate (IRREVERSIBLE) human gate and fetch result"
./gradlew -q --no-daemon starter --args="approve $WF" >/dev/null
sleep 3
RESULT=$(./gradlew -q --no-daemon starter --args="result $WF" | grep -E "completed=")
echo "[GATE] $RESULT"

kill_worker_jvms

echo "[GATE] === evidence from worker log ($WORKER_LOG) ==="
grep -E "\[PluginLoader\] loaded io.example.GreetBlock|\[plugin\] forward io.example.greet" "$WORKER_LOG" || true
echo "[GATE] ==========================================="

# Blocker (b): isolation — plugin class loaded by a child URLClassLoader (NOT the system loader).
if grep -E "\[PluginLoader\] loaded io.example.GreetBlock via .*URLClassLoader" "$WORKER_LOG" >/dev/null; then
  ISO=1; else ISO=0; fi
# Blocker (a): execution — the plugin's forward ran.
if grep -q "\[plugin\] forward io.example.greet" "$WORKER_LOG"; then EXEC=1; else EXEC=0; fi
# Blocker (c): durability — workflow completed.
if echo "$RESULT" | grep -q "completed=true"; then DONE=1; else DONE=0; fi

echo "[GATE] blockers: execution=$EXEC isolation=$ISO completed=$DONE (db_count=$DB_COUNT)"
if [ "$EXEC" = "1" ] && [ "$ISO" = "1" ] && [ "$DONE" = "1" ] && [ "$DB_COUNT" = "1" ]; then
  echo "[GATE] PASS (external block published+resolved+isolation-loaded+ran in saga; completed=true)"
  exit 0
else
  echo "[GATE] FAIL (execution=$EXEC isolation=$ISO completed=$DONE db_count=$DB_COUNT; see $WORKER_LOG)"
  exit 1
fi
