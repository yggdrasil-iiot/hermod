#!/usr/bin/env bash
# §11 v0.2b GATE (objective): one engine-neutral IR -> two engine targets. PASS = 4 hard asserts.
set -euo pipefail
cd "$(dirname "$0")/.."
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}" HERMOD_DB_USER=hermod HERMOD_DB_PASS=hermod
export HERMOD_TEST_BLOCKS=1
WORKER_LOG=/tmp/compiler-gate-worker.log

# The :app:cli JavaExec task runs with workingDir=app/, so relative paths resolve under app/.
# Pass an ABSOLUTE Windows path so java.io.File resolves correctly from the forked JVM.
FIXTURE_ABS="$(cygpath -w "$(pwd)/scripts/fixtures/mistyped.yaml" 2>/dev/null || echo "$(pwd)/scripts/fixtures/mistyped.yaml")"

kill_worker_jvms() { { jps -l | grep yggdrasil.hermod.app.WorkerKt || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
trap kill_worker_jvms EXIT

echo "[GATE] assert 1: determinism — compile demo twice, byte-equal Conductor JSON"
A=$(./gradlew -q --no-daemon :app:cli --args="compile demo --target conductor")
B=$(./gradlew -q --no-daemon :app:cli --args="compile demo --target conductor")
[ "$A" = "$B" ] || { echo "[GATE] FAIL: non-deterministic emit"; exit 1; }

echo "[GATE] assert 2: type-check load-bearing — mistyped workflow REJECTED"
if ./gradlew -q --no-daemon :app:cli --args="compile --file '$FIXTURE_ABS' --target conductor" 2>/tmp/mistyped.err; then
  echo "[GATE] FAIL: mistyped compiled (type-check not load-bearing)"; exit 1
fi
grep -q "type mismatch" /tmp/mistyped.err || { echo "[GATE] FAIL: no type-mismatch diagnostic"; cat /tmp/mistyped.err; exit 1; }

echo "[GATE] assert 4: Conductor emit + schema validation (emit() self-validates via typed round-trip)"
echo "$A" | grep -q '"taskReferenceName"' || { echo "[GATE] FAIL: no conductor tasks in emit"; exit 1; }

echo "[GATE] assert 3: Temporal run + [compiler] marker (IR load-bearing)"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=cgate ./gradlew -q --no-daemon run >"$WORKER_LOG" 2>&1 &
sleep 22
WF="cir-$(date +%s)"
./gradlew -q --no-daemon starter --args="start $WF - 0 demo" >/dev/null
sleep 8
./gradlew -q --no-daemon starter --args="approve $WF" >/dev/null
sleep 3
RESULT=$(./gradlew -q --no-daemon starter --args="result $WF" | grep -E "completed=")
kill_worker_jvms

echo "$RESULT" | grep -q "completed=true" || { echo "[GATE] FAIL: workflow not completed; $RESULT"; exit 1; }
grep -q "\[compiler\] compiled demo nodes=" "$WORKER_LOG" || { echo "[GATE] FAIL: no [compiler] marker (IR not on exec path)"; exit 1; }

echo "[GATE] assert 5: semver — latest/^ pinned to concrete version at compile"
LATEST_F="$(cygpath -w "$(pwd)/scripts/fixtures/latest-demo.yaml" 2>/dev/null || echo "$(pwd)/scripts/fixtures/latest-demo.yaml")"
LATEST_OUT=$(./gradlew -q --no-daemon :app:cli --args="compile --file '$LATEST_F' --target conductor")
echo "$LATEST_OUT" | grep -q '"name":"db.upsert"' || { echo "[GATE] FAIL: db.upsert task missing"; exit 1; }
echo "$LATEST_OUT" | grep -q '"_pinnedVersion":"1.2.0"' || { echo "[GATE] FAIL: caret not pinned to concrete 1.2.0"; echo "$LATEST_OUT"; exit 1; }
if echo "$LATEST_OUT" | grep -q '\^1.0.0'; then echo "[GATE] FAIL: range string leaked into emitted JSON"; exit 1; fi

echo "[GATE] assert 6: lint — irreversible-ordering REJECTED"
BAD_F="$(cygpath -w "$(pwd)/scripts/fixtures/bad-order.yaml" 2>/dev/null || echo "$(pwd)/scripts/fixtures/bad-order.yaml")"
if ./gradlew -q --no-daemon :app:cli --args="compile --file '$BAD_F' --target conductor" 2>/tmp/badorder.err; then
  echo "[GATE] FAIL: bad-order compiled (lint E1 not load-bearing)"; exit 1
fi
grep -q "irreversible-ordering" /tmp/badorder.err || { echo "[GATE] FAIL: no irreversible-ordering diagnostic"; cat /tmp/badorder.err; exit 1; }

echo "[GATE] blockers: determinism=1 typecheck_reject=1 temporal_run=1 conductor_emit=1 semver_pin=1 lint_reject=1"
echo "[GATE] PASS (one IR -> Temporal run + Conductor emit; semver pinned; lint enforced)"
