#!/usr/bin/env bash
# INTEGRATION PoV GATE (objective cross-repo proof): drift-triggered governed reconciliation.
#
# Two pillars, one closed loop on a SHARED Milo sim + SHARED Git canonical:
#   - resequence-twin-lab (pillar 3) detects recipe-setpoint drift (read-only) and surfaces a
#     RECONCILE_SETPOINT proposal at GET :8081/api/drift.
#   - hermod (pillar 2) accepts a SIGNAL at POST /api/reconciliations, resolves the desired value from
#     its OWN Git canonical (model/recipe-setpoints.yaml), and drives the field back via the existing
#     human-gated ot-recipe-stage-activate saga (R1 OPC-UA write/call).
#
# Asserts:
#   T1 happy   : inject drift -> twin reports it -> reconcile -> approve -> field back to canonical ->
#                twin drift CLEARED + command_audit WRITTEN+CONFIRMED.
#   T2 veto    : inject drift -> reconcile to the gate -> REJECT -> opcua.write RESTORED -> field
#                unchanged -> twin drift PERSISTS (the human veto really blocked the change).
#   T3 boundary: an ungoverned node signal -> 400, no run, no field write (signal-only trust boundary).
#
# R1 is untouched (run-opcua-gate.sh, ot-recipe-stage-activate, :opcua/src/main, Conductor[frozen]).
#
# Run from the hermod repo root with the stack up + three sibling checkouts:
#   docker compose up -d
#   SPARKPLUG_LAB_DIR=".../sparkplug-governance-lab" BIFROST_DIR=".../bifrost" RESEQUENCE_DIR=".../resequence-twin-lab" \
#     bash scripts/run-integration-pov-gate.sh
#   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_WF_POLL_MS="${HERMOD_WF_POLL_MS:-3000}"
export HERMOD_FAULT_INJECT=1
export HERMOD_OPCUA_URL="${HERMOD_OPCUA_URL:-opc.tcp://localhost:48400}"
export HERMOD_APPLY_MODE=direct   # this gate proves the direct OPC UA write path, which bypasses Heimdall; it must be named explicitly (default is ncmd)

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
DRIFT="http://localhost:8081/api/drift"
WORK="build/integration-pov-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"
SIM_LOG="$WORK/sim.log"
RESEQ_LOG="$WORK/resequence.log"
PERTURB_LOG="$WORK/perturb.log"
: > "$PERTURB_LOG"

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

