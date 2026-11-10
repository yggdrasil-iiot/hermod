#!/usr/bin/env bash
# R4 auto-correct poller live gate (objective proof): hermod periodically sweeps every deployed unit,
# detects field-transition drift (recording the observation exactly as `fsm drift-check` does), and on
# DRIFT evaluates the corrective SafeHold via the pure TransitionGovernor — classifying each unit as
# BASELINE / OK / DRIFT-CORRECTABLE (a governed ot-safe-hold is available, awaiting operator) /
# DRIFT-BLOCKED (no declared safe path) / SKIPPED. Detect + evaluate + alarm ONLY — hermod NEVER
# dispatches or approves here (human-in-the-loop). Driven via the deterministic `fsm auto-correct-sweep`
# CLI (the @Scheduled AutoCorrectBean wraps the SAME AutoCorrectSupervisor.sweep). See design 2026-07-03.
#
# Bootstrap is deliberately SERVER-LESS (no worker/authoring-api): only the embedded Milo OPC-UA sim
# (for the live state node, perturbed out-of-band exactly like run-fsm-drift-gate.sh) + Postgres (for
# FsmDeploymentStore's active-version pointer + DriftStore's observation/audit log). Driving the CLI
# (not the @Scheduled bean) avoids a bean-vs-CLI race on the shared drift_observation pointer.
#
# One coherent ordered path (one unit, one truncate; a BASELINE row is emitted only on the FIRST obs):
#   set_state 4  -> BASELINE line1 4
#   set_state 6  -> OK line1 4 -> 6              (Idle->Execute, declared `start`)
#   set_state 11 -> OK line1 6 -> 11             (Execute->Held, declared field `hold`)
#   set_state 6  -> DRIFT-CORRECTABLE line1 11 -> 6: ot-safe-hold   (Held->Execute undeclared; SafeHold from Execute)
#   set_state 99 -> DRIFT-BLOCKED line1 6 -> 99  (unknown state; govern DENY)
#
# Regressions (run-fsm-drift-gate.sh / run-fsm-hold-gate.sh / run-fsm-gate.sh) each boot their OWN sim on
# :48400 -> run them SEPARATELY (an inline chain collides on the sim port with this gate's stack).
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-fsm-autocorrect-gate.sh   # expect: [GATE] PASS run-fsm-autocorrect-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/lib/gate-common.sh   # for native_path; include-guarded, safe alongside inline psql_q

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:48400}"   # OpcUaApplyPort/FsmStateReader read this

WORK="build/fsm-autocorrect-gate"
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
echo "[GATE] step 0: ensure registry schema (fsm_deployment*/drift_observation/drift_audit) + reset"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
psql_q "TRUNCATE drift_observation, drift_audit, fsm_deployment, fsm_deployment_audit" >/dev/null
echo "[GATE] schema ensured; state reset"

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
sweep_line() { fsm_cli auto-correct-sweep | grep -E '^(BASELINE|OK|DRIFT-CORRECTABLE|DRIFT-BLOCKED|SKIPPED) ' || true; }
drift_aud() { psql_q "SELECT count(*) FROM drift_audit WHERE unit='line1' AND verdict='$1' AND to_code=$2"; }

echo "[GATE] step 2: deploy line1 v1 so the sweep set is non-empty and drift resolves the active version"
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "prep: deploy line1 v1 failed"
echo "[GATE] line1 active v1"

echo "[GATE] ===== T1: BASELINE @ Idle(4.0) — first observation ====="
set_state "4.0"
OUT=$(sweep_line); echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "BASELINE line1 4" || fail "T1 expected 'BASELINE line1 4', got: $OUT"
[ "$(drift_aud BASELINE 4)" -ge 1 ] || fail "T1 expected a BASELINE drift_audit row for to_code=4"
echo "[GATE] T1 OK"

echo "[GATE] ===== T2: OK — Idle(4.0) -> Execute(6.0), declared 'start' ====="
set_state "6.0"
OUT=$(sweep_line); echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "OK line1 4 -> 6" || fail "T2 expected 'OK line1 4 -> 6', got: $OUT"
echo "[GATE] T2 OK"

echo "[GATE] ===== T3: OK — Execute(6.0) -> Held(11.0), declared field 'hold' ====="
set_state "11.0"
OUT=$(sweep_line); echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "OK line1 6 -> 11" || fail "T3 expected 'OK line1 6 -> 11', got: $OUT"
echo "[GATE] T3 OK"

echo "[GATE] ===== T4: DRIFT-CORRECTABLE — Held(11.0) -> Execute(6.0) undeclared; SafeHold from Execute ====="
set_state "6.0"
OUT=$(sweep_line); echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "DRIFT-CORRECTABLE line1 11 -> 6: ot-safe-hold" || fail "T4 expected 'DRIFT-CORRECTABLE line1 11 -> 6: ot-safe-hold', got: $OUT"
[ "$(drift_aud DRIFT 6)" -ge 1 ] || fail "T4 expected a DRIFT drift_audit row for to_code=6"
echo "[GATE] T4 OK: bypass detected AND a governed corrective path exists (alarm, no dispatch)"

echo "[GATE] ===== T5: DRIFT-BLOCKED — Execute(6.0) -> unknown(99.0); govern DENY ====="
set_state "99.0"
OUT=$(sweep_line); echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "DRIFT-BLOCKED line1 6 -> 99" || fail "T5 expected 'DRIFT-BLOCKED line1 6 -> 99', got: $OUT"
echo "$OUT" | grep -q "unknown state code 99" || fail "T5 expected reason 'unknown state code 99', got: $OUT"
[ "$(drift_aud DRIFT 99)" -ge 1 ] || fail "T5 expected a DRIFT drift_audit row for to_code=99"
echo "[GATE] T5 OK: bypass into an unidentifiable state -> no corrective path (fail-closed alarm)"

echo ""
echo "[GATE] PASS run-fsm-autocorrect-gate.sh"
exit 0
