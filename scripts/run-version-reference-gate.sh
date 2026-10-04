#!/usr/bin/env bash
# VERSION-REFERENCE GATE v3 (objective cross-repo proof): 청구서③ — data-lineage <-> definition-lineage
# meet at a CONTENT-ADDRESSED reference minted ONCE by ① (sparkplug-governance-lab), independently
# verified by both consumers (no runtime git in koshei or the twin).
#
# ① publishes recipe-setpoints.yaml → registry/recipe/line1/1.0.0/{recipe-setpoints.yaml, manifest.json}
#    (manifest: defRef=git commit SHA [provenance] + contentSha256=sha256 of the committed blob bytes).
# koshei reads the published canonical, COMPUTES sha256(bytes)==manifest.contentSha256, stamps a
#    self-attested reconciliation_provenance row (fail-closed 409 tampered/unresolvable = UNIT tests).
# the twin COMPUTES the same hash from the bytes it reads and verifies (UNVERIFIED on mismatch).
#
# Asserts:
#   V1 round-trip (self-attested): reconciliation_provenance.content_sha256 == manifest.contentSha256 ;
#                 def_ref == manifest.defRef ; applied command_audit value == published canonical desired.
#   V2 lineage (independent): the twin's /api/drift finding is provenanceVerified with the SAME
#                 contentSha256 (twin-computed) + defRef → both independently derived & agree.
#   V-verify (independent SHA<->content binding, off runtime path): sha256(git show <defRef>) == manifest.
#   (V4 tampered->409 / V5 unresolvable->409 are koshei @WebMvcTest unit tests; the twin reject is
#    SetpointDefRefTest — verification is against bootDigest fixed at boot, so not live per-request.)
#
# Run from the koshei repo root with the stack up + both sibling checkouts:
#   docker compose up -d
#   SPARKPLUG_LAB_DIR=".../sparkplug-governance-lab" RESEQUENCE_DIR=".../resequence-twin-lab" \
#     bash scripts/run-version-reference-gate.sh
#   # expect: [GATE] PASS ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_DIR_SH="${KOSHEI_PLUGIN_DIR:-$(pwd)/registry-store}"
export KOSHEI_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export KOSHEI_DB_URL="${KOSHEI_DB_URL:-jdbc:postgresql://localhost:15432/koshei}"
export KOSHEI_DB_USER="${KOSHEI_DB_USER:-koshei}"
export KOSHEI_DB_PASS="${KOSHEI_DB_PASS:-koshei}"
export KOSHEI_WF_POLL_MS="${KOSHEI_WF_POLL_MS:-3000}"
export KOSHEI_FAULT_INJECT=1
export KOSHEI_OPCUA_URL="${KOSHEI_OPCUA_URL:-opc.tcp://localhost:48400}"

FIX="scripts/fixtures/compose"
API="http://localhost:18090"
DRIFT="http://localhost:8081/api/drift"
WORK="build/version-reference-gate"
mkdir -p "$WORK"
WORKER_LOG="$WORK/worker.log"
API_LOG="$WORK/api.log"
SIM_LOG="$WORK/sim.log"
RESEQ_LOG="$WORK/resequence.log"
PERTURB_LOG="$WORK/perturb.log"
PUBLISH_LOG="$WORK/publish.log"
: > "$PERTURB_LOG"

psql_q() { docker compose exec -T postgres psql -U koshei -d koshei -tAc "$1" | tr -d '[:space:]'; }

