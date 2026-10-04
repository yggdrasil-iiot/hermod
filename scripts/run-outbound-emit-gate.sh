#!/usr/bin/env bash
# OUTBOUND GOVERNANCE-EVENT GATE (objective cross-repo proof): koshei publishes its governance lifecycle
# as a spec-compliant Sparkplug B Edge Node onto the UNS, and the resequence twin consumes it as a genuine
# Sparkplug host, annotating a live drift finding from the received NDATA.
#
#   koshei :authoring-api (RunReconciler emit hook) --NBIRTH/NDATA--> HiveMQ CE (broker.yml)
#       --> resequence-twin-lab control (KosheiGovernanceSubscriber) --> drift-finding reconciliation.state
#
# The emitter is ADDITIVE and FAIL-OPEN (spec 2026-07-01): the OPC-UA saga (worker, DIRECT R1 apply) is
# byte-identical to run-opcua-gate.sh; only :authoring-api gains the KOSHEI_EMIT_MODE-gated emit surface.
#
# This environment has NO mosquitto CLI, so the gate asserts via OBSERVABLE STATE (twin /api/drift + the
# koshei emitted_event ledger + command_audit), and uses an in-repo Paho+Tahu probe (:opcua:emitProbe) for
# the raw Sparkplug wire assertions (NBIRTH order + rebirth) instead of an MQTT sniffer.
#
# Asserts:
#   T1 happy  : inject drift -> twin DETECTED -> reconcile -> approve -> command_audit CONFIRMED +
#               emitted_event CONFIRMED (koshei emitted) + wait_no_drift (field reconciled -> finding gone).
#               NOTE: RECONCILING is NOT asserted — the reconcile saga's human gate reports node-state
#               "AWAITING_APPROVAL" (SagaWorkflowImpl.kt:75, B1; the emitter's inFlight counts it), not "PARKED", so the emitter's parked() guard never
#               fires RECONCILING for this flow (see the CONTROLLER note at T1). The faithful twin-CONSUMES
#               proof is T2.
#   T2 veto   : fresh drift -> reconcile -> reject -> opcua.write RESTORED + emitted_event RECON_FAILED +
#               twin finding reconciliation.state=="RECONCILING_FAILED" with the koshei runId (the twin
#               HOST received + applied the RECON_FAILED NDATA) + drift PERSISTS.
#   T3 host   : from the :opcua:emitProbe capture, an NBIRTH line precedes any NDATA line (Sparkplug host
#               rule); a Node Control/Rebirth NCMD (emitProbe rebirth) yields a fresh NBIRTH line.
#   T4 failopen: stop the broker mid-run -> the saga STILL reaches terminal (command_audit CONFIRMED, run
#               terminal) with emit dropped; restart broker -> a fresh veto run's twin annotation resumes.
#   T5 idem   : exactly one emitted_event row per (run,event_type); retry path (POST /runs/{id}/retry).
#
# Run from the koshei repo root with the main stack up (postgres+temporal ALREADY RUNNING) + a twin checkout:
#   docker compose up -d          # (postgres 15432 + temporal 7233 — reused, NOT restarted here)
#   TWIN_DIR="/path/to/resequence-twin-lab" bash scripts/run-outbound-emit-gate.sh
#   # expect: [GATE] PASS run-outbound-emit-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."
# Shared gate helpers (native_path, psql_q, kill_jvms_by, wait_for_log, wait_http_ok, wait_tcp).
source "$(dirname "$0")/lib/gate-common.sh"

