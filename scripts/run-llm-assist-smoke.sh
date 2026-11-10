#!/usr/bin/env bash
# run-llm-assist-smoke.sh — KEYED golden-set smoke: real Anthropic /api/fsm/assist -> structurally valid
# FSM draft -> accepted by the REAL conformance gate (fail-closed authority, unchanged).
#
# NOT part of the automated gate suite (non-deterministic: hits a live LLM). Skips cleanly (exit 0) when
# ANTHROPIC_API_KEY is unset — that is the ONLY path verifiable in an environment without a key.
#
# What this proves when a key IS present: the natural-language -> FsmSpec format contract holds end to
# end — :authoring-api boots with HERMOD_LLM_ASSIST=anthropic, each golden prompt in
# scripts/fixtures/llm-assist-golden.jsonl comes back as HTTP 200 with a non-empty FsmSpec, and that spec
# (converted to house-style YAML, dropped into a throwaway model/ copy under a DISTINCT name/unit so it
# cannot collide with the shipped packml-line1.*) passes the real `:app:cli conformance` gate — the same
# fail-closed authority run-conformance-gate.sh exercises. TransitionGovernor/FsmValidator/model/fsm/**/
# run-conformance-gate.sh are untouched by this script.
#
# App-boot modeled on scripts/run-integration-pov-gate.sh (the ":authoring-api:run" background-JVM +
# poll-for-up + kill_jvms_by-on-EXIT pattern). NOTE (verified in this environment, not with a key):
# authoring-api's DB pool is opened LAZILY (RegistryConfig.kt: minimumIdle=0,
# initializationFailTimeout=-1) and FsmAssistController depends only on FsmAssistService/LlmAssistPort —
# neither touches the DB. So this gate does NOT require `docker compose up` (postgres/temporal); the
# readiness probe below is a plain TCP check on 18090 (gate-common's wait_tcp) rather than an
# HTTP GET against a DB-backed endpoint like /api/workflows, precisely to avoid a false-negative liveness
# failure if no compose stack happens to be running.
#
# Usage (only meaningful with a real key):
#   ANTHROPIC_API_KEY=sk-... bash scripts/run-llm-assist-smoke.sh
#
# Usage (this environment — proves the skip path only):
#   bash scripts/run-llm-assist-smoke.sh   # -> "SKIP ..." and exit 0
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# ---------------------------------------------------------------------------------------------------
# REQUIREMENT 1 (verifiable here): skip cleanly, first thing, when there is no key.
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo "SKIP  no ANTHROPIC_API_KEY — llm-assist golden-set smoke gate skipped (real-LLM path unverifiable without a key)"
  exit 0
fi
# ---------------------------------------------------------------------------------------------------

source scripts/lib/gate-common.sh

API="http://localhost:18090"
GOLDEN="scripts/fixtures/llm-assist-golden.jsonl"
WORK="build/llm-assist-smoke-gate"
mkdir -p "$WORK"
API_LOG="$WORK/api.log"
: > "$API_LOG"

TMP_MODEL=""
cleanup() {
  kill_jvms_by "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true
  [ -n "$TMP_MODEL" ] && rm -rf "$TMP_MODEL" || true
}
trap cleanup EXIT

fail() {
  echo "FAIL  $*"
  echo "----- authoring-api log tail -----"
  tail -60 "$API_LOG" 2>/dev/null || true
  echo "-----------------------------------"
  exit 1
}

[ -f "$GOLDEN" ] || fail "missing golden fixtures $GOLDEN"

# ---------------------------------------------------------------------------------------------------
echo "[GATE] step 1: build :authoring-api"
./gradlew -q --no-daemon :authoring-api:build -x test >/dev/null

echo "[GATE] step 2: boot :authoring-api with HERMOD_LLM_ASSIST=anthropic"
HERMOD_LLM_ASSIST=anthropic ./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &
wait_tcp localhost 18090 60 2 || fail "authoring-api did not come up on :18090 (see log tail below)"
echo "[GATE] authoring-api up on 18090 (HERMOD_LLM_ASSIST=anthropic)"

