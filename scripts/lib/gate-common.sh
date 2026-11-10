#!/usr/bin/env bash
# gate-common.sh — shared helpers for hermod's objective bash gates.
#
# SOURCE this from a gate script; it defines functions only. It deliberately does NOT set
# `set -euo pipefail`, `cd`, traps, or any side-effecting top-level code — the CALLER owns its
# environment, working directory, and cleanup trap. Guarded against double-source below.
#
# Adoption is incremental: new gates source this library; the older gates
# (run-opcua-gate.sh, run-delegation-gate.sh, run-integration-pov-gate.sh) keep their inline
# helpers and are intentionally left untouched. run-r2-ncmd-gate.sh is the first adopter.

# Include guard: safe to source more than once.
if [ -n "${_HERMOD_GATE_COMMON_SH:-}" ]; then return 0 2>/dev/null || true; fi
_HERMOD_GATE_COMMON_SH=1

# native_path <path> — echo a native-Windows path that NATIVE tools (docker, native JVMs) accept.
# Use this for any path handed to a NATIVE binary via `-f`/`-D` etc.: an MSYS `/c/...` path passed
# to e.g. `docker compose -f` becomes an invalid `C:\c\...`. `cygpath -m` yields a Windows path with
# forward slashes that such tools accept. NOTE: `cd "$dir" && <tool>` is immune (it cds, not -f) and
# needs no conversion. Falls back to the input unchanged when cygpath is unavailable (non-MSYS).
native_path() {
  cygpath -m "$1" 2>/dev/null || echo "$1"
}

# psql_q <sql> — run a single SQL statement in the compose `postgres` service and strip all
# whitespace from the result (so it can be compared directly, e.g. `[ "$(psql_q ...)" = "1" ]`).
psql_q() {
  docker compose exec -T postgres psql -U hermod -d hermod -tAc "$1" | tr -d '[:space:]'
}

# kill_jvms_by <mainclass-substring> — best-effort kill every JVM whose `jps -l` main-class line
# contains the given substring, killing the process TREE (//F //T) on Windows. Never fails the
# caller (all steps are `|| true`). Generalizes the per-mainclass kill_*_jvms helpers.
kill_jvms_by() {
  { jps -l 2>/dev/null | grep "$1" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}

# wait_for_log <file> <pattern> <tries> [sleep_s] — poll until `grep -q "<pattern>" "<file>"`
# succeeds. Returns 0 on the first hit, 1 once <tries> attempts are exhausted. Default sleep 2s.
wait_for_log() {
  local file="$1" pattern="$2" tries="$3" sleep_s="${4:-2}"
  local i
  for i in $(seq 1 "$tries"); do
    grep -q "$pattern" "$file" 2>/dev/null && return 0
    sleep "$sleep_s"
  done
  return 1
}

# wait_http_ok <url> <tries> [sleep_s] — poll until `curl -sf "<url>"` succeeds (2xx, no output).
# Returns 0 on the first success, 1 once <tries> attempts are exhausted. Default sleep 2s.
wait_http_ok() {
  local url="$1" tries="$2" sleep_s="${3:-2}"
  local i
  for i in $(seq 1 "$tries"); do
    curl -sf "$url" >/dev/null 2>&1 && return 0
    sleep "$sleep_s"
  done
  return 1
}

# wait_tcp <host> <port> <tries> [sleep_s] — poll until a TCP connect to <host>:<port> succeeds
# (via bash's /dev/tcp). Returns 0 on the first success, 1 once <tries> attempts are exhausted.
# Default sleep 2s.
wait_tcp() {
  local host="$1" port="$2" tries="$3" sleep_s="${4:-2}"
  local i
  for i in $(seq 1 "$tries"); do
    bash -c "echo > /dev/tcp/$host/$port" >/dev/null 2>&1 && return 0
    sleep "$sleep_s"
  done
  return 1
}
