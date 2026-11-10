#!/usr/bin/env bash
# R4 FSM field-transition drift-detect live gate (objective proof): hermod observes the equipment's
# LIVE state node for a unit's ACTIVE FSM-spec version and compares each new observation against the
# LAST one persisted, purely via the pure yggdrasil.hermod.opcua.DriftDetector + the :app `fsm drift-check` CLI
# subcommand. Detect-only — no governed run is dispatched, no equipment is driven. See design
# 2026-07-03 / plan Chunk 3.
#
# Bootstrap is deliberately server-less (no worker/authoring-api): only the embedded Milo OPC-UA sim
# (for the live state node, perturbed out-of-band exactly like run-fsm-gate.sh/run-fsm-canary-gate.sh)
# + Postgres (for FsmDeploymentStore's active-version pointer + DriftStore's observation/audit log).
#
# Asserts (drift_audit rows via psql; stdout via `fsm drift-check`, grep-matched — NEVER exact-matched,
# since OpcUaApplyPort.connect() prints a leading "[opcua] connecting to ..." line before the verdict):
#   T1  BASELINE : first observation @ Idle(4.0)             -> no prior -> BASELINE recorded, no verdict.
#   T2  OK       : Idle(4.0) -> Execute(6.0) (declared)       -> OK.
#   T3  DRIFT    : Execute(6.0) -> Idle(4.0) (undeclared)     -> DRIFT "undeclared transition Execute -> Idle".
#   T4  DRIFT    : Idle(4.0) -> 99.0 (not a declared state)   -> DRIFT "observed state code 99 not declared".
#   T5  regression (opt-in, default skipped): run-fsm-gate.sh unchanged (T1/T2 governance still pass).
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-fsm-drift-gate.sh   # expect: [GATE] PASS run-fsm-drift-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/lib/gate-common.sh   # for native_path; include-guarded, safe alongside inline psql_q

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:48400}"   # OpcUaApplyPort.default() / FsmStateReader read this in the CLI JVM
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)

WORK="build/fsm-drift-gate"
mkdir -p "$WORK"
SIM_LOG="$WORK/sim.log"

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

kill_sim_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.opcua.SimMainKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}
cleanup() { kill_sim_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- sim log tail ---"; tail -20 "$SIM_LOG" 2>/dev/null || true
  exit 1
}

echo "[GATE] db = $HERMOD_DB_URL ; opcua = $HERMOD_OPCUA_URL"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: ensure schemas (registry incl fsm_deployment*/drift_observation/drift_audit) + reset"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "TRUNCATE drift_observation, drift_audit, fsm_deployment, fsm_deployment_audit" >/dev/null
echo "[GATE] schemas ensured; state reset"

# ---------------------------------------------------------------------------
echo "[GATE] step 0.5: start the embedded Milo OPC-UA sim and wait for it to listen"
: > "$SIM_LOG"
./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
SIMUP=0
for i in $(seq 1 90); do
  if grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null; then SIMUP=1; break; fi
  sleep 2
done
[ "$SIMUP" = "1" ] || { echo "--- sim log ---"; cat "$SIM_LOG" 2>/dev/null || true; fail "OPC-UA sim did not start (no 'OPC-UA sim listening' within ~180s)"; }
echo "[GATE] OPC-UA sim listening on $HERMOD_OPCUA_URL"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: build :app"
./gradlew -q --no-daemon :app:build -x test >/dev/null

# ---------------------------------------------------------------------------
STATE_NODE="ns=2;s=Line1/StateCurrent"
fsm_cli() { ./gradlew -q --console=plain --no-daemon :app:cli --args="fsm $*"; }
set_state() { ./gradlew -q --no-daemon :opcua:perturb -Pnode="$STATE_NODE" -Pvalue="$1" >/dev/null 2>&1; }
drift_aud() { psql_q "SELECT count(*) FROM drift_audit WHERE unit='line1' AND verdict='$1' AND to_code=$2"; }

echo "[GATE] step 2: deploy line1 v1 so drift-check resolves the active version"
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "prep: deploy line1 v1 failed"
echo "[GATE] line1 active v1"

echo "[GATE] ===== T1: BASELINE @ Idle(4.0) — first observation, no prior ====="
set_state "4.0"
OUT=$(fsm_cli drift-check line1) || fail "T1 drift-check exited non-zero: $OUT"
echo "[GATE] drift-check: $OUT"
echo "$OUT" | grep -q '^baseline line1 4' || fail "T1 expected 'baseline line1 4', got: $OUT"
[ "$(drift_aud BASELINE 4)" -ge 1 ] || fail "T1 expected a BASELINE drift_audit row for to_code=4"
echo "[GATE] T1 OK"

echo "[GATE] ===== T2: OK — Idle(4.0) -> Execute(6.0), declared transition ====="
set_state "6.0"
OUT=$(fsm_cli drift-check line1) || fail "T2 drift-check exited non-zero: $OUT"
echo "[GATE] drift-check: $OUT"
echo "$OUT" | grep -q 'OK line1 4 -> 6' || fail "T2 expected 'OK line1 4 -> 6', got: $OUT"
[ "$(drift_aud OK 6)" -ge 1 ] || fail "T2 expected an OK drift_audit row for to_code=6"
echo "[GATE] T2 OK"

echo "[GATE] ===== T3: DRIFT (undeclared) — Execute(6.0) -> Idle(4.0), no such transition ====="
set_state "4.0"
OUT=$(fsm_cli drift-check line1) || fail "T3 drift-check exited non-zero: $OUT"
echo "[GATE] drift-check: $OUT"
echo "$OUT" | grep -q 'DRIFT line1 6 -> 4' || fail "T3 expected 'DRIFT line1 6 -> 4', got: $OUT"
echo "$OUT" | grep -q 'undeclared transition Execute -> Idle' || fail "T3 expected reason 'undeclared transition Execute -> Idle', got: $OUT"
[ "$(drift_aud DRIFT 4)" -ge 1 ] || fail "T3 expected a DRIFT drift_audit row for to_code=4"
echo "[GATE] T3 OK"

echo "[GATE] ===== T4: DRIFT (unknown state) — Idle(4.0) -> 99.0, not a declared state ====="
set_state "99.0"
OUT=$(fsm_cli drift-check line1) || fail "T4 drift-check exited non-zero: $OUT"
echo "[GATE] drift-check: $OUT"
echo "$OUT" | grep -q 'DRIFT line1 4 -> 99' || fail "T4 expected 'DRIFT line1 4 -> 99', got: $OUT"
echo "$OUT" | grep -q 'observed state code 99 not declared' || fail "T4 expected reason 'observed state code 99 not declared', got: $OUT"
[ "$(drift_aud DRIFT 99)" -ge 1 ] || fail "T4 expected a DRIFT drift_audit row for to_code=99"
echo "[GATE] T4 OK"

echo "[GATE] ===== T5: regression (opt-in) — run-fsm-gate.sh still governs correctly ====="
if [ "${DRIFT_GATE_RUN_FSM:-0}" = "1" ]; then
  bash scripts/run-fsm-gate.sh > "$WORK/fsm.out" 2>&1 && grep -q "\[GATE\] PASS" "$WORK/fsm.out" || fail "T5"
  echo "[GATE] T5 OK"
else
  echo "[GATE] T5 SKIPPED — run run-fsm-gate.sh separately, or DRIFT_GATE_RUN_FSM=1"
fi

echo ""
echo "[GATE] PASS run-fsm-drift-gate.sh"
exit 0
