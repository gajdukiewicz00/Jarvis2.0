#!/usr/bin/env bash
# =============================================================================
# setup-wakeword-openwakeword.sh
# -----------------------------------------------------------------------------
# Create/refresh the local Python venv for the Jarvis openWakeWord sidecar,
# install dependencies, download the pretrained "hey_jarvis" model, and run a
# short health self-test. Idempotent: reuses an existing .venv-wakeword.
#
# No Picovoice / Porcupine account or key is involved.
# =============================================================================
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="${REPO_ROOT}/.venv-wakeword"
PY="python3"
FRAMEWORK="${JARVIS_WAKEWORD_FRAMEWORK:-onnx}"

log()  { printf '\033[1;36m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup][warn]\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m[setup][err]\033[0m %s\n' "$*" >&2; }

# --- 1. venv -----------------------------------------------------------------
if [[ -x "${VENV_DIR}/bin/python" ]]; then
  log "Reusing existing venv at ${VENV_DIR}"
else
  log "Creating venv at ${VENV_DIR}"
  "${PY}" -m venv "${VENV_DIR}" || { err "venv creation failed"; exit 1; }
fi

# shellcheck disable=SC1091
source "${VENV_DIR}/bin/activate"
VPY="${VENV_DIR}/bin/python"

log "Python: $(${VPY} --version 2>&1)"

# --- 2. dependencies ---------------------------------------------------------
log "Upgrading pip/setuptools/wheel"
"${VPY}" -m pip install --quiet --upgrade pip setuptools wheel || warn "pip upgrade had issues"

log "Installing core dependencies (openwakeword, sounddevice, numpy, fastapi, uvicorn, onnxruntime)"
"${VPY}" -m pip install \
  "openwakeword" \
  "sounddevice" \
  "numpy<2" \
  "fastapi" \
  "uvicorn[standard]" \
  "onnxruntime" \
  || { err "core dependency install failed"; exit 1; }

# tflite-runtime is optional; openWakeWord runs fine on the onnx framework.
if [[ "${FRAMEWORK}" == "tflite" ]]; then
  log "Attempting tflite-runtime (framework=tflite requested)"
  "${VPY}" -m pip install "tflite-runtime" \
    || warn "tflite-runtime unavailable; will fall back to onnx at runtime"
fi

# vosk is best-effort (optional Russian/English phrase spotter).
log "Attempting vosk (best-effort, optional)"
"${VPY}" -m pip install "vosk" \
  && log "vosk installed" \
  || warn "vosk not installed — vosk engine will report vosk_not_installed (openwakeword still works)"

# --- 3. verify pretrained models --------------------------------------------
# openWakeWord 0.4.x ships the pretrained wake models (hey_jarvis) and the
# feature models (melspectrogram/embedding) bundled inside the wheel as .onnx,
# so there is nothing to download. Older versions exposed download_models();
# handle both so this stays forward/backward compatible.
log "Verifying openWakeWord pretrained models (hey_jarvis + feature models)"
"${VPY}" - <<'PY' || { err "model verification failed"; exit 1; }
import os, openwakeword
# Best-effort download for versions that support it (no-op on 0.4.x).
u = getattr(openwakeword, "utils", None)
if u is not None and hasattr(u, "download_models"):
    try:
        u.download_models(model_names=["hey_jarvis"])
        print("[setup] download_models() invoked")
    except Exception as exc:
        print(f"[setup] download_models note: {exc}")
paths = openwakeword.get_pretrained_model_paths()
hey = [p for p in paths if "hey_jarvis" in os.path.basename(p).lower()]
if not hey:
    raise SystemExit("[setup] hey_jarvis model not found in pretrained paths")
print(f"[setup] hey_jarvis model present: {hey[0]}")
PY

# --- 4. list input devices ---------------------------------------------------
log "Enumerating input devices (preferred first)"
"${VPY}" - <<'PY' || warn "device enumeration failed (no audio session?)"
import sys, os
sys.path.insert(0, os.path.join(os.getcwd(), "apps", "wake-word-service"))
try:
    from openwakeword_server import enumerate_input_devices
    listing = enumerate_input_devices()
    devs = listing["devices"]
    if not devs:
        print("[setup]   (no input-capable devices detected)")
    for d in devs:
        star = "*" if d["preferred"] else " "
        print(f"[setup]  {star} id={d['id']:<3} {d['name']}")
    if listing["rejected"]:
        print("[setup]   rejected (output/monitor):")
        for r in listing["rejected"]:
            print(f"[setup]     - {r['name']} ({r['reason']})")
except Exception as exc:
    print(f"[setup]   device enumeration error: {exc}")
PY

# --- 5. health self-test -----------------------------------------------------
log "Health self-test: import openwakeword, construct Model(), predict one frame"
"${VPY}" - "${FRAMEWORK}" <<'PY' || { err "health self-test FAILED"; exit 1; }
import sys, os
framework = sys.argv[1] if len(sys.argv) > 1 else "onnx"
sys.path.insert(0, os.path.join(os.getcwd(), "apps", "wake-word-service"))
import numpy as np
# Use the sidecar's own engine wrapper so the self-test exercises real code.
from openwakeword_server import OpenWakeWordEngine
eng = OpenWakeWordEngine("hey_jarvis", framework)
# feed one silent 80ms frame to prove the pipeline runs end to end
score = eng.process(np.zeros(1280, dtype=np.int16))
print(f"[setup] OK — framework={eng.framework} models={eng.loaded_models()} "
      f"key={eng.model_key} silent_score={score:.4f}")
PY

log "Setup complete."
log "Run:  bash scripts/run-wakeword-openwakeword.sh"
log "Test: bash scripts/test-wakeword-openwakeword.sh"
