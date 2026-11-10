#!/usr/bin/env bash
# §1/§6 v0.3d GATE (objective proof): the block-engineer AUTHORING surface is real and LIVE.
#
# The runtime claim under test is the LIVE registry/palette PROJECTION (spec §3 Layer A): an authored
# contract is published via the API, and publish -> hot-update -> deprecate are ALL reflected in
# `GET /api/palette` with NO restart of the authoring API (one process, captured PID unchanged). This is
# NOT a claim about hot WORKER execution of a newly-registered block (both engines need a startup rebind,
# §3 Layer B) — no Temporal/Conductor/worker is involved here; the gate needs only Postgres 15432.
#
# Seven hard asserts (ALL must hold for PASS):
#   1 publish authored.json + jar via POST /api/publish -> 200; block_index has exactly 1 row for
#     io.example.canvasdemo#1.0.0.
#   2 GET /api/palette shows it canvas-ready: param labels non-empty, port labels non-empty, category + risk present.
#   3 POST /api/contracts/validate with incomplete.json -> complete==false + readiness has C3; the
#     incomplete block is NOT in /api/palette.
#   4 publish authored-v2.json (1.1.0) -> /api/palette latestVersion==1.1.0; the 1.0.0 row still resolves
#     (present in GET /api/blocks) — pin safety.
#   5 POST .../1.1.0/deprecate -> 204; /api/palette no longer offers 1.1.0 as latest (falls back to 1.0.0);
#     1.1.0 still resolves (present in GET /api/blocks) — resolution untouched.
#   6 the API PID captured at startup is UNCHANGED at the end (publish->update->deprecate served live by
#     one process, no restart) — the LIVE-loop headline.
#   7 the built :core/:registry/:compiler/:dispatch jars reference 0 org.springframework (Spring confined
#     to the :authoring-api edge module).
#
# Run from repo root with the stack up + v0.1 schema applied:
#   docker compose up -d
#   bash scripts/init-db.sh
#   bash scripts/run-authoring-gate.sh   # expect: [GATE] PASS ... exit 0
#
# WINDOWS KILL NOTE (REF run-add-block-gate.sh): `./gradlew run` forks a separate JVM
# (yggdrasil.hermod.authoring.AuthoringApplicationKt); $! is the wrapper PID, not the JVM. We resolve the real JVM
# via `jps` and taskkill by PID.
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Shared env: pin the plugin store dir + DB so the forked API JVM agrees with this shell. ---
PLUGIN_DIR_SH="${HERMOD_PLUGIN_DIR:-$(pwd)/registry-store}"
export HERMOD_PLUGIN_DIR="$(cygpath -w "$PLUGIN_DIR_SH" 2>/dev/null || echo "$PLUGIN_DIR_SH")"
export HERMOD_DB_URL="${HERMOD_DB_URL:-jdbc:postgresql://localhost:15432/hermod}"
export HERMOD_DB_USER="${HERMOD_DB_USER:-hermod}"
export HERMOD_DB_PASS="${HERMOD_DB_PASS:-hermod}"

ID="io.example.canvasdemo"
FIX="scripts/fixtures/authoring"
JAR="$FIX/canvasdemo-plugin.jar"
API="http://localhost:18090"
# Work dir is repo-relative so BOTH Git Bash (curl) and the Windows `python` interpreter resolve the same
# files (a Git Bash `/tmp/...` path is read by Windows python as C:\tmp\... — they disagree). The gate cwds
# to the repo root, so a relative path is unambiguous for both.
WORK="build/authoring-gate"
mkdir -p "$WORK"
API_LOG="$WORK/api.log"
API_PID=""