# Tiny throwaway JS: FsmSpecDto JSON -> house-style YAML (mirrors authoring-ui's fsmYaml.ts emitFsmYaml),
# with the top-level name/unit forced to a value distinct from the shipped packml-line1.* set so the
# conformance gate (which validates the WHOLE model/fsm/ directory) never sees a name/unit collision.
# Kept out of the committed tree per the plan (written to $WORK at run time).
TO_YAML="$WORK/to-yaml.js"
cat > "$TO_YAML" <<'JS'
const fs = require("fs");
const [, , respFile, ovName, ovUnit] = process.argv;
const spec = JSON.parse(fs.readFileSync(respFile, "utf8"));
if (!Array.isArray(spec.states) || spec.states.length === 0) { console.error("no states in draft"); process.exit(2); }
if (!Array.isArray(spec.transitions) || spec.transitions.length === 0) { console.error("no transitions in draft"); process.exit(2); }
const lines = [];
lines.push(`name: ${ovName}`);
lines.push(`unit: ${ovUnit}`);
if (spec.version) lines.push(`version: ${spec.version}`);
lines.push(`stateNode: ${spec.stateNode}`);
lines.push("states:");
for (const s of spec.states) lines.push(`  - { id: ${s.id}, code: ${s.code} }`);
lines.push("transitions:");
for (const t of spec.transitions) {
  const parts = [
    `id: ${t.id}`, `from: ${t.from}`, `to: ${t.to}`,
    `command: ${t.command === null || t.command === undefined ? "null" : t.command}`,
    `driver: ${t.driver}`,
  ];
  if (t.action && t.action.workflow) parts.push(`action: { workflow: ${t.action.workflow} }`);
  lines.push(`  - { ${parts.join(", ")} }`);
}
process.stdout.write(lines.join("\n") + "\n");
JS

WF="$PWD/app/src/main/resources/workflows"
PASS_COUNT=0
FAIL_COUNT=0
CASE_N=0

while IFS= read -r LINE || [ -n "$LINE" ]; do
  [ -z "$LINE" ] && continue                       # skip blank lines
  CASE_N=$((CASE_N + 1))
  echo "[GATE] ===== case $CASE_N: $LINE ====="

  RESP_JSON="$WORK/case${CASE_N}.json"
  CODE=$(curl -s -o "$RESP_JSON" -w '%{http_code}' -X POST "$API/api/fsm/assist" \
    -H 'Content-Type: application/json' --data-binary "$LINE") || { echo "FAIL  case $CASE_N: curl error"; FAIL_COUNT=$((FAIL_COUNT + 1)); continue; }
  if [ "$CODE" != "200" ]; then
    echo "FAIL  case $CASE_N: expected HTTP 200, got $CODE ($(cat "$RESP_JSON" 2>/dev/null))"
    FAIL_COUNT=$((FAIL_COUNT + 1)); continue
  fi

  DRAFT_YAML="$WORK/case${CASE_N}.yaml"
  if ! node "$TO_YAML" "$RESP_JSON" "packml-smoke${CASE_N}" "smoke${CASE_N}" > "$DRAFT_YAML" 2>"$WORK/case${CASE_N}.yaml.err"; then
    echo "FAIL  case $CASE_N: draft is not a structurally parseable FsmSpec (non-empty states/transitions): $(cat "$WORK/case${CASE_N}.yaml.err")"
    FAIL_COUNT=$((FAIL_COUNT + 1)); continue
  fi
  echo "[GATE] case $CASE_N: 200 OK, draft has states+transitions"

  # Drop into a throwaway model/ copy (fresh per case) and run the REAL conformance gate against it.
  TMP_MODEL="$(mktemp -d)"
  cp -r model "$TMP_MODEL/model"
  cp "$DRAFT_YAML" "$TMP_MODEL/model/fsm/packml-assist-smoke.yaml"

  set +e
  CONF_OUT="$(./gradlew -q --console=plain :app:cli \
    --args="conformance --model-dir '$(native_path "$TMP_MODEL/model")' --workflow-dir '$(native_path "$WF")'" 2>&1)"
  CONF_RC=$?
  set -e

  if [ "$CONF_RC" -eq 0 ] && echo "$CONF_OUT" | grep -q "conformance: 0 errors"; then
    echo "PASS  case $CASE_N: accepted by the real conformance gate"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "FAIL  case $CASE_N: rejected by the conformance gate (rc=$CONF_RC)"
    echo "----- conformance output -----"; echo "$CONF_OUT"; echo "-------------------------------"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi

  rm -rf "$TMP_MODEL"; TMP_MODEL=""