# ---- WEDGE GUARD (root cause of a prior single-run hang) --------------------------------------------
# A transient multi-JVM stall (heavy GC/CPU during saga completion) once left a NO-TIMEOUT `curl` blocked
# on a TCP read forever, freezing the whole >10-min single run at T1 with no PASS/FAIL (every endpoint was
# healthy again by the time we looked). Bound EVERY blocking call so a transient can never wedge the run:
# override `curl` (covers all call sites here AND inside gate-common's wait_http_ok) and `psql_q` with hard
# timeouts. Retry-loop callers (wait_node/wait_emit/wait_recon_state/drift_has_rpm) self-heal on a timed-out
# poll; one-shot callers (approve/reject/?wait=true/reconcile) then fail honestly via `|| fail` instead of
# hanging. --max-time 60 sits far above a real run's few-seconds completion, so healthy calls are unaffected.
curl() { command curl --connect-timeout 5 --max-time 60 "$@"; }
psql_q() { timeout 40 docker compose exec -T postgres psql -U koshei -d koshei -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }

PLUGIN_DIR_SH="${KOSHEI_PLUGIN_DIR:-$(pwd)/registry-store}"
export KOSHEI_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export KOSHEI_DB_URL="${KOSHEI_DB_URL:-jdbc:postgresql://localhost:15432/koshei}"
export KOSHEI_DB_USER="${KOSHEI_DB_USER:-koshei}"
export KOSHEI_DB_PASS="${KOSHEI_DB_PASS:-koshei}"
export KOSHEI_WF_POLL_MS="${KOSHEI_WF_POLL_MS:-3000}"
export KOSHEI_FAULT_INJECT=1
export KOSHEI_OPCUA_URL="${KOSHEI_OPCUA_URL:-opc.tcp://localhost:48400}"
# ---- Outbound-emit wire config. Read by EmitConfig via System.getenv INSIDE the :authoring-api JVM.
# NOTE (env forwarding, grounded): the emitter lives in :authoring-api, NOT the worker. `:app`'s
# kosheiEnvKeys list intentionally does NOT carry KOSHEI_EMIT_* (the worker does not emit). The
# :authoring-api `run` task is the application-plugin default JavaExec (no env filter), so under
# --no-daemon it inherits this exported shell env and EmitConfig.on()/System.getenv see KOSHEI_EMIT_*.
export KOSHEI_EMIT_MODE="${KOSHEI_EMIT_MODE:-1}"
export KOSHEI_EMIT_MQTT_URL="${KOSHEI_EMIT_MQTT_URL:-tcp://localhost:1883}"
export KOSHEI_EMIT_GROUP="${KOSHEI_EMIT_GROUP:-Koshei}"
export KOSHEI_EMIT_EDGE="${KOSHEI_EMIT_EDGE:-Governance}"
MQTT_TCP="tcp://localhost:1883"
SFX="$(date +%s)"   # unique per invocation — reconcile workflowIds never collide with a stuck run's leftovers

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
DRIFT="http://localhost:8081/api/drift"
WORK="build/outbound-emit-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"
SIM_LOG="$WORK/sim.log"
TWIN_LOG="$WORK/twin.log"
PERTURB_LOG="$WORK/perturb.log"
PROBE_LOG="$WORK/probe.log"
CAP="$WORK/emit-capture.log"       # :opcua:emitProbe capture output (one line per Sparkplug message)
: > "$PERTURB_LOG"; : > "$CAP"

BROKER_COMPOSE="$(native_path scripts/compose/broker.yml)"
PROBE_PID=""