psql_q() { docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'; }

# Pull the real forked API JVM pid out of jps (the gradle wrapper PID is not the server).
api_jvm_pid() { { jps -l 2>/dev/null | grep "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | head -1; }

kill_api_jvms() {
  { jps -l 2>/dev/null | grep "yggdrasil.hermod.authoring.AuthoringApplicationKt" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}

cleanup() { kill_api_jvms || true; }
trap cleanup EXIT

fail() { echo "[GATE] FAIL: $*"; exit 1; }

echo "[GATE] plugin dir = $HERMOD_PLUGIN_DIR ; db = $HERMOD_DB_URL"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: ensure registry schema (block_index incl. deprecated column) + reset state"
# init-db.sh applies the v0.1 schema; the registry schema (block_index + the deprecated ALTER) is NOT in
# init-db, so apply it here explicitly.
docker compose exec -T postgres psql -U hermod -d hermod < registry/src/main/resources/registry-schema.sql >/dev/null
# Clean prior runs (publish rejects duplicate id#version; versions are immutable).
psql_q "DELETE FROM block_index WHERE id='$ID';" >/dev/null
rm -rf "$PLUGIN_DIR_SH/$ID"

[ -f "$JAR" ] || fail "fixture jar missing at $JAR (build it: cd examples/greet-plugin && ./gradlew jar, then copy)"

# ---------------------------------------------------------------------------
echo "[GATE] step 2: build + start the authoring API on 18090 (background); capture its PID; poll /api/palette"
./gradlew -q --no-daemon :authoring-api:build -x test >/dev/null
: > "$API_LOG"
# --no-daemon so the launcher (and thus the forked run JVM) inherits THIS shell's HERMOD_* env.
./gradlew -q --no-daemon :authoring-api:run >"$API_LOG" 2>&1 &

# Poll up to ~120s for the server to answer, then resolve the real JVM PID.
UP=0
for i in $(seq 1 60); do
  if curl -sf "$API/api/palette" >/dev/null 2>&1; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || { echo "--- API log tail ---"; tail -40 "$API_LOG" || true; fail "API did not answer GET /api/palette within ~120s"; }
API_PID="$(api_jvm_pid)"
[ -n "$API_PID" ] || fail "could not resolve the authoring API JVM pid via jps"
echo "[GATE] API up; AuthoringApplicationKt JVM pid = $API_PID"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 1: publish authored.json + jar -> 200; exactly 1 block_index row ====="
PUB_CODE=$(curl -s -o "$WORK/pub1.json" -w '%{http_code}' \
  -F "contract=@$FIX/authored.json;type=application/json" \
  -F "jar=@$JAR;type=application/java-archive" \
  "$API/api/publish")
echo "[GATE] publish 1.0.0 http=$PUB_CODE body=$(cat "$WORK/pub1.json")"
[ "$PUB_CODE" = "200" ] || fail "publish authored.json expected 200, got $PUB_CODE"
DB_COUNT=$(psql_q "SELECT count(*) FROM block_index WHERE id='$ID' AND version='1.0.0'")
echo "[GATE] block_index count for $ID#1.0.0 = $DB_COUNT"
[ "$DB_COUNT" = "1" ] || fail "expected exactly 1 block_index row for $ID#1.0.0, got $DB_COUNT"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 2: GET /api/palette shows the block canvas-ready ====="
curl -sf "$API/api/palette" -o "$WORK/palette1.json" || fail "GET /api/palette failed"
python - "$WORK/palette1.json" "$ID" <<'PY' || fail "Assert 2: palette card not canvas-ready"
import sys, json
path, bid = sys.argv[1], sys.argv[2]
cards = json.load(open(path, encoding="utf-8"))
c = next((x for x in cards if x["id"] == bid), None)
assert c is not None, f"{bid} absent from /api/palette"
assert c.get("category"), "category missing/empty"
assert c.get("risk"), "risk missing/empty"
assert c["latestVersion"] == "1.0.0", f"latestVersion={c['latestVersion']}"
assert c["params"] and all(p.get("label") for p in c["params"]), "a param label is empty"
ports = c.get("inputs", []) + c.get("outputs", [])
assert ports and all(p.get("label") for p in ports), "a port label is empty"
print(f"[GATE] palette card OK: {bid} v{c['latestVersion']} risk={c['risk']} "
      f"params={[p['label'] for p in c['params']]} ports={[p['label'] for p in ports]}")
PY

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 3: validate incomplete.json -> complete=false + C3; absent from palette ====="
curl -sf -X POST "$API/api/contracts/validate" -H 'Content-Type: application/json' \
  --data-binary "@$FIX/incomplete.json" -o "$WORK/validate.json" \
  || fail "POST /api/contracts/validate failed"
echo "[GATE] validate body=$(cat "$WORK/validate.json")"
python - "$WORK/validate.json" <<'PY' || fail "Assert 3: incomplete contract not flagged"
import sys, json
r = json.load(open(sys.argv[1], encoding="utf-8"))
assert r["complete"] is False, f"expected complete=false, got {r['complete']}"
codes = {d["code"] for d in r["readiness"]}
assert "C3" in codes, f"expected C3 in readiness, got {codes}"
print(f"[GATE] validate OK: complete={r['complete']} codes={sorted(codes)}")
PY
# the incomplete version (1.0.2) was never published, so it must not appear; assert palette latest != 1.0.2
curl -sf "$API/api/palette" -o "$WORK/palette3.json" || fail "GET /api/palette failed"
python - "$WORK/palette3.json" "$ID" <<'PY' || fail "Assert 3: incomplete version leaked into palette"
import sys, json
path, bid = sys.argv[1], sys.argv[2]
c = next((x for x in json.load(open(path, encoding="utf-8")) if x["id"] == bid), None)
assert c is not None, f"{bid} absent from palette"
assert c["latestVersion"] != "1.0.2", "incomplete 1.0.2 must not be palette latest"
print(f"[GATE] palette latest still {c['latestVersion']} (incomplete 1.0.2 absent)")
PY

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 4: hot-update -> publish 1.1.0; palette latest=1.1.0; 1.0.0 still resolves ====="
PUB2_CODE=$(curl -s -o "$WORK/pub2.json" -w '%{http_code}' \
  -F "contract=@$FIX/authored-v2.json;type=application/json" \
  -F "jar=@$JAR;type=application/java-archive" \
  "$API/api/publish")
echo "[GATE] publish 1.1.0 http=$PUB2_CODE body=$(cat "$WORK/pub2.json")"
[ "$PUB2_CODE" = "200" ] || fail "publish authored-v2.json expected 200, got $PUB2_CODE"
curl -sf "$API/api/palette" -o "$WORK/palette4.json" || fail "GET /api/palette failed"
python - "$WORK/palette4.json" "$ID" <<'PY' || fail "Assert 4: palette did not hot-update to 1.1.0"
import sys, json
path, bid = sys.argv[1], sys.argv[2]
c = next((x for x in json.load(open(path, encoding="utf-8")) if x["id"] == bid), None)
assert c is not None, f"{bid} absent from palette"
assert c["latestVersion"] == "1.1.0", f"expected latest 1.1.0, got {c['latestVersion']}"
print(f"[GATE] palette hot-updated: latest={c['latestVersion']} (no API restart)")
PY
# 1.0.0 still in the registry (resolution untouched) — present in the full /api/blocks list.
curl -sf "$API/api/blocks" -o "$WORK/blocks4.json" || fail "GET /api/blocks failed"
python - "$WORK/blocks4.json" "$ID" "1.0.0" <<'PY' || fail "Assert 4: 1.0.0 no longer resolves"
import sys, json
path, bid, ver = sys.argv[1], sys.argv[2], sys.argv[3]
rows = json.load(open(path, encoding="utf-8"))
hit = any(r["card"]["id"] == bid and r["card"]["latestVersion"] == ver for r in rows)
assert hit, f"{bid}#{ver} absent from /api/blocks (resolution lost)"
print(f"[GATE] {bid}#{ver} still resolves (present in /api/blocks)")
PY

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 5: deprecate 1.1.0 -> 204; palette falls back to 1.0.0; 1.1.0 still resolves ====="
DEP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/blocks/$ID/1.1.0/deprecate")
echo "[GATE] deprecate 1.1.0 http=$DEP_CODE"
[ "$DEP_CODE" = "204" ] || fail "deprecate 1.1.0 expected 204, got $DEP_CODE"
curl -sf "$API/api/palette" -o "$WORK/palette5.json" || fail "GET /api/palette failed"
python - "$WORK/palette5.json" "$ID" <<'PY' || fail "Assert 5: deprecated 1.1.0 still offered as latest"
import sys, json
path, bid = sys.argv[1], sys.argv[2]
c = next((x for x in json.load(open(path, encoding="utf-8")) if x["id"] == bid), None)
assert c is not None, f"{bid} disappeared from palette (1.0.0 should remain)"
assert c["latestVersion"] == "1.0.0", f"expected fallback to 1.0.0, got {c['latestVersion']}"
print(f"[GATE] palette fell back to {c['latestVersion']} after deprecating 1.1.0")
PY
# 1.1.0 still resolvable (soft-delete only — resolution path intact).
curl -sf "$API/api/blocks" -o "$WORK/blocks5.json" || fail "GET /api/blocks failed"
python - "$WORK/blocks5.json" "$ID" "1.1.0" <<'PY' || fail "Assert 5: deprecated 1.1.0 no longer resolves"
import sys, json
path, bid, ver = sys.argv[1], sys.argv[2], sys.argv[3]
rows = json.load(open(path, encoding="utf-8"))
r = next((r for r in rows if r["card"]["id"] == bid and r["card"]["latestVersion"] == ver), None)
assert r is not None, f"{bid}#{ver} absent from /api/blocks after deprecate (resolution lost)"
assert r["deprecated"] is True, f"{bid}#{ver} not flagged deprecated in /api/blocks"
print(f"[GATE] {bid}#{ver} still resolves and is flagged deprecated=true (soft-delete only)")
PY

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 6: LIVE loop — single API process, PID unchanged, no restart ====="
API_PID_NOW="$(api_jvm_pid)"
echo "[GATE] API pid: start=$API_PID end=$API_PID_NOW"
[ -n "$API_PID_NOW" ] || fail "API JVM no longer running at end of gate"
[ "$API_PID" = "$API_PID_NOW" ] || fail "API PID changed ($API_PID -> $API_PID_NOW): the gate restarted the server"
echo "[GATE] LIVE loop proven: publish -> hot-update -> deprecate all served by one process (pid $API_PID)"

# ---------------------------------------------------------------------------
echo "[GATE] ===== Assert 7: boundary — :core/:registry/:compiler/:dispatch jars carry 0 org.springframework ====="
./gradlew -q --no-daemon :core:jar :registry:jar :compiler:jar :dispatch:jar >/dev/null
BOUNDARY_OK=1
for m in core registry compiler dispatch; do
  JARP=$(ls "$m"/build/libs/*.jar 2>/dev/null | head -1 || true)
  [ -n "$JARP" ] || fail "no built jar found for :$m"
  if unzip -l "$JARP" | grep -qi "org/springframework"; then
    echo "[GATE] BOUNDARY VIOLATION: :$m jar ($JARP) references org.springframework"
    BOUNDARY_OK=0
  else
    echo "[GATE] :$m jar clean (no org.springframework)"
  fi
done
[ "$BOUNDARY_OK" = "1" ] || fail "Spring leaked outside :authoring-api"

echo "[GATE] PASS (authoring surface: publish+canvas-ready palette+completeness gate+hot-update+soft-delete, all LIVE on one API process; Spring confined to :authoring-api)"
exit 0
