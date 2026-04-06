#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

json_get() {
    local url="$1"
    shift
    curl -fsS "$@" "${url}"
}

json_post() {
    local url="$1"
    local payload="$2"
    shift 2
    curl -fsS "$@" \
        -H "Content-Type: application/json" \
        -X POST \
        -d "${payload}" \
        "${url}"
}

assert_json() {
    local payload="$1"
    local script="$2"
    python3 - "${payload}" <<PY
import json
import sys
payload = json.loads(sys.argv[1])
${script}
PY
}

service_token() {
    python3 "${ROOT_DIR}/scripts/runtime/make_service_jwt.py" \
        --secret "${SERVICE_JWT_SECRET}" \
        --subject "ai-gpu-smoke" \
        --service "ai-gpu-smoke"
}

GPU_SMOKE_STATUS="failed"
GPU_SMOKE_REASON="ai-gpu-smoke did not complete"
GPU_EFFECTIVE_DEVICE=""
GPU_EFFECTIVE_GPU_LAYERS=""
GPU_LLAMA_CPP_VERSION=""
GPU_DRIVER_VERSION=""
GPU_NAME=""
GPU_PROFILE=""
export GPU_SMOKE_STATUS GPU_SMOKE_REASON GPU_EFFECTIVE_DEVICE GPU_EFFECTIVE_GPU_LAYERS
export GPU_LLAMA_CPP_VERSION GPU_DRIVER_VERSION GPU_NAME GPU_PROFILE
export JARVIS_AI_GPU_STATUS_FILE="${JARVIS_AI_GPU_STATUS_FILE:-$(runtime_local_ai_gpu_status_file)}"

write_gpu_status_file() {
    python3 - "${JARVIS_AI_GPU_STATUS_FILE}" <<'PY'
import json
import os
import pathlib
from datetime import datetime, timezone
import sys

target = pathlib.Path(sys.argv[1])
target.parent.mkdir(parents=True, exist_ok=True)
payload = {
    "status": os.environ.get("GPU_SMOKE_STATUS", "unknown"),
    "reason": os.environ.get("GPU_SMOKE_REASON", ""),
    "verifiedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "profile": os.environ.get("GPU_PROFILE", ""),
    "packageSpec": os.environ.get("JARVIS_LLAMACPP_PACKAGE_SPEC", ""),
    "modelId": os.environ.get("JARVIS_LLM_MODEL_ID", ""),
    "modelPath": os.environ.get("JARVIS_LLM_MODEL_PATH", ""),
    "configuredDevicePath": "cuda" if os.environ.get("N_GPU_LAYERS", "0") != "0" else "cpu",
    "configuredGpuLayers": int(os.environ.get("N_GPU_LAYERS", "0")),
    "effectiveDevicePath": os.environ.get("GPU_EFFECTIVE_DEVICE", ""),
    "effectiveGpuLayers": int(os.environ["GPU_EFFECTIVE_GPU_LAYERS"])
    if os.environ.get("GPU_EFFECTIVE_GPU_LAYERS")
    else None,
    "llamaCppPythonVersion": os.environ.get("GPU_LLAMA_CPP_VERSION", ""),
    "driverVersion": os.environ.get("GPU_DRIVER_VERSION", ""),
    "gpuName": os.environ.get("GPU_NAME", ""),
    "smokeScript": "scripts/ai-gpu-smoke.sh",
}
target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

restore_cpu_baseline() {
    log "Restoring canonical CPU AI baseline..."
    local cpu_health=""
    (
        export ENABLE_LLM="true"
        export JARVIS_LLM_ENABLED="true"
        export ENABLE_MEMORY="true"
        export MEMORY_SERVICE_ENABLED="true"
        export JARVIS_LLM_MANAGED_SERVER="true"
        export N_GPU_LAYERS="0"
        "${ROOT_DIR}/scripts/runtime-down.sh" >/dev/null 2>&1 || true
        "${ROOT_DIR}/scripts/setup-ai-local.sh" >/dev/null
        "${ROOT_DIR}/scripts/runtime-up.sh" >/dev/null

        cpu_health="$(json_get "${LLM_SERVER_URL}/health")"
        assert_json "${cpu_health}" '
assert payload["status"] == "healthy", payload
assert payload["effective_device"] == "cpu", payload
assert payload["effective_n_gpu_layers"] == 0, payload
'
    ) || warn "Failed to restore canonical CPU baseline automatically"
}

cleanup() {
    local exit_code=$?
    write_gpu_status_file || warn "Failed to write GPU readiness file ${JARVIS_AI_GPU_STATUS_FILE}"
    restore_cpu_baseline
    exit "${exit_code}"
}
trap cleanup EXIT

export ENABLE_LLM="true"
export JARVIS_LLM_ENABLED="true"
export ENABLE_MEMORY="true"
export MEMORY_SERVICE_ENABLED="true"
export JARVIS_LLM_MANAGED_SERVER="true"
export JARVIS_LLAMACPP_PACKAGE_SPEC="${JARVIS_LLAMACPP_PACKAGE_SPEC:-llama-cpp-python==0.3.19}"
export N_GPU_LAYERS="${N_GPU_LAYERS:--1}"
export N_CTX="${N_CTX:-4096}"
export N_BATCH="${N_BATCH:-512}"
export N_THREADS="${N_THREADS:-$(default_llm_threads)}"

if ! is_truthy "${JARVIS_AI_SKIP_SETUP:-false}"; then
    log "Running AI setup for GPU smoke..."
    "${ROOT_DIR}/scripts/setup-ai-local.sh"
else
    ensure_local_env
fi

[[ -f "${JARVIS_LLM_MODEL_PATH}" ]] || fail "Canonical LLM model is missing at ${JARVIS_LLM_MODEL_PATH}"

log "Starting local runtime with GPU offload profile..."
"${ROOT_DIR}/scripts/runtime-down.sh" >/dev/null 2>&1 || true
"${ROOT_DIR}/scripts/runtime-up.sh"

SERVICE_TOKEN="$(service_token)"
DIRECT_HEADERS=(
    -H "Authorization: Bearer ${SERVICE_TOKEN}"
    -H "X-User-Id: ai-gpu-smoke-user"
    -H "X-User-Roles: USER"
)

log "Checking llm-server GPU health..."
LLM_SERVER_HEALTH="$(json_get "${LLM_SERVER_URL}/health")"
assert_json "${LLM_SERVER_HEALTH}" '
assert payload["status"] == "healthy", payload
assert payload["backend"] == "llamacpp", payload
assert payload["model_loaded"] is True, payload
assert payload["effective_device"] == "cuda", payload
assert payload["configured_n_gpu_layers"] == int("'"${N_GPU_LAYERS}"'"), payload
assert payload["effective_n_gpu_layers"] not in (None, 0), payload
'

read -r GPU_EFFECTIVE_DEVICE GPU_EFFECTIVE_GPU_LAYERS GPU_DRIVER_VERSION GPU_NAME < <(
    python3 - "${LLM_SERVER_HEALTH}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
diagnostics = payload.get("diagnostics") or {}
print(
    payload.get("effective_device", ""),
    diagnostics.get("effective_n_gpu_layers", ""),
    diagnostics.get("gpu_driver_version", ""),
    diagnostics.get("gpu_name", "").replace(" ", "_"),
)
PY
)
GPU_NAME="${GPU_NAME//_/ }"

log "Reading llama-cpp-python version from managed llm-server venv..."
LLM_SERVER_VENV="$(python_service_venv_dir "llm-server")"
CUDA_LIBRARY_PATH="$(llm_cuda_library_path "${LLM_SERVER_VENV}" 2>/dev/null || true)"
GPU_LLAMA_CPP_VERSION="$(
    LD_LIBRARY_PATH="${CUDA_LIBRARY_PATH}:${LD_LIBRARY_PATH:-}" \
        "${LLM_SERVER_VENV}/bin/python" - <<'PY'
import llama_cpp
print(getattr(llama_cpp, "__version__", "unknown"))
PY
)"
GPU_PROFILE="${JARVIS_LLAMACPP_PACKAGE_SPEC} + N_GPU_LAYERS=${N_GPU_LAYERS}"