# psql_q, kill_jvms_by, native_path, wait_* come from scripts/lib/gate-common.sh (sourced above).
cleanup() {
  kill_jvms_by "koshei.authoring.AuthoringApplicationKt" || true
  kill_jvms_by "koshei.app.WorkerKt" || true
  kill_jvms_by "koshei.opcua.SimMainKt" || true
  kill_jvms_by "koshei.opcua.emit.EmitProbeMain" || true
  kill_jvms_by "ResequenceTwinControlApplication" || true
  [ -n "$PROBE_PID" ] && { taskkill //F //T //PID "$PROBE_PID" >/dev/null 2>&1 || kill "$PROBE_PID" >/dev/null 2>&1 || true; }
  docker compose -f "$BROKER_COMPOSE" down >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
  echo "--- sim log tail ---";    tail -20 "$SIM_LOG"    2>/dev/null || true
  echo "--- twin log tail ---";   tail -40 "$TWIN_LOG"   2>/dev/null || true
  echo "--- probe log tail ---";  tail -10 "$PROBE_LOG"  2>/dev/null || true
  echo "--- capture tail ---";    tail -10 "$CAP"        2>/dev/null || true
  exit 1
}

# Cross-repo dependency: the twin checkout (the dir containing control/pom.xml). Fail-closed if unset/missing.
TWIN_DIR="${TWIN_DIR:-/path/to/resequence-twin-lab}"
[ -f "$TWIN_DIR/control/pom.xml" ] || fail "no control/pom.xml under TWIN_DIR='$TWIN_DIR' — point TWIN_DIR at the resequence-twin-lab checkout"
# Windows-mixed canonical path so the twin JVM resolves it (an MSYS /c/... path breaks a native JVM -D).
CANON="$(native_path "$(pwd)/model/recipe-setpoints.yaml")"
[ -f "$CANON" ] || fail "missing canonical $CANON"

echo "[GATE] db=$KOSHEI_DB_URL ; opcua=$KOSHEI_OPCUA_URL ; mqtt=$MQTT_TCP ; group/edge=$KOSHEI_EMIT_GROUP/$KOSHEI_EMIT_EDGE ; twin=$TWIN_DIR"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: schemas (registry incl. emitted_event + app command_audit/source_rows/target_rows) + fault_inject + reset"
docker compose exec -T postgres psql -U koshei -d koshei < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U koshei -d koshei < app/src/main/resources/schema.sql >/dev/null
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject, command_audit, emitted_event" >/dev/null
echo "[GATE] schemas ensured; state reset (postgres+temporal reused from the main compose — not restarted)"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: start the MQTT broker (broker.yml, hivemq-ce:latest) + wait for :1883"
docker compose -f "$BROKER_COMPOSE" up -d >/dev/null 2>&1 || fail "failed to start the broker (broker.yml)"
wait_tcp localhost 1883 60 || { docker compose -f "$BROKER_COMPOSE" logs --tail 40 2>/dev/null || true; fail "broker did not open :1883"; }
echo "[GATE] broker up on :1883"

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the embedded Milo OPC-UA sim (shared by koshei write + twin read)"
: > "$SIM_LOG"
./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
wait_for_log "$SIM_LOG" "OPC-UA sim listening" 90 || { cat "$SIM_LOG" 2>/dev/null || true; fail "OPC-UA sim did not start"; }
echo "[GATE] OPC-UA sim listening on $KOSHEI_OPCUA_URL"

perturb() {  # $1=nodeId $2=value — out-of-band raw write to the sim (ungoverned drift injection)
  ./gradlew -q --no-daemon :opcua:perturb -Pnode="$1" -Pvalue="$2" >>"$PERTURB_LOG" 2>&1 || fail "perturb $1=$2 failed (see $PERTURB_LOG)"
}
RPM_NODE="ns=2;s=Recipe/Rpm"
TEMP_NODE="ns=2;s=Recipe/Temp"
seed_canonical() { perturb "$RPM_NODE" 1500; perturb "$TEMP_NODE" 200; }   # canonical baseline -> no drift

# ---------------------------------------------------------------------------
echo "[GATE] step 3: build worker + API + probe (compile :opcua test classes so the background probe starts fast)"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test :opcua:testClasses >/dev/null

# Start the Paho+Tahu capture probe BEFORE the twin/API so it catches the (non-retained) startup NBIRTH.
# CONTROLLER: the probe must be CONNECTED before the API births. It is started well ahead of the twin+worker+API
# bring-up (tens of seconds), which is ample; if the startup-NBIRTH assertion below ever flakes, start the
# probe earlier or force a rebirth right after the API is up. (T2 succeeding independently proves the HOST saw
# an NBIRTH before applying an NDATA, since KosheiGovernanceSubscriber ignores NDATA until birthSeen.)
CAP_OUT="$(native_path "$(pwd)/$CAP")"
echo "[GATE] step 4: start the Sparkplug capture probe (:opcua:emitProbe capture) -> $CAP"
./gradlew -q --no-daemon :opcua:emitProbe -Pmode=capture -PmqttUrl="$MQTT_TCP" -Pgrp="$KOSHEI_EMIT_GROUP" -Pedg="$KOSHEI_EMIT_EDGE" -Pout="$CAP_OUT" -Psecs=1200 >"$PROBE_LOG" 2>&1 &
PROBE_PID=$!

# Twin BEFORE the koshei API (Sparkplug host rule + non-retained NBIRTH): the twin subscriber ignores NDATA
# until it has seen an NBIRTH. Bringing the twin fully up (MQTT-subscribed) before the API births guarantees
# the host catches the startup NBIRTH and can annotate findings.
echo "[GATE] step 5: start twin control (drift + koshei governance subscribe enabled)"
: > "$TWIN_LOG"
( cd "$TWIN_DIR" && mvn -q -f control/pom.xml spring-boot:run \
    -Dspring-boot.run.jvmArguments="-Dpbs.drift.recipe.enabled=true -Dpbs.drift.recipe.canonical-path='$CANON' -Dpbs.drift.recipe.opcua.endpoint=opc.tcp://localhost:48400 -Dkoshei.subscribe.enabled=true -Dkoshei.subscribe.mqtt-url=$MQTT_TCP -Dkoshei.subscribe.group=$KOSHEI_EMIT_GROUP -Dkoshei.subscribe.edge=$KOSHEI_EMIT_EDGE" \
  ) >"$TWIN_LOG" 2>&1 &
wait_http_ok "$DRIFT" 120 || { tail -40 "$TWIN_LOG" 2>/dev/null || true; fail "twin /api/drift not up"; }
echo "[GATE] twin up on 8081 (drift + koshei subscribe)"

echo "[GATE] step 6: start worker (DIRECT R1 apply — KOSHEI_APPLY_MODE unset) then API (emits NBIRTH)"
: > "$WORKER_LOG"
KOSHEI_WORKER_NAME=emit-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
wait_for_log "$WORKER_LOG" "starting; polling" 60 || fail "worker did not reach 'starting; polling'"
echo "[GATE] worker up"

: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
wait_http_ok "$API/api/workflows" 60 || fail "API did not answer GET /api/workflows"
echo "[GATE] API up on 18090 (KOSHEI_EMIT_MODE=$KOSHEI_EMIT_MODE)"

# The API's SparkplugEdgeSession births on connect. Wait for the probe to record it.
wait_for_log "$CAP" "^NBIRTH " 30 || { tail -10 "$CAP" 2>/dev/null || true; tail -10 "$PROBE_LOG" 2>/dev/null || true; fail "no startup NBIRTH captured — check the emit session connected + the probe was up first"; }
echo "[GATE] startup NBIRTH observed by the probe"

# ---------------------------------------------------------------------------
echo "[GATE] step 7: save ot-recipe-stage-activate@1.0.0 + wait for the LIVE worker to poll-bind it"
SAVE_CODE=$(curl -s -o "$WORK/save_osa.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-stage-activate.json")
[ "$SAVE_CODE" = "200" ] || fail "save ot-recipe-stage-activate@1.0.0 expected 200, got $SAVE_CODE ($(cat "$WORK/save_osa.json"))"
SLEEP_S=$(( (KOSHEI_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for poll-bind..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-stage-activate@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind the saga"
echo "[GATE] worker poll-bound ot-recipe-stage-activate@1.0.0"

# ---------------------------------------------------------------------------
# Helpers
aud() { psql_q "SELECT count(*) FROM command_audit WHERE node='$1' AND outcome='$2'"; }
emit_count() { psql_q "SELECT count(*) FROM emitted_event WHERE run_id='$1' AND event_type='$2'"; }
drift_has_rpm() { curl -fsS "$DRIFT" | grep -q '"key":"recipe.rpmSetpoint"'; }
wait_drift()    { for i in $(seq 1 "${1:-20}"); do drift_has_rpm && return 0; sleep 2; done; return 1; }
wait_no_drift() { for i in $(seq 1 "${1:-20}"); do drift_has_rpm || return 0; sleep 2; done; return 1; }
# Poll /api/drift until a finding shows a given reconciliation state (Jackson serializes the nullable
# SetpointDriftFinding.reconciliation as {"state":..,"runId":..,"atMillis":..}).
wait_recon_state() {  # $1=state $2=tries
  local st="$1" tries="${2:-25}"
  for i in $(seq 1 "$tries"); do curl -fsS "$DRIFT" | grep -q "\"state\":\"$st\"" && return 0; sleep 2; done
  return 1
}
wait_node() {  # $1=runId $2=node $3=state $4=tries  (also drives the read-path reconcile -> emit)
  local run="$1" node="$2" state="$3" tries="${4:-30}"
  for i in $(seq 1 "$tries"); do curl -fsS "$API/api/runs/$run/nodes" | grep -q "\"$node\":\"$state\"" && return 0; sleep 2; done
  return 1
}
wait_emit() {  # $1=runId $2=type $3=tries  (drive read-path reconcile + wait for the ledger row)
  local run="$1" type="$2" tries="${3:-15}"
  for i in $(seq 1 "$tries"); do curl -fsS "$API/api/runs/$run" >/dev/null 2>&1 || true; \
    [ "$(emit_count "$run" "$type")" -ge 1 ] && return 0; sleep 2; done
  return 1
}
reconcile() {  # $1=reconciliationId $2=proposalRef  (runId == reconciliationId)
  curl -fsS -X POST "$API/api/reconciliations" -H 'Content-Type: application/json' \
    -d "{\"reconciliationId\":\"$1\",\"nodes\":[\"recipe.rpmSetpoint\"],\"source\":\"outbound-emit-gate\",\"proposalRef\":\"$2\"}"
}

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1: happy — drift -> reconcile -> approve -> CONFIRMED emitted + twin drift CLEARED ====="
# CONTROLLER (#4, VERIFIED): the reconcile saga runs interactive=false and its human gate sets node-state
# "AWAITING_APPROVAL" (SagaWorkflowImpl.kt:75, B1), NOT "PARKED" ("PARKED" only for an interactive FAILED node, line 145).
# GovernanceEventEmitter.parked() matches =="PARKED", so RECONCILING is NEVER emitted for this flow. T1 does
# NOT assert RECONCILING (on the wire or the ledger). Loop proof = command_audit CONFIRMED + emitted_event
# CONFIRMED + drift cleared. If a RECONCILING event is wanted, the emitter (or the human-gate node-state
# signal) must change — reporting per your #4 request.
psql_q "TRUNCATE fault_inject, target_rows, command_audit, source_rows, emitted_event" >/dev/null
seed_canonical
wait_no_drift 10 || { curl -fsS "$DRIFT"; fail "T1 field not clean at baseline"; }
perturb "$RPM_NODE" 1200
wait_drift 20 || { tail -20 "$TWIN_LOG"; curl -fsS "$DRIFT"; fail "T1 twin did not report recipe.rpmSetpoint drift (DETECTED)"; }
echo "[GATE] T1 twin reports drift (DETECTED)"
RID1="emit-happy-$SFX"
RESP=$(reconcile "$RID1" "t1") || fail "T1 POST /reconciliations failed"
echo "[GATE] T1 reconcile => $RESP"
echo "$RESP" | grep -q "\"runId\":\"$RID1\"" || fail "T1 expected runId=$RID1"
wait_node "$RID1" activateRecipe RUNNING || fail "T1 never reached the activate gate"
curl -fsS -X POST "$API/api/runs/$RID1/approve" >/dev/null || fail "T1 approve failed"
curl -fsS "$API/api/runs/$RID1?wait=true" | grep -q '"completed":true' || fail "T1 did not complete after approve"
[ "$(aud opcua.write WRITTEN)"  -ge 1 ] || fail "T1 expected opcua.write WRITTEN>=1, got $(aud opcua.write WRITTEN)"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T1 expected opcua.call CONFIRMED=1, got $(aud opcua.call CONFIRMED)"
wait_emit "$RID1" CONFIRMED 15 || fail "T1 koshei did not emit a CONFIRMED governance event (emitted_event)"
wait_no_drift 20 || { curl -fsS "$DRIFT"; fail "T1 twin drift not cleared after approve"; }
echo "[GATE] T1 OK: audit CONFIRMED, CONFIRMED emitted, twin drift CLEARED (loop closed)"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T2: veto — reject -> opcua.write RESTORED + RECON_FAILED emitted + twin HOST annotates RECONCILING_FAILED ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit, source_rows, emitted_event" >/dev/null
seed_canonical
wait_no_drift 10 || { curl -fsS "$DRIFT"; fail "T2 field not clean at baseline"; }
perturb "$RPM_NODE" 1200
wait_drift 20 || fail "T2 twin did not report recipe drift"
RID2="emit-veto-$SFX"
reconcile "$RID2" "t2" >/dev/null || fail "T2 reconcile failed"
wait_node "$RID2" activateRecipe RUNNING || fail "T2 never reached the activate gate"
[ "$(aud opcua.write WRITTEN)" -ge 1 ] || fail "T2 stageRecipe should have written before the gate, got $(aud opcua.write WRITTEN)"
curl -fsS -X POST "$API/api/runs/$RID2/reject" >/dev/null || fail "T2 reject failed"
curl -fsS "$API/api/runs/$RID2?wait=true" | grep -q '"completed":false' || fail "T2 rejected run should compensate"
[ "$(aud opcua.write RESTORED)" -ge 1 ] || fail "T2 expected opcua.write RESTORED>=1, got $(aud opcua.write RESTORED)"
[ "$(aud opcua.call CONFIRMED)" = "0" ] || fail "T2 activate must NOT have fired"
wait_emit "$RID2" RECON_FAILED 15 || fail "T2 koshei did not emit a RECON_FAILED governance event (emitted_event)"
# Drift PERSISTS (compensation restored the field to 1200) -> the finding survives -> its annotation surfaces.
wait_recon_state RECONCILING_FAILED 25 || { curl -fsS "$DRIFT"; fail "T2 twin HOST did not annotate the finding RECONCILING_FAILED (RECON_FAILED NDATA not received/applied?)"; }
curl -fsS "$DRIFT" | grep -q "\"runId\":\"$RID2\"" || fail "T2 twin annotation missing provenance runId=$RID2"
wait_drift 10 || { curl -fsS "$DRIFT"; fail "T2 drift should PERSIST after veto"; }
echo "[GATE] T2 OK: veto restored the field; RECON_FAILED emitted AND applied by the twin host (RECONCILING_FAILED, runId=$RID2); drift persists"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T3: Sparkplug host-compliance — NBIRTH before any NDATA + rebirth NCMD re-births ====="
# Ordering from the probe capture (lines: "<KIND> <LastEventType|->"). T1/T2 have produced NDATA lines.
NB_LINE=$(grep -n '^NBIRTH ' "$CAP" | head -1 | cut -d: -f1)
ND_LINE=$(grep -n '^NDATA '  "$CAP" | head -1 | cut -d: -f1)
[ -n "$NB_LINE" ] || fail "T3 no NBIRTH ever captured by the probe"
[ -n "$ND_LINE" ] || fail "T3 no NDATA captured by the probe (expected from T1/T2) — probe misconfigured?"
[ "$NB_LINE" -lt "$ND_LINE" ] || fail "T3 NBIRTH (line $NB_LINE) did not precede the first NDATA (line $ND_LINE) — host rule violated"
echo "[GATE] T3 NBIRTH (line $NB_LINE) precedes first NDATA (line $ND_LINE)"
# Rebirth round-trip: publish Node Control/Rebirth=true via the in-repo probe; the edge session must re-birth.
NB_BEFORE=$(grep -c '^NBIRTH ' "$CAP")
./gradlew -q --no-daemon :opcua:emitProbe -Pmode=rebirth -PmqttUrl="$MQTT_TCP" -Pgrp="$KOSHEI_EMIT_GROUP" -Pedg="$KOSHEI_EMIT_EDGE" >>"$PROBE_LOG" 2>&1 || fail "T3 rebirth NCMD publish failed (see $PROBE_LOG)"
NB_AFTER="$NB_BEFORE"
for i in $(seq 1 20); do NB_AFTER=$(grep -c '^NBIRTH ' "$CAP"); [ "$NB_AFTER" -gt "$NB_BEFORE" ] && break; sleep 2; done
[ "$NB_AFTER" -gt "$NB_BEFORE" ] || fail "T3 no fresh NBIRTH after the rebirth NCMD (before=$NB_BEFORE after=$NB_AFTER)"
echo "[GATE] T3 OK: NBIRTH-before-NDATA host rule holds; rebirth NCMD re-issued a fresh NBIRTH"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T4: fail-open — broker DOWN mid-run -> saga still terminal; broker UP -> emit resumes ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit, source_rows, emitted_event" >/dev/null
seed_canonical
echo "[GATE] T4 stopping the broker (emit must fail-open, saga unaffected)"
docker compose -f "$BROKER_COMPOSE" stop >/dev/null 2>&1 || fail "T4 could not stop the broker"
sleep 3
RID4="emit-failopen-$SFX"
reconcile "$RID4" "t4" >/dev/null || fail "T4 reconcile failed to start (broker down must not block the API)"
wait_node "$RID4" activateRecipe RUNNING 45 || fail "T4 never reached the activate gate with the broker down"
curl -fsS -X POST "$API/api/runs/$RID4/approve" >/dev/null || fail "T4 approve failed"
curl -fsS "$API/api/runs/$RID4?wait=true" | grep -q '"completed":true' || fail "T4 saga did NOT complete with the broker down — not fail-open"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "T4 expected opcua.call CONFIRMED=1 (saga unaffected), got $(aud opcua.call CONFIRMED)"
echo "[GATE] T4 saga reached terminal (CONFIRMED) with the broker DOWN — emit dropped, no saga impact"

echo "[GATE] T4 restarting the broker; expect a fresh veto run's twin annotation to resume"
docker compose -f "$BROKER_COMPOSE" start >/dev/null 2>&1 || fail "T4 could not restart the broker"
wait_tcp localhost 1883 60 || fail "T4 broker did not reopen :1883"
sleep 8   # allow Paho auto-reconnect on the koshei edge AND the twin subscriber (both isAutomaticReconnect)
# CONTROLLER: T4-resume requires BOTH Paho clients (koshei PahoEdgeNodeTransport + twin KosheiSubscribeConfig,
# both isAutomaticReconnect=true, cleanSession=true) to re-subscribe after the broker restart. If the twin
# subscriber does not re-consume post-restart, restart the twin here (or treat this sub-assertion as advisory).
psql_q "TRUNCATE fault_inject, target_rows, command_audit, source_rows, emitted_event" >/dev/null
seed_canonical; perturb "$RPM_NODE" 1200; wait_drift 20 || fail "T4 resume: twin did not report drift"
RID4B="emit-resume-$SFX"
reconcile "$RID4B" "t4b" >/dev/null || fail "T4 resume reconcile failed"
wait_node "$RID4B" activateRecipe RUNNING || fail "T4 resume never reached the gate"
curl -fsS -X POST "$API/api/runs/$RID4B/reject" >/dev/null || fail "T4 resume reject failed"
curl -fsS "$API/api/runs/$RID4B?wait=true" | grep -q '"completed":false' || fail "T4 resume veto run should compensate"
wait_emit "$RID4B" RECON_FAILED 15 || fail "T4 resume: koshei did not re-emit after broker restart"
wait_recon_state RECONCILING_FAILED 30 || { curl -fsS "$DRIFT"; fail "T4 resume: twin annotation did not resume after broker restart (see the CONTROLLER note)"; }
echo "[GATE] T4 OK: fail-open under broker outage; emit + twin annotation resume after restart"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T5: idempotency (+ retry path) ====="
# Idempotency: T4B's veto run has exactly one RECON_FAILED row even after extra reconcile passes (write-once).
for i in $(seq 1 5); do curl -fsS "$API/api/runs/$RID4B" >/dev/null 2>&1 || true; sleep 1; done
[ "$(emit_count "$RID4B" RECON_FAILED)" = "1" ] || fail "T5 expected exactly one RECON_FAILED emitted_event for $RID4B, got $(emit_count "$RID4B" RECON_FAILED)"
DUP=$(psql_q "SELECT COALESCE(MAX(c),0) FROM (SELECT count(*) c FROM emitted_event WHERE run_id='$RID4B' GROUP BY event_type) t")
[ "$DUP" = "1" ] || fail "T5 emitted_event has a duplicated (run,type) for $RID4B (max per type=$DUP, expected 1)"
echo "[GATE] T5 idempotency OK: exactly one emitted_event per (run,type)"

# Retry path: POST /api/runs/{id}/retry (RunController.kt) calls signalRetry THEN clearArchive (which also
# deletes emitted_event, so a re-run re-emits). CONTROLLER (#5, VERIFIED): for a TERMINAL rejected Temporal
# run, TemporalEnginePort.signalRetry signals a CLOSED workflow stub, which throws BEFORE clearArchive runs
# — so a live retry-re-emit on a veto run is not cleanly exercisable via this saga (it is non-interactive, so
# no PARKED node to retry). The clearArchive->emitted_event-clear behavior is unit-covered by RunStoreTest.
# The call below is therefore BEST-EFFORT and NON-FATAL: if the retry endpoint accepts it and clears the
# ledger, we assert that; otherwise we log and continue (idempotency above is the hard T5 assertion).
RC=$(curl -s -o "$WORK/t5_retry.json" -w '%{http_code}' -X POST "$API/api/runs/$RID4B/retry" -H 'Content-Type: application/json' -d '{}')
if [ "$RC" = "200" ]; then
  sleep 3
  AFTER=$(emit_count "$RID4B" RECON_FAILED)
  if [ "$AFTER" = "0" ]; then
    echo "[GATE] T5 retry OK: POST /runs/$RID4B/retry cleared emitted_event (a re-run would re-emit)"
  else
    echo "[GATE] T5 retry: endpoint returned 200 but emitted_event not cleared (AFTER=$AFTER) — see the CONTROLLER note"
  fi
else
  echo "[GATE] T5 retry: POST /runs/$RID4B/retry returned $RC (expected for a terminal Temporal run; clearArchive path is unit-covered) — see the CONTROLLER note"
fi
echo "[GATE] T5 OK: exactly-once ledger verified (retry path exercised best-effort per the CONTROLLER note)"

echo ""
echo "[GATE] PASS run-outbound-emit-gate.sh"
exit 0
