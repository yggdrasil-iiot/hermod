#!/usr/bin/env bash
# R4 auto-correct AUTO-DISPATCH -> park-for-approval live gate (design 2026-07-04). On a swept DriftCorrectable,
# koshei auto-dispatches the ot-safe-hold governed saga via POST /api/autocorrect/sweep; the saga parks at the
# intrinsic IRREVERSIBLE human gate (safeHoldCall AWAITING_APPROVAL). A drift_correction PENDING row dedups: a re-swept
# drift for the same unit while a correction is in flight does NOT storm a second run. On operator approve the
# saga issues the governed command; the field/PLC reaches Held (:opcua:perturb) and the next sweep sees the
# DECLARED Execute->Held = drift resolved, and reconcile flips the row PENDING->RESOLVED. A terminalized unit
# re-dispatches on a NEW drift. Bootstrap REPLICATES run-fsm-hold-gate.sh; the @Scheduled bean is OFF
# (KOSHEI_AUTOCORRECT_DISABLED=1) so the endpoint is the only sweep driver (no race). Module count 12.
#
# Run from repo root with the stack up:
#   docker compose up -d
#   bash scripts/run-fsm-autocorrect-dispatch-gate.sh   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/lib/gate-common.sh   # native_path; include-guarded, safe alongside inline psql_q

PLUGIN_DIR_SH="${KOSHEI_PLUGIN_DIR:-$(pwd)/registry-store}"
export KOSHEI_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export KOSHEI_DB_URL="${KOSHEI_DB_URL:-jdbc:postgresql://localhost:15432/koshei}"
export KOSHEI_DB_USER="${KOSHEI_DB_USER:-koshei}"
export KOSHEI_DB_PASS="${KOSHEI_DB_PASS:-koshei}"
export KOSHEI_WF_POLL_MS="${KOSHEI_WF_POLL_MS:-3000}"
export KOSHEI_OPCUA_URL="${KOSHEI_OPCUA_URL:-opc.tcp://localhost:48400}"
export KOSHEI_MODEL_DIR="$(native_path "${KOSHEI_MODEL_DIR:-$(pwd)/model}")"   # native_path (cygpath -m): a native JVM rejects an MSYS /c/... path
export KOSHEI_AUTOCORRECT_DISABLED=1                          # @Scheduled bean OFF — endpoint is the only sweep driver

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
WORK="build/fsm-autocorrect-dispatch-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"; API_LOG="$WORK/api.log"; SIM_LOG="$WORK/sim.log"

psql_q() { docker compose exec -T postgres psql -U koshei -d koshei -tAc "$1" | tr -d '[:space:]'; }