kill_worker_jvms() { { jps -l 2>/dev/null | grep "yggdrasil.hermod.app.WorkerKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_api_jvms()    { { jps -l 2>/dev/null | grep "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_sim_jvms()    { { jps -l 2>/dev/null | grep "yggdrasil.hermod.opcua.SimMainKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_reseq_jvms()  { { jps -l 2>/dev/null | grep "ResequenceTwinControlApplication" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_sim_jvms || true; kill_reseq_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
  echo "--- sim log tail ---";    tail -20 "$SIM_LOG"    2>/dev/null || true
  echo "--- resequence log tail ---"; tail -40 "$RESEQ_LOG" 2>/dev/null || true
  exit 1
}

# Cross-repo dependency: the resequence checkout (the dir containing control/pom.xml).
RESEQUENCE_DIR="${RESEQUENCE_DIR:-}"
[ -n "$RESEQUENCE_DIR" ] || fail "RESEQUENCE_DIR not set — point it at the resequence-twin-lab checkout (the dir containing control/pom.xml). No silent skip of the integration assertions."
[ -f "$RESEQUENCE_DIR/control/pom.xml" ] || fail "no control/pom.xml under RESEQUENCE_DIR='$RESEQUENCE_DIR'"
# v3: hermod consumes a ①-PUBLISHED canonical (no working-tree classpath fallback) — its reconcile preflight
# needs a sibling manifest.json to verify, so integration-pov must publish too (else T1 → 409 unresolvable).
SPARKPLUG_LAB_DIR="${SPARKPLUG_LAB_DIR:-}"
[ -n "$SPARKPLUG_LAB_DIR" ] || fail "SPARKPLUG_LAB_DIR not set — point it at the sparkplug-governance-lab checkout (source of model/recipe-setpoints.yaml). No silent skip."
[ -d "$SPARKPLUG_LAB_DIR/.git" ] || fail "SPARKPLUG_LAB_DIR='$SPARKPLUG_LAB_DIR' is not a git checkout (the published yaml is read from its history)"
BIFROST_DIR="${BIFROST_DIR:-}"
[ -n "$BIFROST_DIR" ] || fail "BIFROST_DIR not set — point it at a bifrost checkout (RecipePublish CLI in core). No silent skip."
[ -f "$BIFROST_DIR/core/pom.xml" ] || fail "no core/pom.xml under BIFROST_DIR='$BIFROST_DIR'"
# ①'s registry/ is tracked → publish to a THROWAWAY dir; mvn exec:java is a Windows JVM → cygpath -m every path arg.
REG="$SPARKPLUG_LAB_DIR/build/gate-registry"; rm -rf "$REG"
REG_WIN="$(cygpath -m "$REG" 2>/dev/null || echo "$REG")"
LAB_WIN="$(cygpath -m "$SPARKPLUG_LAB_DIR" 2>/dev/null || echo "$SPARKPLUG_LAB_DIR")"
# RecipePublish lives in bifrost core (it moved out of the lab); the lab stays the source repo of the yaml.
# The paths contain spaces, so each is quoted inside exec.args.
( cd "$BIFROST_DIR" && mvn -q -pl core -am -DskipTests install && \
  mvn -q -f core/pom.xml exec:java -Dexec.mainClass=yggdrasil.bifrost.core.schema.RecipePublish \
    "-Dexec.args=\"$REG_WIN\" \"$LAB_WIN\" model/recipe-setpoints.yaml line1 1.0.0" ) >"$WORK/publish.log" 2>&1 \
  || { cat "$WORK/publish.log"; fail "① publish failed"; }
CANON_RAW="$REG/recipe/line1/1.0.0/recipe-setpoints.yaml"
CANON="$(cygpath -m "$CANON_RAW" 2>/dev/null || echo "$CANON_RAW")"
[ -f "$CANON" ] || fail "published canonical missing at $CANON"
export HERMOD_RECIPE_SETPOINTS="$CANON"   # hermod bean reads the PUBLISHED canonical + its sibling manifest.json

echo "[GATE] db=$HERMOD_DB_URL ; opcua=$HERMOD_OPCUA_URL ; pub_canon=$CANON ; resequence=$RESEQUENCE_DIR"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: schemas (registry + app: command_audit/source_rows/target_rows) + fault_inject + reset"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U hermod -d hermod < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject, command_audit, reconciliation_provenance" >/dev/null
echo "[GATE] schemas ensured; state reset"

# ---------------------------------------------------------------------------
echo "[GATE] step 0.5: start the embedded Milo OPC-UA sim (shared by hermod write + resequence read)"
: > "$SIM_LOG"
./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
SIMUP=0
for i in $(seq 1 90); do grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null && { SIMUP=1; break; }; sleep 2; done
[ "$SIMUP" = "1" ] || { cat "$SIM_LOG" 2>/dev/null || true; fail "OPC-UA sim did not start"; }
echo "[GATE] OPC-UA sim listening on $HERMOD_OPCUA_URL"

# Out-of-band raw write to the sim (ungoverned field change / drift injection). Bypasses governance on
# purpose: it stands in for a local operator/rogue write that the twin then detects.
perturb() {  # $1=nodeId $2=value
  ./gradlew -q --no-daemon :opcua:perturb -Pnode="$1" -Pvalue="$2" >>"$PERTURB_LOG" 2>&1 || fail "perturb $1=$2 failed (see $PERTURB_LOG)"
}
RPM_NODE="ns=2;s=Recipe/Rpm"
TEMP_NODE="ns=2;s=Recipe/Temp"
# Drive the sim to the canonical baseline so a clean field shows NO drift before we perturb.
seed_canonical() { perturb "$RPM_NODE" 1500; perturb "$TEMP_NODE" 200; }

# ---------------------------------------------------------------------------
echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start worker (fault-inject armed + HERMOD_OPCUA_URL) then API"
: > "$WORKER_LOG"
HERMOD_WORKER_NAME=ipov-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
WUP=0
for i in $(seq 1 60); do grep -q "starting; polling" "$WORKER_LOG" 2>/dev/null && { WUP=1; break; }; sleep 2; done
[ "$WUP" = "1" ] || fail "worker did not reach 'starting; polling'"
echo "[GATE] worker up"

: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0
for i in $(seq 1 60); do curl -sf "$API/api/workflows" >/dev/null 2>&1 && { UP=1; break; }; sleep 2; done
[ "$UP" = "1" ] || fail "API did not answer GET /api/workflows"
echo "[GATE] API up on 18090"

# ---------------------------------------------------------------------------
echo "[GATE] step 3: save ot-recipe-stage-activate@1.0.0 + wait for the LIVE worker to poll-bind it"
SAVE_CODE=$(curl -s -o "$WORK/save_osa.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-stage-activate.json")
[ "$SAVE_CODE" = "200" ] || fail "save ot-recipe-stage-activate@1.0.0 expected 200, got $SAVE_CODE ($(cat "$WORK/save_osa.json"))"
SLEEP_S=$(( (HERMOD_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for poll-bind..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-stage-activate@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind the saga"
echo "[GATE] worker poll-bound ot-recipe-stage-activate@1.0.0"

# ---------------------------------------------------------------------------
echo "[GATE] step 4: start resequence control with recipe drift enabled (reads the shared sim vs hermod canonical)"
: > "$RESEQ_LOG"
( cd "$RESEQUENCE_DIR" && mvn -q -f control/pom.xml spring-boot:run \
    -Dspring-boot.run.jvmArguments="-Dpbs.drift.recipe.enabled=true -Dpbs.drift.recipe.canonical-path='$CANON' -Dpbs.drift.recipe.opcua.endpoint=opc.tcp://localhost:48400" \
  ) >"$RESEQ_LOG" 2>&1 &
RUP=0
for i in $(seq 1 120); do curl -sf "$DRIFT" >/dev/null 2>&1 && { RUP=1; break; }; sleep 2; done
[ "$RUP" = "1" ] || { tail -40 "$RESEQ_LOG"; fail "resequence /api/drift not up"; }
echo "[GATE] resequence control up on 8081 (recipe drift enabled)"

# Helpers
aud() { psql_q "SELECT count(*) FROM command_audit WHERE node='$1' AND outcome='$2'"; }
drift_has_rpm() { curl -fsS "$DRIFT" | grep -q '"key":"recipe.rpmSetpoint"'; }
wait_drift()    { for i in $(seq 1 "${1:-20}"); do drift_has_rpm && return 0; sleep 2; done; return 1; }
wait_no_drift() { for i in $(seq 1 "${1:-20}"); do drift_has_rpm || return 0; sleep 2; done; return 1; }
wait_node() {  # $1=runId $2=node $3=state $4=tries
  local run="$1" node="$2" state="$3" tries="${4:-30}"
  for i in $(seq 1 "$tries"); do curl -fsS "$API/api/runs/$run/nodes" | grep -q "\"$node\":\"$state\"" && return 0; sleep 2; done
  return 1
}

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1: happy — inject drift -> twin detects -> reconcile -> approve -> drift CLEARED ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit, source_rows" >/dev/null
seed_canonical                       # clean field at canonical (rpm 1500, temp 200) -> no drift
wait_no_drift 10 || { curl -fsS "$DRIFT"; fail "T1 field not clean at baseline (unexpected rpm drift)"; }
perturb "$RPM_NODE" 1200             # ungoverned drift
wait_drift 20 || { tail -30 "$RESEQ_LOG"; curl -fsS "$DRIFT"; fail "T1 twin did not report recipe.rpmSetpoint drift"; }
echo "[GATE] T1 twin reports RECONCILE_SETPOINT for recipe.rpmSetpoint"

RID="recon-happy-$(date +%s)"   # unique per invocation: Temporal retains prior workflowIds across worker restarts
RESP=$(curl -fsS -X POST "$API/api/reconciliations" -H 'Content-Type: application/json' \
  -d "{\"reconciliationId\":\"$RID\",\"nodes\":[\"recipe.rpmSetpoint\"],\"source\":\"resequence-drift\",\"proposalRef\":\"t1\"}") || fail "T1 POST /reconciliations failed"
echo "[GATE] T1 reconcile => $RESP"
echo "$RESP" | grep -q "\"runId\":\"$RID\"" || fail "T1 expected runId=$RID"
wait_node "$RID" activateRecipe AWAITING_APPROVAL || fail "T1 never reached the activate gate"   # parked human-gate state (SagaWorkflowImpl.kt:75, was RUNNING)
curl -fsS -X POST "$API/api/runs/$RID/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RID?wait=true" | grep -q '"completed":true' || fail "T1 did not complete after approve"
[ "$(aud opcua.write WRITTEN)"  -ge 1 ] || fail "T1 expected opcua.write WRITTEN >=1, got $(aud opcua.write WRITTEN)"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1 expected opcua.call CONFIRMED=1, got $(aud opcua.call CONFIRMED)"
wait_no_drift 20 || { curl -fsS "$DRIFT"; fail "T1 drift not cleared after reconcile"; }
echo "[GATE] T1 OK: loop closed — field back to canonical, twin drift cleared, audit WRITTEN+CONFIRMED"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T2: human veto — REJECT the gate -> field RESTORED -> drift PERSISTS ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit, source_rows" >/dev/null
seed_canonical
wait_no_drift 10 || { curl -fsS "$DRIFT"; fail "T2 field not clean at baseline"; }
perturb "$RPM_NODE" 1200
wait_drift 20 || fail "T2 twin did not report recipe drift"
RID2="recon-veto-$(date +%s)"   # unique per invocation (see RID)
curl -fsS -X POST "$API/api/reconciliations" -H 'Content-Type: application/json' \
  -d "{\"reconciliationId\":\"$RID2\",\"nodes\":[\"recipe.rpmSetpoint\"],\"source\":\"resequence-drift\"}" >/dev/null || fail "T2 reconcile failed"
wait_node "$RID2" activateRecipe AWAITING_APPROVAL || fail "T2 never reached the activate gate"   # parked human-gate state (SagaWorkflowImpl.kt:75, was RUNNING)
[ "$(aud opcua.write WRITTEN)" -ge 1 ] || fail "T2 stageRecipe should have written before the gate"
curl -fsS -X POST "$API/api/runs/$RID2/reject" >/dev/null || fail "T2 reject failed"
curl -fsS "$API/api/runs/$RID2?wait=true" | grep -q '"completed":false' || fail "T2 rejected run should compensate"
[ "$(aud opcua.write RESTORED)" -ge 1 ] || fail "T2 expected opcua.write RESTORED >=1, got $(aud opcua.write RESTORED)"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T2 activate must NOT have fired"
wait_drift 20 || { curl -fsS "$DRIFT"; fail "T2 drift should PERSIST after veto (field rolled back to 1200)"; }
echo "[GATE] T2 OK: human veto restored the field; twin drift persists (veto really blocked the change)"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T3: trust boundary — ungoverned node signal -> 400, no run, no write ====="
psql_q "TRUNCATE command_audit, source_rows" >/dev/null
CODE=$(curl -s -o "$WORK/t3.json" -w '%{http_code}' -X POST "$API/api/reconciliations" -H 'Content-Type: application/json' \
  -d '{"nodes":["recipe.secretValve"],"source":"resequence-drift"}')
[ "$CODE" = "400" ] || fail "T3 expected 400 for ungoverned node, got $CODE ($(cat "$WORK/t3.json"))"
[ "$(psql_q "SELECT count(*) FROM source_rows")" = "0" ] || fail "T3 must not seed source_rows"
[ "$(aud opcua.write WRITTEN)" = "0" ] || fail "T3 must not write"
echo "[GATE] T3 OK: ungoverned signal rejected 400, no run, no field write"

echo ""
echo "[GATE] PASS run-integration-pov-gate.sh"
exit 0
