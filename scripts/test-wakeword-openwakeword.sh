#!/usr/bin/env bash
# =============================================================================
# test-wakeword-openwakeword.sh
# -----------------------------------------------------------------------------
# Smoke-test the running openWakeWord sidecar: curl /health, /devices,
# /diagnostics and pretty-print them. With --listen, also start capture and
# stream WAKE_DETECTED events over SSE for ~20 seconds (say "Hey Jarvis").
#
# Usage:
#   bash scripts/test-wakeword-openwakeword.sh            # health/devices/diag
#   bash scripts/test-wakeword-openwakeword.sh --listen   # + live SSE wake test
# =============================================================================
set -uo pipefail

PORT="${JARVIS_WAKEWORD_PORT:-18095}"
HOST="${JARVIS_WAKEWORD_HOST:-127.0.0.1}"
BASE="http://${HOST}:${PORT}"
LISTEN=0
[[ "${1:-}" == "--listen" ]] && LISTEN=1

# jq is optional; fall back to raw output when absent.
pp() { if command -v jq >/dev/null 2>&1; then jq .; else cat; fi; }

echo "== Jarvis wake-word sidecar smoke test =="
echo "Target: ${BASE}"
echo

if ! curl -sf -o /dev/null -m 4 "${BASE}/health"; then
  echo "[test] Server is NOT up at ${BASE}"
  echo "[test] Start it with:"
  echo "         bash scripts/run-wakeword-openwakeword.sh"
  echo "       (or run in background:)"
  echo "         nohup bash scripts/run-wakeword-openwakeword.sh > /tmp/wakeword.log 2>&1 &"
  exit 1
fi

echo "--- GET /health ---"
curl -s "${BASE}/health" | pp
echo

echo "--- GET /devices ---"
curl -s "${BASE}/devices" | pp
echo

echo "--- GET /diagnostics ---"
curl -s "${BASE}/diagnostics" | pp
echo

if [[ "${LISTEN}" -eq 1 ]]; then
  echo "--- POST /start (device=auto) ---"
  curl -s -X POST "${BASE}/start" \
    -H 'Content-Type: application/json' \
    -d '{"device":"auto","model":"hey_jarvis","threshold":0.5,"engine":"openwakeword"}' | pp
  echo
  echo "--- GET /events (SSE, ~20s — say \"Hey Jarvis\" now) ---"
  curl -sN -m 20 "${BASE}/events" || true
  echo
  echo "--- POST /stop ---"
  curl -s -X POST "${BASE}/stop" | pp
  echo
fi

echo "[test] done."