kill_worker_jvms() { { jps -l 2>/dev/null | grep "koshei.app.WorkerKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_api_jvms() { { jps -l 2>/dev/null | grep "koshei.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_sim_jvms() { { jps -l 2>/dev/null | grep "koshei.opcua.SimMainKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_sim_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
  echo "--- sim log tail ---";    tail -20 "$SIM_LOG"    2>/dev/null || true
  exit 1
}

echo "[GATE] db=$KOSHEI_DB_URL opcua=$KOSHEI_OPCUA_URL model=$KOSHEI_MODEL_DIR"

echo "[GATE] step 0: ensure BOTH schemas + reset (incl. drift_correction)"
docker compose exec -T postgres psql -U koshei -d koshei < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U koshei -d koshei < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE drift_observation, drift_audit, drift_correction, fsm_deployment, fsm_deployment_audit, command_audit, source_rows, target_rows, workflow_def, run_index, fault_inject" >/dev/null
echo "[GATE] schemas ensured; state reset"

echo "[GATE] step 0.5: start the embedded Milo sim"
: > "$SIM_LOG"; ./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
SIMUP=0; for i in $(seq 1 90); do grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null && { SIMUP=1; break; }; sleep 2; done
[ "$SIMUP" = "1" ] || { cat "$SIM_LOG" 2>/dev/null || true; fail "sim did not start"; }
echo "[GATE] sim listening"

echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start worker then API"
: > "$WORKER_LOG"; KOSHEI_WORKER_NAME=ac-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
WUP=0; for i in $(seq 1 60); do grep -q "starting; polling" "$WORKER_LOG" 2>/dev/null && { WUP=1; break; }; sleep 2; done
[ "$WUP" = "1" ] || fail "worker did not start"
: > "$API_LOG"; ./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0; for i in $(seq 1 60); do curl -sf "$API/api/workflows" >/dev/null 2>&1 && { UP=1; break; }; sleep 2; done
[ "$UP" = "1" ] || fail "API did not answer"
echo "[GATE] worker + API up"

STATE_NODE="ns=2;s=Line1/StateCurrent"
fsm_cli() { ./gradlew -q --console=plain --no-daemon :app:cli --args="fsm $*"; }
set_state() { ./gradlew -q --no-daemon :opcua:perturb -Pnode="$STATE_NODE" -Pvalue="$1" >/dev/null 2>&1; }
sweep_ep() { curl -fsS -X POST "$API/api/autocorrect/sweep"; }
pending_count() { psql_q "SELECT count(*) FROM drift_correction WHERE unit='line1' AND status='PENDING'"; }
run_count() { psql_q "SELECT count(*) FROM run_index WHERE workflow_name='ot-safe-hold'"; }
pending_runid() { psql_q "SELECT run_id FROM drift_correction WHERE unit='line1' AND status='PENDING' ORDER BY id DESC LIMIT 1"; }
corr_status_of() { psql_q "SELECT status FROM drift_correction WHERE run_id='$1'"; }
wait_node() {
  local run="$1" node="$2" state="$3" tries="${4:-30}"
  for i in $(seq 1 "$tries"); do curl -fsS "$API/api/runs/$run/nodes" | grep -qE "\"$node\":\"$state\"" && return 0; sleep 2; done
  return 1
}

echo "[GATE] step 3: deploy line1 v1 + save ot-safe-hold@1.0.0 + poll-bind + seed interlock"
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "deploy line1 v1 failed"
SAVE_CODE=$(curl -s -o "$WORK/save.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-safe-hold.json")
[ "$SAVE_CODE" = "200" ] || fail "save ot-safe-hold@1.0.0 expected 200, got $SAVE_CODE"
SLEEP_S=$(( (KOSHEI_WF_POLL_MS / 1000) + 6 )); sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-safe-hold@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind ot-safe-hold@1.0.0"
psql_q "INSERT INTO source_rows(id,val) VALUES ('line1.interlock','1') ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val" >/dev/null
echo "[GATE] deployed + bound + seeded"

echo "[GATE] ===== bring line1 to Held (baseline -> start -> field hold) ====="
set_state "4.0";  sweep_ep >/dev/null
set_state "6.0";  sweep_ep >/dev/null
set_state "11.0"; sweep_ep >/dev/null
[ "$(pending_count)" = "0" ] || fail "bring-up must leave 0 PENDING corrections, got $(pending_count)"
echo "[GATE] bring-up OK (0 corrections)"

echo "[GATE] ===== T1: Held(11)->Execute(6) bypass = DRIFT -> AUTO-DISPATCH + park ====="
set_state "6.0"
RESP=$(sweep_ep); echo "[GATE] sweep: $RESP"
echo "$RESP" | grep -q '"correctable":\["line1"\]' || fail "T1 expected correctable line1, got: $RESP"
[ "$(pending_count)" = "1" ] || fail "T1 expected exactly 1 PENDING correction, got $(pending_count)"
[ "$(run_count)" = "1" ] || fail "T1 expected exactly 1 ot-safe-hold run, got $(run_count)"
RUN1="$(pending_runid)"; echo "[GATE] auto-dispatched run: $RUN1"
# The IRREVERSIBLE human gate records AWAITING_APPROVAL while parked (SagaWorkflowImpl.kt:75, the operator
# legibility fix 9d1fd34; "RUNNING" only appears briefly AFTER approval). Tolerate both, mirroring the
# scenario/statepersist/conductor gates the same fix made tolerant.
wait_node "$RUN1" safeHoldCall "(RUNNING|AWAITING_APPROVAL)" || fail "T1 run never parked at the human gate (safeHoldCall AWAITING_APPROVAL)"
# loose-ends: the partial-unique index must reject a 2nd PENDING for line1 (dedup atomicity). A *successful*
# insert is the failure. NOTE: don't pipe psql_q | grep (set -euo pipefail + psql_q is itself a pipeline →
# false-fails on the expected error); use it as an if-condition (a non-zero there doesn't trip set -e).
if psql_q "INSERT INTO drift_correction(unit,run_id,from_code,to_code,workflow,status) VALUES ('line1','dup-x',11,6,'ot-safe-hold','PENDING')" >/dev/null 2>&1; then
  fail "partial-unique index did not reject a 2nd PENDING for line1"
fi
[ "$(pending_count)" = "1" ] || fail "still exactly one PENDING for line1 after the rejected insert, got $(pending_count)"
echo "[GATE] T1 OK: auto-dispatched + parked for approval; partial-unique index rejected a 2nd PENDING"

echo "[GATE] ===== T2: re-fire drift while parked -> DEDUP (no second dispatch) ====="
set_state "11.0"; sweep_ep >/dev/null            # OK 6->11 (declared); run still parked -> stays PENDING
set_state "6.0";  RESP=$(sweep_ep); echo "[GATE] re-sweep: $RESP"
# The drift MUST re-fire (correctable again) so the pass genuinely depends on the dedup guard suppressing a
# second dispatch — not on the drift simply not being re-detected (which would be a false green).
echo "$RESP" | grep -q '"correctable":\["line1"\]' || fail "T2 dedup: drift must RE-FIRE (correctable line1) so dedup is actually exercised, got: $RESP"
[ "$(pending_count)" = "1" ] || fail "T2 dedup: PENDING count must stay 1, got $(pending_count)"
[ "$(run_count)" = "1" ] || fail "T2 dedup: ot-safe-hold run count must stay 1, got $(run_count)"
[ "$(pending_runid)" = "$RUN1" ] || fail "T2 dedup: the pending run must still be $RUN1"
echo "[GATE] T2 OK: dedup held (still 1 PENDING, 1 run)"

echo "[GATE] ===== T3: approve -> Held -> drift resolved + correction RESOLVED ====="
curl -fsS -X POST "$API/api/runs/$RUN1/approve" >/dev/null || fail "T3 approve failed"
curl -fsS "$API/api/runs/$RUN1?wait=true" | grep -q '"completed":true' || fail "T3 run did not complete"
set_state "11.0"                                  # field/PLC responds to the approved command -> Held
RESP=$(sweep_ep); echo "[GATE] resolve-sweep: $RESP"
echo "$RESP" | grep -q '"correctable":\[\]' || fail "T3 expected empty correctable (drift resolved), got: $RESP"
[ "$(corr_status_of "$RUN1")" = "RESOLVED" ] || fail "T3 correction must be RESOLVED, got $(corr_status_of "$RUN1")"
[ "$(pending_count)" = "0" ] || fail "T3 expected 0 PENDING after resolve, got $(pending_count)"
echo "[GATE] T3 OK: approved -> Held -> resolved (RESOLVED, 0 pending)"

echo "[GATE] ===== T4: new drift on a terminalized unit -> RE-DISPATCH ====="
set_state "6.0"
RESP=$(sweep_ep); echo "[GATE] re-dispatch-sweep: $RESP"
echo "$RESP" | grep -q '"correctable":\["line1"\]' || fail "T4 expected correctable line1, got: $RESP"
[ "$(pending_count)" = "1" ] || fail "T4 expected 1 new PENDING correction, got $(pending_count)"
[ "$(run_count)" = "2" ] || fail "T4 expected 2 total ot-safe-hold runs, got $(run_count)"
RUN2="$(pending_runid)"; [ "$RUN2" != "$RUN1" ] || fail "T4 re-dispatch must be a NEW run, got $RUN2"
echo "[GATE] T4 OK: terminalized unit re-dispatched on new drift ($RUN2)"

echo ""
echo "[GATE] PASS run-fsm-autocorrect-dispatch-gate.sh"
exit 0