kill_worker_jvms() { { jps -l 2>/dev/null | grep "koshei.app.WorkerKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_api_jvms()    { { jps -l 2>/dev/null | grep "koshei.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_sim_jvms()    { { jps -l 2>/dev/null | grep "koshei.opcua.SimMainKt" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
kill_reseq_jvms()  { { jps -l 2>/dev/null | grep "ResequenceTwinControlApplication" || true; } | awk '{print $1}' | while read -r p; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done; }
cleanup() { kill_api_jvms || true; kill_worker_jvms || true; kill_sim_jvms || true; kill_reseq_jvms || true; }
trap cleanup EXIT

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- publish log tail ---"; tail -20 "$PUBLISH_LOG" 2>/dev/null || true
  echo "--- worker log tail ---"; tail -40 "$WORKER_LOG" 2>/dev/null || true
  echo "--- api log tail ---";    tail -40 "$API_LOG"    2>/dev/null || true
  echo "--- sim log tail ---";    tail -20 "$SIM_LOG"    2>/dev/null || true
  echo "--- resequence log tail ---"; tail -40 "$RESEQ_LOG" 2>/dev/null || true
  exit 1
}

# Cross-repo dependencies: the twin checkout + the ① lab checkout (needs the RecipePublish CLI).
RESEQUENCE_DIR="${RESEQUENCE_DIR:-}"
[ -n "$RESEQUENCE_DIR" ] || fail "RESEQUENCE_DIR not set — point it at the resequence-twin-lab checkout (control/pom.xml). No silent skip."
[ -f "$RESEQUENCE_DIR/control/pom.xml" ] || fail "no control/pom.xml under RESEQUENCE_DIR='$RESEQUENCE_DIR'"
SPARKPLUG_LAB_DIR="${SPARKPLUG_LAB_DIR:-}"
[ -n "$SPARKPLUG_LAB_DIR" ] || fail "SPARKPLUG_LAB_DIR not set — point it at the sparkplug-governance-lab checkout (RecipePublish CLI). No silent skip."
[ -f "$SPARKPLUG_LAB_DIR/pom.xml" ] || fail "no pom.xml under SPARKPLUG_LAB_DIR='$SPARKPLUG_LAB_DIR'"

# ---------------------------------------------------------------------------
echo "[GATE] step -1: ① publish recipe-setpoints.yaml (the single mint; the only git in the feature)"
# ①'s registry/ is a TRACKED dir — publish to a THROWAWAY dir so gate runs never dirty the lab tree.
# mvn exec:java runs the WINDOWS JVM → every path in -Dexec.args MUST be cygpath -m'd (a bare MSYS /c/…
# becomes drive-relative C:\c\… in Path.of).
REG="$SPARKPLUG_LAB_DIR/build/gate-registry"
rm -rf "$REG"
REG_WIN="$(cygpath -m "$REG" 2>/dev/null || echo "$REG")"
LAB_WIN="$(cygpath -m "$SPARKPLUG_LAB_DIR" 2>/dev/null || echo "$SPARKPLUG_LAB_DIR")"
: > "$PUBLISH_LOG"
( cd "$SPARKPLUG_LAB_DIR" && mvn -q -DskipTests package && \
  mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.schema.RecipePublish \
    -Dexec.args="$REG_WIN $LAB_WIN model/recipe-setpoints.yaml line1 1.0.0" ) >"$PUBLISH_LOG" 2>&1 \
  || { cat "$PUBLISH_LOG"; fail "① publish failed"; }
PUB_DIR="$REG/recipe/line1/1.0.0"
PUB_CANON_RAW="$PUB_DIR/recipe-setpoints.yaml"
PUB_CANON="$(cygpath -m "$PUB_CANON_RAW" 2>/dev/null || echo "$PUB_CANON_RAW")"
[ -f "$PUB_CANON" ] || fail "published canonical missing at $PUB_CANON (see $PUBLISH_LOG)"
DEFREF="$(grep -oE '"defRef"[^,]*' "$PUB_DIR/manifest.json" | grep -oE '[0-9a-f]{40}' | head -1)"
MANIFEST_SHA="$(grep -oE '"contentSha256"[^,]*' "$PUB_DIR/manifest.json" | grep -oE '[0-9a-f]{64}' | head -1)"
[ -n "$DEFREF" ] || fail "no defRef in $PUB_DIR/manifest.json"
[ -n "$MANIFEST_SHA" ] || fail "no contentSha256 in $PUB_DIR/manifest.json"

# Both consumers read the PUBLISHED canonical + its sibling manifest.json (no working-tree file, no git).
export KOSHEI_RECIPE_SETPOINTS="$PUB_CANON"

echo "[GATE] published: def_ref=$DEFREF contentSha256=$MANIFEST_SHA"
echo "[GATE] db=$KOSHEI_DB_URL ; opcua=$KOSHEI_OPCUA_URL ; pub_canon=$PUB_CANON ; resequence=$RESEQUENCE_DIR"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: schemas (registry + app: command_audit/reconciliation_provenance[+content_sha256]) + reset"
docker compose exec -T postgres psql -U koshei -d koshei < registry/src/main/resources/registry-schema.sql >/dev/null
docker compose exec -T postgres psql -U koshei -d koshei < app/src/main/resources/schema.sql >/dev/null   # DROP+CREATE reconciliation_provenance (v3 col)
psql_q "DROP TABLE IF EXISTS fault_inject; CREATE TABLE fault_inject (block_id text NOT NULL, phase text NOT NULL DEFAULT 'forward', PRIMARY KEY (block_id, phase))" >/dev/null
psql_q "TRUNCATE target_rows, source_rows, workflow_def, run_index, fault_inject, command_audit, reconciliation_provenance" >/dev/null
echo "[GATE] schemas ensured; state reset"

# ---------------------------------------------------------------------------
echo "[GATE] step 0.5: start the embedded Milo OPC-UA sim (shared by koshei write + resequence read)"
: > "$SIM_LOG"
./gradlew -q --no-daemon :opcua:runSim >"$SIM_LOG" 2>&1 &
SIMUP=0
for i in $(seq 1 90); do grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null && { SIMUP=1; break; }; sleep 2; done
[ "$SIMUP" = "1" ] || { cat "$SIM_LOG" 2>/dev/null || true; fail "OPC-UA sim did not start"; }
echo "[GATE] OPC-UA sim listening on $KOSHEI_OPCUA_URL"

perturb() {  # $1=nodeId $2=value  (out-of-band raw write = ungoverned drift injection)
  ./gradlew -q --no-daemon :opcua:perturb -Pnode="$1" -Pvalue="$2" >>"$PERTURB_LOG" 2>&1 || fail "perturb $1=$2 failed (see $PERTURB_LOG)"
}
RPM_NODE="ns=2;s=Recipe/Rpm"
TEMP_NODE="ns=2;s=Recipe/Temp"
seed_canonical() { perturb "$RPM_NODE" 1500; perturb "$TEMP_NODE" 200; }

# ---------------------------------------------------------------------------
echo "[GATE] step 1: build worker + API"
./gradlew -q --no-daemon :app:build -x test :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: start worker then API (KOSHEI_RECIPE_SETPOINTS → published canonical)"
: > "$WORKER_LOG"
KOSHEI_WORKER_NAME=vref-w ./gradlew -q --no-daemon :app:run >"$WORKER_LOG" 2>&1 &
WUP=0
for i in $(seq 1 60); do grep -q "starting; polling" "$WORKER_LOG" 2>/dev/null && { WUP=1; break; }; sleep 2; done
[ "$WUP" = "1" ] || fail "worker did not reach 'starting; polling'"
echo "[GATE] worker up"

: > "$API_LOG"
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
UP=0
for i in $(seq 1 60); do curl -sf "$API/api/workflows" >/dev/null 2>&1 && { UP=1; break; }; sleep 2; done
[ "$UP" = "1" ] || { tail -30 "$API_LOG"; fail "API did not answer GET /api/workflows"; }
echo "[GATE] API up on 18090"

# ---------------------------------------------------------------------------
echo "[GATE] step 3: save ot-recipe-stage-activate@1.0.0 + wait for the LIVE worker to poll-bind it"
SAVE_CODE=$(curl -s -o "$WORK/save_osa.json" -w '%{http_code}' -X POST "$API/api/workflows?version=1.0.0" -H 'Content-Type: application/json' --data-binary "@$FIX/ot-recipe-stage-activate.json")
[ "$SAVE_CODE" = "200" ] || fail "save ot-recipe-stage-activate@1.0.0 expected 200, got $SAVE_CODE ($(cat "$WORK/save_osa.json"))"
SLEEP_S=$(( (KOSHEI_WF_POLL_MS / 1000) + 6 ))
echo "[GATE] sleeping ${SLEEP_S}s for poll-bind..."
sleep "$SLEEP_S"
grep -q "\[worker\] bound workflow ot-recipe-stage-activate@1.0.0" "$WORKER_LOG" || fail "worker did not poll-bind the saga"
echo "[GATE] worker poll-bound ot-recipe-stage-activate@1.0.0"

# ---------------------------------------------------------------------------
echo "[GATE] step 4: start resequence control with recipe drift enabled (reads the PUBLISHED canonical + manifest)"
: > "$RESEQ_LOG"
( cd "$RESEQUENCE_DIR" && mvn -q -f control/pom.xml spring-boot:run \
    -Dspring-boot.run.jvmArguments="-Dpbs.drift.recipe.enabled=true -Dpbs.drift.recipe.canonical-path='$PUB_CANON' -Dpbs.drift.recipe.opcua.endpoint=opc.tcp://localhost:48400" \
  ) >"$RESEQ_LOG" 2>&1 &
RUP=0
for i in $(seq 1 120); do curl -sf "$DRIFT" >/dev/null 2>&1 && { RUP=1; break; }; sleep 2; done
[ "$RUP" = "1" ] || { tail -40 "$RESEQ_LOG"; fail "resequence /api/drift not up"; }
echo "[GATE] resequence control up on 8081 (recipe drift enabled)"

# Helpers
aud() { psql_q "SELECT count(*) FROM command_audit WHERE node='$1' AND outcome='$2'"; }
prov()    { psql_q "SELECT def_ref FROM reconciliation_provenance WHERE run_id='$1' ORDER BY at_millis DESC LIMIT 1"; }
provsha() { psql_q "SELECT content_sha256 FROM reconciliation_provenance WHERE run_id='$1' ORDER BY at_millis DESC LIMIT 1"; }
# applied value lives on the WRITTEN row (logical_node=recipe.rpmSetpoint); the CONFIRMED row is the activate
# step (logical_node='activate', value=null), so the logical_node filter isolates the numeric WRITTEN row.
applied()   { psql_q "SELECT value FROM command_audit WHERE run_id='$1' AND logical_node='recipe.rpmSetpoint' AND outcome IN ('WRITTEN','CONFIRMED') ORDER BY at_millis DESC LIMIT 1"; }
# published canonical is flow-style, one line per setpoint: `recipe.rpmSetpoint: { ..., desired: 1500, ... }`
recovered_file() { grep 'rpmSetpoint' "$1" | grep -oE 'desired:[[:space:]]*[0-9.]+' | grep -oE '[0-9.]+' | head -1; }
num_eq()    { awk -v a="$1" -v b="$2" 'BEGIN{d=a-b; if(d<0)d=-d; exit !(d<=0.001)}'; }
drift_has_rpm() { curl -fsS "$DRIFT" | grep -q '"key":"recipe.rpmSetpoint"'; }
wait_drift()    { for i in $(seq 1 "${1:-20}"); do drift_has_rpm && return 0; sleep 2; done; return 1; }
wait_no_drift() { for i in $(seq 1 "${1:-20}"); do drift_has_rpm || return 0; sleep 2; done; return 1; }
wait_node() {  # $1=runId $2=node $3=state $4=tries
  local run="$1" node="$2" state="$3" tries="${4:-30}"
  for i in $(seq 1 "$tries"); do curl -fsS "$API/api/runs/$run/nodes" | grep -q "\"$node\":\"$state\"" && return 0; sleep 2; done
  return 1
}

# ---------------------------------------------------------------------------
echo "[GATE] ===== V-verify: independent git-SHA <-> content binding (off runtime path) ====="
VSHA="$(git -C "$LAB_WIN" show "$DEFREF:model/recipe-setpoints.yaml" | sha256sum | cut -d' ' -f1)"   # git -C needs the cygpath -m Windows path, not MSYS /c/…
[ "$VSHA" = "$MANIFEST_SHA" ] || fail "V-verify: sha256(git show $DEFREF) = $VSHA != manifest $MANIFEST_SHA"
echo "[GATE] V-verify OK: sha256(git show $DEFREF) == manifest.contentSha256"

# ---------------------------------------------------------------------------
echo "[GATE] ===== V1+V2: self-attested round-trip + independent twin lineage on a happy reconcile ====="
psql_q "TRUNCATE fault_inject, target_rows, command_audit, source_rows, reconciliation_provenance" >/dev/null
seed_canonical
wait_no_drift 10 || { curl -fsS "$DRIFT"; fail "V1 field not clean at baseline (unexpected rpm drift)"; }
perturb "$RPM_NODE" 1200
wait_drift 20 || { tail -30 "$RESEQ_LOG"; curl -fsS "$DRIFT"; fail "V1 twin did not report recipe.rpmSetpoint drift"; }
echo "[GATE] V1 twin reports RECONCILE_SETPOINT for recipe.rpmSetpoint"

# V2: the twin INDEPENDENTLY computed the content hash and verified it → provenanceVerified with the same refs
DRIFT_JSON="$(curl -fsS "$DRIFT")"
echo "$DRIFT_JSON" | grep -q '"provenanceVerified":true' || { echo "$DRIFT_JSON"; fail "V2 twin finding not provenanceVerified"; }
echo "$DRIFT_JSON" | grep -q "\"contentSha256\":\"$MANIFEST_SHA\"" || { echo "$DRIFT_JSON"; fail "V2 twin contentSha256 != manifest $MANIFEST_SHA"; }
echo "$DRIFT_JSON" | grep -q "\"defRef\":\"$DEFREF\"" || { echo "$DRIFT_JSON"; fail "V2 twin defRef != $DEFREF"; }
echo "[GATE] V2 OK: twin independently verified — provenanceVerified, contentSha256=$MANIFEST_SHA, defRef=$DEFREF"

RID="vref-happy-$(date +%s)"   # unique per invocation: Temporal retains prior workflowIds across worker restarts
RESP=$(curl -fsS -X POST "$API/api/reconciliations" -H 'Content-Type: application/json' \
  -d "{\"reconciliationId\":\"$RID\",\"nodes\":[\"recipe.rpmSetpoint\"],\"source\":\"resequence-drift\",\"proposalRef\":\"v1\"}") || fail "V1 POST /reconciliations failed"
echo "[GATE] V1 reconcile => $RESP"
echo "$RESP" | grep -q "\"runId\":\"$RID\"" || fail "V1 expected runId=$RID"
wait_node "$RID" activateRecipe AWAITING_APPROVAL 75 || fail "V1 never reached the activate gate"   # parked human-gate state (SagaWorkflowImpl.kt:75)
curl -fsS -X POST "$API/api/runs/$RID/approve" >/dev/null || fail "V1 approve failed"
curl -fsS "$API/api/runs/$RID?wait=true" | grep -q '"completed":true' || fail "V1 did not complete after approve"
[ "$(aud opcua.write WRITTEN)"  -ge 1 ] || fail "V1 expected opcua.write WRITTEN >=1"
[ "$(aud opcua.call CONFIRMED)" = "1" ] || fail "V1 expected opcua.call CONFIRMED=1"

# V1a: provenance row is SELF-ATTESTED — koshei recorded the hash it COMPUTED (== manifest) + the def_ref
PROW="$(prov "$RID")";    [ "$PROW" = "$DEFREF" ]      || fail "V1 provenance def_ref '$PROW' != '$DEFREF'"
PSHA="$(provsha "$RID")"; [ "$PSHA" = "$MANIFEST_SHA" ] || fail "V1 provenance content_sha256 '$PSHA' != manifest '$MANIFEST_SHA'"
# V1b: the applied command_audit value == the PUBLISHED canonical's desired
REC="$(recovered_file "$PUB_CANON")"; APP="$(applied "$RID")"
[ -n "$REC" ] || fail "V1 could not recover desired from published canonical $PUB_CANON"
[ -n "$APP" ] || fail "V1 no applied value in command_audit for run $RID"
num_eq "$REC" "$APP" || fail "V1 round-trip mismatch: published desired=$REC vs applied=$APP"
echo "[GATE] V1 OK: self-attested content_sha256=$PSHA == manifest ; applied $APP == published desired $REC ; def_ref stamped"

echo ""
echo "[GATE] PASS run-version-reference-gate.sh"
echo "[GATE] (fail-closed 409 tampered/unresolvable + twin reject are unit tests: ReconciliationControllerTest, SetpointDefRefTest)"
exit 0