done < "$GOLDEN"

# --- Phase B: one EDIT case (current spec + instruction -> complete updated spec accepted by the gate) ---
CASE_N=$((CASE_N + 1))
echo "[GATE] ===== case $CASE_N (EDIT): add a Held state, keep governed loadRecipe ====="
EDIT_REQ="$WORK/edit-req.json"
cat > "$EDIT_REQ" <<'JSON'
{"prompt":"Add a Held state (code 11) reachable from Execute via a field-driven Hold command; keep everything else unchanged.",
 "context":{"name":"packml-line1","unit":"line1","version":"v1","stateNode":"line1.stateCurrent",
  "states":[{"id":"Idle","code":4},{"id":"Execute","code":6},{"id":"Aborted","code":9}],
  "transitions":[
    {"id":"loadRecipe","from":"Idle","to":"Idle","command":"LoadRecipe","driver":"hermod","action":{"workflow":"ot-recipe-stage-activate"}},
    {"id":"start","from":"Idle","to":"Execute","command":"Start","driver":"field"},
    {"id":"abort","from":"Execute","to":"Aborted","command":"Abort","driver":"field"}]}}
JSON
RESP_JSON="$WORK/case${CASE_N}.json"
CODE=$(curl -s -o "$RESP_JSON" -w '%{http_code}' -X POST "$API/api/fsm/assist" \
  -H 'Content-Type: application/json' --data-binary @"$EDIT_REQ") || CODE="000"   # curl failure -> non-200, counted once below
if [ "$CODE" = "200" ]; then
  DRAFT_YAML="$WORK/case${CASE_N}.yaml"
  if node "$TO_YAML" "$RESP_JSON" "packml-smoke${CASE_N}" "smoke${CASE_N}" > "$DRAFT_YAML" 2>"$WORK/case${CASE_N}.yaml.err"; then
    TMP_MODEL="$(mktemp -d)"; cp -r model "$TMP_MODEL/model"; cp "$DRAFT_YAML" "$TMP_MODEL/model/fsm/packml-assist-smoke.yaml"
    set +e
    CONF_OUT="$(./gradlew -q --console=plain :app:cli --args="conformance --model-dir '$(native_path "$TMP_MODEL/model")' --workflow-dir '$(native_path "$WF")'" 2>&1)"
    CONF_RC=$?
    set -e
    if [ "$CONF_RC" -eq 0 ] && echo "$CONF_OUT" | grep -q "conformance: 0 errors"; then
      echo "PASS  case $CASE_N: edited draft accepted by the real conformance gate"; PASS_COUNT=$((PASS_COUNT + 1))
    else
      echo "FAIL  case $CASE_N: edited draft rejected (rc=$CONF_RC)"; echo "$CONF_OUT"; FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
    rm -rf "$TMP_MODEL"; TMP_MODEL=""
  else
    echo "FAIL  case $CASE_N: edited draft not a parseable FsmSpec: $(cat "$WORK/case${CASE_N}.yaml.err")"; FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
else
  echo "FAIL  case $CASE_N (EDIT): expected HTTP 200, got $CODE ($(cat "$RESP_JSON" 2>/dev/null))"; FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""
echo "[GATE] LLM-ASSIST SMOKE: $PASS_COUNT passed, $FAIL_COUNT failed, $CASE_N total"
if [ "$FAIL_COUNT" -eq 0 ] && [ "$CASE_N" -gt 0 ]; then
  echo "[GATE] PASS run-llm-assist-smoke.sh"
  exit 0
else
  echo "[GATE] FAIL run-llm-assist-smoke.sh"
  exit 1
fi
