#!/usr/bin/env bash
# R4 Soak-Window + Auto-Rollback gate (objective proof): a canary deploy can enter a SOAKING window
# instead of promoting immediately; failures reported during the window accumulate a fail_count, and
# a periodic sweep (yggdrasil.hermod.registry.SoakSupervisor.sweep, driven in prod by authoring-api's
# SoakSupervisorBean @Scheduled) either AUTO_ROLLBACKs a soaking deployment that hit its fail
# threshold, or PROMOTEs one that survived past soak_until untouched. This gate drives the SAME
# supervisor via the Git-native :app fsm CLI subcommands (deploy --soak-seconds/--fail-threshold,
# report-failure, soak-sweep, status) — no server needed; the CLI opens its own DB connection per
# invocation (yggdrasil.hermod.blocks.Db, HERMOD_DB_URL/USER/PASS).
#
# SERVER-LESS: unlike run-fsm-canary-gate.sh (which boots sim+worker+authoring-api because it drives
# a REAL governed OPC-UA run), this gate only needs Postgres + the :app fsm CLI. No sim, no worker,
# no API, no OPC-UA I/O.
#
#   T1  auto-rollback : deploy v1 (rollback target) -> deploy v2 soaking (threshold 2) -> 2 reported
#                       failures (>= threshold) -> sweep -> AUTO_ROLLBACK line1 v2 -> v1; active=v1;
#                       status=promoted (rollback target is not itself "soaking"); audit row written.
#   T2  promote       : deploy v1 -> deploy v2 soaking (1s window) -> sleep past soak_until -> sweep
#                       -> PROMOTE line1 v2; active stays v2; status=promoted.
#   T3  no-op         : deploy v1 -> deploy v2 soaking (long window, threshold 2) -> 1 reported failure
#                       (below threshold) -> sweep -> "no soak actions"; status stays soaking
#                       fail_count=1/2; active stays v2 (nothing flipped).
#   T4  reject        : deploy v2 --soak-seconds with NO prior deployed version for the unit is
#                       refused (a soak needs a known rollback target); pointer stays unset.
#   T5  regression    : OPT-IN (SOAK_GATE_RUN_CANARY=1). The R4 canary+instant-rollback gate
#                       (run-fsm-canary-gate.sh) boots its own full sim+worker+authoring-api stack and
#                       drives governed Temporal runs, so it is slow; chaining it here would push the
#                       total past a single harness run window. By DEFAULT T5 is SKIPPED and the canary
#                       regression is verified by running run-fsm-canary-gate.sh SEPARATELY (its own
#                       [GATE] PASS). Set SOAK_GATE_RUN_CANARY=1 to chain it inline (~10+ extra min).
#
# HERMOD_SOAK_DISABLED=1 is exported so that IF an authoring-api happened to be running against the
# same DB it would not race this gate's manual sweeps (defense-in-depth; this gate does not start
# authoring-api itself).
#
# Run from repo root:
#   bash scripts/run-fsm-soak-gate.sh                         # T1-T4 (new soak behaviour) -> [GATE] PASS
#   SOAK_GATE_RUN_CANARY=1 bash scripts/run-fsm-soak-gate.sh  # also chain the canary regression (slow)
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/lib/gate-common.sh   # psql_q, native_path (include-guarded)

export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"
export HERMOD_SOAK_DISABLED=1   # defense-in-depth: no in-process supervisor should race our manual sweeps

WORK="build/fsm-soak-gate"
mkdir -p "$WORK"

fail() {
  echo "[GATE] FAIL: $*"
  exit 1
}

echo "[GATE] db = $HERMOD_DB_URL"

# ---------------------------------------------------------------------------
echo "[GATE] step 0: bring up postgres only (server-less gate; no sim/worker/api)"
docker compose up -d postgres >/dev/null

PGUP=0
for i in $(seq 1 30); do
  if docker compose exec -T postgres pg_isready -U hermod >/dev/null 2>&1; then PGUP=1; break; fi
  sleep 2
done
[ "$PGUP" = "1" ] || fail "postgres did not become ready within ~60s"
echo "[GATE] postgres ready"

echo "[GATE] step 0.5: apply registry schema (fsm_deployment + fsm_deployment_audit)"
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
echo "[GATE] schema applied"

reset() { psql_q "TRUNCATE fsm_deployment, fsm_deployment_audit" >/dev/null; }

# ---------------------------------------------------------------------------
# run the :app fsm CLI subcommand; app stdout (println) flows to our stdout. Propagates exit code.
fsm_cli() { ./gradlew -q --console=plain --no-daemon :app:cli --args="fsm $*"; }
aud_audit() { psql_q "SELECT count(*) FROM fsm_deployment_audit WHERE unit='$1' AND action='$2' AND to_version='$3'"; }

echo "[GATE] step 1: build :app once so subsequent fsm_cli invocations are fast"
./gradlew -q --no-daemon :app:build -x test >/dev/null