log "Checking direct llm-server chat on GPU..."
GPU_CHAT_PAYLOAD='{"messages":[{"role":"system","content":"Answer briefly."},{"role":"user","content":"Reply with the single word ready."}],"max_tokens":8,"temperature":0.1}'
GPU_CHAT_RESPONSE="$(json_post "${LLM_SERVER_URL}/api/v1/llm/chat" "${GPU_CHAT_PAYLOAD}")"
assert_json "${GPU_CHAT_RESPONSE}" '
reply = payload["reply"].strip().lower()
assert reply == "ready", payload
'

log "Checking llm-service runtime truth during GPU mode..."
LLM_RUNTIME="$(json_get "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/runtime" \
    -H "Authorization: Bearer ${SERVICE_TOKEN}")"
assert_json "${LLM_RUNTIME}" '
assert payload["status"] == "ready", payload
assert payload["llm"]["configuredDevicePath"] == "cuda", payload["llm"]
assert payload["llm"]["effectiveDevicePath"] == "cuda", payload["llm"]
assert payload["llm"]["configuredGpuLayers"] == int("'"${N_GPU_LAYERS}"'"), payload["llm"]
assert payload["llm"]["effectiveGpuLayers"] not in (None, 0), payload["llm"]
'

log "Checking llm-service chat path on GPU..."
LLM_SERVICE_CHAT_PAYLOAD='{"sessionId":"ai-gpu-smoke-session","messages":[{"role":"USER","content":"Reply with the single word ready."}],"maxTokens":8,"temperature":0.1}'
LLM_SERVICE_CHAT="$(json_post "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/chat" \
    "${LLM_SERVICE_CHAT_PAYLOAD}" "${DIRECT_HEADERS[@]}")"
assert_json "${LLM_SERVICE_CHAT}" '
reply = payload["reply"].strip().lower()
assert "ready" in reply, payload
'

log "Ensuring runtime stays alive after GPU smoke..."
LLM_SERVER_HEALTH_AFTER="$(json_get "${LLM_SERVER_URL}/health")"
assert_json "${LLM_SERVER_HEALTH_AFTER}" '
assert payload["status"] == "healthy", payload
assert payload["effective_device"] == "cuda", payload
assert payload["effective_n_gpu_layers"] not in (None, 0), payload
'

GPU_SMOKE_STATUS="verified"
GPU_SMOKE_REASON=""
write_gpu_status_file

log "Checking llm-service runtime truth after GPU verification marker..."
LLM_RUNTIME_VERIFIED="$(json_get "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/runtime" \
    -H "Authorization: Bearer ${SERVICE_TOKEN}")"
assert_json "${LLM_RUNTIME_VERIFIED}" '
assert payload["gpu"]["readinessStatus"] == "verified", payload["gpu"]
assert payload["gpu"]["verifiedDevicePath"] == "cuda", payload["gpu"]
assert payload["gpu"]["verifiedGpuLayers"] not in (None, 0), payload["gpu"]
'

log "AI GPU smoke passed."
