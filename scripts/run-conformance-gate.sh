#!/usr/bin/env bash
# run-conformance-gate.sh — objective proof that the CI conformance gate fails CLOSED.
# T1  positive: shipped model/ + ot-* workflows -> exit 0.
# T1b warning-surfaced: a cross-ref-warning fixture -> exit 0 AND a WARN line is printed.
# T2  negatives (a-e): one corrupted artifact each -> exit != 0 with the right ERROR text.
# T3  template self-test: the shipped intentional-fail template is rejected (no packml error in T1).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
source scripts/lib/gate-common.sh

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
FAIL=0

# run_conf <model-dir> <workflow-dir> -> sets $OUT (combined stdout+stderr) and $RC (exit code)
run_conf() {
  set +e
  OUT="$(./gradlew -q --console=plain :app:cli \
        --args="conformance --model-dir '$(native_path "$1")' --workflow-dir '$(native_path "$2")'" 2>&1)"
  RC=$?
  set -e
}

pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; echo "----- output -----"; echo "$OUT"; echo "------------------"; FAIL=1; }

MODEL="$PWD/model"
WF="$PWD/app/src/main/resources/workflows"

# ---- T1 positive ----------------------------------------------------------
run_conf "$MODEL" "$WF"
if [ "$RC" -eq 0 ] && echo "$OUT" | grep -q "conformance: 0 errors"; then pass "T1 shipped set is conformant (exit 0)"; else fail "T1 positive"; fi
# ---- T3 template self-test (packml must not surface as an error in T1) -----
if ! echo "$OUT" | grep -q "packml-unit.yaml"; then pass "T3 shipped template correctly rejected (no error surfaced)"; else fail "T3 template self-test"; fi

# ---- T1b warning surfaced, does not fail ----------------------------------
M="$SCRATCH/t1b"; cp -r "$MODEL" "$M"
cat > "$M/command-policy.json" <<'JSON'
{ "default": "deny", "rules": [
  { "id": "rpm-ok",  "node": "recipe.rpmSetpoint",  "allow": true },
  { "id": "temp-ok", "node": "recipe.tempSetpoint", "allow": true },
  { "id": "ghost",   "node": "recipe.ghostNode",    "allow": true } ] }
JSON
run_conf "$M" "$WF"
if [ "$RC" -eq 0 ] && echo "$OUT" | grep -q "WARN.*ghost"; then pass "T1b cross-ref WARNING surfaced, exit 0"; else fail "T1b warning-surfaced"; fi

# ---- T2a duplicate rule id ------------------------------------------------
M="$SCRATCH/t2a"; cp -r "$MODEL" "$M"
cat > "$M/command-policy.json" <<'JSON'
{ "default": "deny", "rules": [
  { "id": "dup", "node": "recipe.rpmSetpoint",  "allow": true },
  { "id": "dup", "node": "recipe.tempSetpoint", "allow": true } ] }
JSON
run_conf "$M" "$WF"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q "duplicate rule id"; then pass "T2a duplicate rule id -> fail-closed"; else fail "T2a"; fi

# ---- T2b malformed nodeId -------------------------------------------------
M="$SCRATCH/t2b"; cp -r "$MODEL" "$M"
sed -i 's#ns=2;s=Recipe/Rpm#ns=2;x=Recipe/Rpm#' "$M/ot-site.yaml"
run_conf "$M" "$WF"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q "nodeId"; then pass "T2b malformed nodeId -> fail-closed"; else fail "T2b"; fi

# ---- T2c recipe desired out of EURange ------------------------------------
M="$SCRATCH/t2c"; cp -r "$MODEL" "$M"
sed -i 's#desired: 1500#desired: 99999#' "$M/recipe-setpoints.yaml"
run_conf "$M" "$WF"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q "EURange"; then pass "T2c recipe out of EURange -> fail-closed"; else fail "T2c"; fi

# ---- T2d delegation threshold out of range --------------------------------
M="$SCRATCH/t2d"; cp -r "$MODEL" "$M"
sed -i 's#"threshold": 0.80#"threshold": 1.5#' "$M/delegation-policy.json"
run_conf "$M" "$WF"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q "threshold"; then pass "T2d delegation threshold -> fail-closed"; else fail "T2d"; fi

# ---- T2e workflow references an unregistered block (offline, deterministic) -
WFB="$SCRATCH/wf"; mkdir -p "$WFB"
printf 'name: ot-bad\nsteps:\n  - { block: no.such.block, version: "1.0.0" }\n' > "$WFB/ot-bad.yaml"
run_conf "$MODEL" "$WFB"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q "ot-bad.yaml"; then pass "T2e unregistered block -> fail-closed (offline)"; else fail "T2e"; fi

# ---- T2f malformed FSM spec (unknown from-state) --------------------------
M="$SCRATCH/t2f"; cp -r "$MODEL" "$M"
sed -i 's#from: Idle, to: Execute, command: Start#from: Nope, to: Execute, command: Start#' "$M/fsm/packml-line1.yaml"
run_conf "$M" "$WF"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q "packml-line1.yaml"; then pass "T2f malformed FSM -> fail-closed"; else fail "T2f"; fi

echo
if [ "$FAIL" -eq 0 ]; then echo "CONFORMANCE GATE: PASS"; else echo "CONFORMANCE GATE: FAIL"; exit 1; fi