# ---------------------------------------------------------------------------
echo "[GATE] ===== T1: auto-rollback (2 failures >= threshold 2) ====="
reset
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "T1 deploy v1 (rollback target) failed"
OUT="$(fsm_cli deploy line1 v2 --soak-seconds 3600 --fail-threshold 2)"
echo "[GATE] $OUT"
echo "$OUT" | grep -q "^deployed line1 -> v2" || fail "T1 deploy v2 --soak-seconds failed: $OUT"
fsm_cli report-failure line1 | grep -q "^counted failure for line1" || fail "T1 report-failure #1 failed"
fsm_cli report-failure line1 | grep -q "^counted failure for line1" || fail "T1 report-failure #2 failed"
OUT="$(fsm_cli soak-sweep)"
echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "AUTO_ROLLBACK line1 v2 -> v1" || fail "T1 expected AUTO_ROLLBACK line1 v2 -> v1, got: $OUT"
[ "$(fsm_cli active line1 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" = "v1" ] || fail "T1 active should be v1 after auto-rollback"
fsm_cli status line1 | grep -q "status=promoted" || fail "T1 status should read promoted after auto-rollback"
[ "$(aud_audit line1 AUTO_ROLLBACK v1)" -ge 1 ] || fail "T1 expected an AUTO_ROLLBACK->v1 audit row"
echo "[GATE] T1 OK"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T2: promote (survives past soak_until, no failures) ====="
reset
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "T2 deploy v1 (rollback target) failed"
OUT="$(fsm_cli deploy line1 v2 --soak-seconds 1 --fail-threshold 2)"
echo "$OUT" | grep -q "^deployed line1 -> v2" || fail "T2 deploy v2 --soak-seconds 1 failed: $OUT"
sleep 2
OUT="$(fsm_cli soak-sweep)"
echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "PROMOTE line1 v2" || fail "T2 expected PROMOTE line1 v2, got: $OUT"
[ "$(fsm_cli active line1 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" = "v2" ] || fail "T2 active should stay v2 after promote"
fsm_cli status line1 | grep -q "status=promoted" || fail "T2 status should read promoted after promote"
echo "[GATE] T2 OK"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T3: no-op (1 failure < threshold 2 -> still soaking) ====="
reset
fsm_cli deploy line1 v1 | grep -q "^deployed line1 -> v1" || fail "T3 deploy v1 (rollback target) failed"
OUT="$(fsm_cli deploy line1 v2 --soak-seconds 3600 --fail-threshold 2)"
echo "$OUT" | grep -q "^deployed line1 -> v2" || fail "T3 deploy v2 --soak-seconds failed: $OUT"
fsm_cli report-failure line1 | grep -q "^counted failure for line1" || fail "T3 report-failure #1 failed"
OUT="$(fsm_cli soak-sweep)"
echo "[GATE] sweep: $OUT"
echo "$OUT" | grep -q "no soak actions" || fail "T3 expected 'no soak actions', got: $OUT"
STATUS="$(fsm_cli status line1)"
echo "[GATE] status: $STATUS"
echo "$STATUS" | grep -q "status=soaking" || fail "T3 expected status=soaking, got: $STATUS"
echo "$STATUS" | grep -q "fail_count=1/2" || fail "T3 expected fail_count=1/2, got: $STATUS"
[ "$(fsm_cli active line1 2>/dev/null | grep -E '^v[0-9]+$' | tail -1)" = "v2" ] || fail "T3 active should stay v2 (no-op)"
echo "[GATE] T3 OK"

# ---------------------------------------------------------------------------
echo "[GATE] ===== T4: reject a soak deploy with no prior version (no rollback target) ====="
reset
if fsm_cli deploy line1 v2 --soak-seconds 3600 >"$WORK/t4.out" 2>"$WORK/t4.err"; then
  fail "T4 first-deploy soak should be rejected (no prior version to roll back to), but it succeeded"
fi
grep -q "no prior version" "$WORK/t4.err" || fail "T4 expected stderr to mention 'no prior version', got: $(cat "$WORK/t4.err")"
if fsm_cli active line1 >/dev/null 2>&1; then fail "T4 unit should have NO active deployment after the rejected soak deploy"; fi
echo "[GATE] T4 OK: first-deploy soak refused, pointer stays unset"

# ---------------------------------------------------------------------------
# T5 (canary regression) is OPT-IN: run-fsm-canary-gate.sh boots the full sim+worker+authoring-api stack
# and drives governed runs on Temporal, so chaining it here pushes the total wall-clock past a single
# harness run window. By default this gate proves the NEW soak behaviour (T1-T4) and exits clean; the
# canary regression is verified by running run-fsm-canary-gate.sh SEPARATELY (it is its own [GATE] PASS).
# Set SOAK_GATE_RUN_CANARY=1 to also run it inline (allow ~10+ extra minutes).
if [ "${SOAK_GATE_RUN_CANARY:-0}" = "1" ]; then
  echo "[GATE] ===== T5: regression — run-fsm-canary-gate.sh (non-soaking deploy/rollback/governance) still passes ====="
  if bash scripts/run-fsm-canary-gate.sh > "$WORK/canary.out" 2>&1; then
    grep -q "\[GATE\] PASS" "$WORK/canary.out" || fail "T5 run-fsm-canary-gate.sh exited 0 but did not print [GATE] PASS"
  else
    tail -60 "$WORK/canary.out"
    fail "T5 run-fsm-canary-gate.sh (regression) failed"
  fi
  echo "[GATE] T5 OK: canary regression gate passes"
else
  echo "[GATE] T5 SKIPPED (canary regression) — run 'bash scripts/run-fsm-canary-gate.sh' separately, or SOAK_GATE_RUN_CANARY=1 to chain it"
fi

echo ""
echo "[GATE] PASS run-fsm-soak-gate.sh"
exit 0
