#!/usr/bin/env bash
# =============================================================================
# stop-wakeword-openwakeword.sh
# -----------------------------------------------------------------------------
# Gracefully stop the openWakeWord sidecar:
#   1. POST /stop so the running server releases the microphone cleanly.
#   2. Find the uvicorn/openwakeword_server process on the configured port and
#      send SIGTERM (fall back to SIGKILL if it refuses to exit).
#   3. Confirm the port is free.
#
# Idempotent: safe to run when nothing is listening. Honors the same env vars
# as run-wakeword-openwakeword.sh.
#
#   JARVIS_WAKEWORD_PORT   (default 18095)
#   JARVIS_WAKEWORD_HOST   (default 127.0.0.1)
# =============================================================================
set -uo pipefail

PORT="${JARVIS_WAKEWORD_PORT:-18095}"
HOST="${JARVIS_WAKEWORD_HOST:-127.0.0.1}"
BASE="http://${HOST}:${PORT}"

TERM_WAIT_SEC=5      # seconds to wait for a graceful SIGTERM exit
PROC_MATCH="openwakeword_server"

log()  { echo "[stop] $*"; }
warn() { echo "[stop][warn] $*" >&2; }

# --- find PIDs listening on the target port (best available tool) -----------
find_port_pids() {
  local pids=""
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -ti "tcp:${PORT}" -sTCP:LISTEN 2>/dev/null || true)"
  fi
  if [[ -z "${pids}" ]] && command -v ss >/dev/null 2>&1; then
    # Extract pid=NNN from ss output for the listening socket on PORT.
    pids="$(ss -tlnp 2>/dev/null | awk -v p=":${PORT}" '$4 ~ p' \
            | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)"
  fi
  if [[ -z "${pids}" ]] && command -v fuser >/dev/null 2>&1; then
    pids="$(fuser "${PORT}/tcp" 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$' || true)"
  fi
  echo "${pids}" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -u
}

# --- fallback: PIDs matching the server command line ------------------------
find_proc_pids() {
  pgrep -f "${PROC_MATCH}" 2>/dev/null | sort -u || true
}

is_port_free() {
  local pids
  pids="$(find_port_pids)"
  [[ -z "${pids}" ]]
}

# --- 1. graceful HTTP stop --------------------------------------------------
if command -v curl >/dev/null 2>&1; then
  if curl -sf -o /dev/null -m 3 "${BASE}/health" 2>/dev/null; then
    log "POST ${BASE}/stop (release microphone)"
    curl -s -m 5 -X POST "${BASE}/stop" >/dev/null 2>&1 || warn "/stop request failed (continuing)"
    sleep 1
  else
    log "server not responding on ${BASE} (may already be stopped)"
  fi
else
  warn "curl not found; skipping graceful /stop"
fi

# --- 2. terminate the process -----------------------------------------------
PIDS="$(find_port_pids)"
if [[ -z "${PIDS}" ]]; then
  PIDS="$(find_proc_pids)"
  [[ -n "${PIDS}" ]] && log "no listener on port ${PORT}; matched ${PROC_MATCH} process(es) instead"
fi

if [[ -z "${PIDS}" ]]; then
  log "nothing to stop — port ${PORT} already free."
  exit 0
fi

for pid in ${PIDS}; do
  log "sending SIGTERM to pid ${pid}"
  kill -TERM "${pid}" 2>/dev/null || warn "could not SIGTERM pid ${pid} (already gone?)"
done

# --- wait for graceful exit, then confirm / force ---------------------------
for _ in $(seq 1 "${TERM_WAIT_SEC}"); do
  is_port_free && { log "port ${PORT} is now free."; exit 0; }
  sleep 1
done

warn "port ${PORT} still in use after ${TERM_WAIT_SEC}s; escalating to SIGKILL"
for pid in $(find_port_pids) $(find_proc_pids); do
  [[ -n "${pid}" ]] || continue
  log "sending SIGKILL to pid ${pid}"
  kill -KILL "${pid}" 2>/dev/null || true
done
sleep 1

if is_port_free; then
  log "port ${PORT} is now free."
  exit 0
fi

warn "port ${PORT} is STILL in use; manual intervention may be required:"
warn "  $(find_port_pids | tr '\n' ' ')"
exit 1
