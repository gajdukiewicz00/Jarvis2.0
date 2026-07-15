#!/usr/bin/env bash
# =============================================================================
# run-wakeword-openwakeword.sh
# -----------------------------------------------------------------------------
# Activate the .venv-wakeword venv and run the openWakeWord sidecar under
# uvicorn, bound to 127.0.0.1. Honors JARVIS_WAKEWORD_* env vars.
#
#   JARVIS_WAKEWORD_PORT       (default 18095)
#   JARVIS_WAKEWORD_HOST       (default 127.0.0.1)
#   JARVIS_WAKEWORD_DEVICE     (default auto)
#   JARVIS_WAKEWORD_MODEL      (default hey_jarvis)
#   JARVIS_WAKEWORD_THRESHOLD  (default 0.5)
#   JARVIS_WAKEWORD_ENGINE     (default openwakeword)
#   JARVIS_WAKEWORD_FRAMEWORK  (default onnx)
#   JARVIS_WAKEWORD_AUTOSTART  (default 0; set 1 to begin capture on boot)
# =============================================================================
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="${REPO_ROOT}/.venv-wakeword"
APP_DIR="${REPO_ROOT}/apps/wake-word-service"

PORT="${JARVIS_WAKEWORD_PORT:-18095}"
HOST="${JARVIS_WAKEWORD_HOST:-127.0.0.1}"

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  echo "[run][err] venv not found at ${VENV_DIR}" >&2
  echo "[run] Run first: bash scripts/setup-wakeword-openwakeword.sh" >&2
  exit 1
fi

# shellcheck disable=SC1091
source "${VENV_DIR}/bin/activate"

# Export the defaults so the app + uvicorn pick them up.
export JARVIS_WAKEWORD_PORT="${PORT}"
export JARVIS_WAKEWORD_HOST="${HOST}"
export JARVIS_WAKEWORD_DEVICE="${JARVIS_WAKEWORD_DEVICE:-auto}"
export JARVIS_WAKEWORD_MODEL="${JARVIS_WAKEWORD_MODEL:-hey_jarvis}"
export JARVIS_WAKEWORD_THRESHOLD="${JARVIS_WAKEWORD_THRESHOLD:-0.5}"
export JARVIS_WAKEWORD_ENGINE="${JARVIS_WAKEWORD_ENGINE:-openwakeword}"
export JARVIS_WAKEWORD_FRAMEWORK="${JARVIS_WAKEWORD_FRAMEWORK:-onnx}"
export JARVIS_WAKEWORD_AUTOSTART="${JARVIS_WAKEWORD_AUTOSTART:-0}"

echo "[run] Jarvis wake-word sidecar → http://${HOST}:${PORT}"
echo "[run] engine=${JARVIS_WAKEWORD_ENGINE} model=${JARVIS_WAKEWORD_MODEL} device=${JARVIS_WAKEWORD_DEVICE} threshold=${JARVIS_WAKEWORD_THRESHOLD}"

cd "${APP_DIR}"
exec uvicorn openwakeword_server:app --host "${HOST}" --port "${PORT}" --log-level info
