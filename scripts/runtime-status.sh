#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env

print_postgres_status() {
    local host port db
    read -r host port db < <(jdbc_host_port) || return 0
    local reachable="down"
    if python3 - "${host}" "${port}" <<'PY'
import socket
import sys

sock = socket.socket()
sock.settimeout(0.5)
try:
    sock.connect((sys.argv[1], int(sys.argv[2])))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
    then
        reachable="up"
    fi

    local managed="external"
    if docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1; then
        local state
        state="$(docker inspect -f '{{.State.Status}}' "${POSTGRES_CONTAINER}" 2>/dev/null || echo unknown)"
        managed="${POSTGRES_CONTAINER}:${state}"
    fi

    printf '%-18s host=%s:%s db=%s reachable=%s managed=%s\n' "postgres" "${host}" "${port}" "${db}" "${reachable}" "${managed}"
}

print_service_status() {
    local service="$1"
    local label="$2"
    local url
    url="$(service_health_url "${service}" 2>/dev/null || true)"
    local pid_status="stopped"
    local health_status="n/a"

    if service_is_running "${service}"; then
        pid_status="running"
    fi

    if [[ -n "${url}" ]]; then
        local body
        body="$(curl_with_runtime_tls "${url}" -fsS 2>/dev/null || true)"
        if [[ -n "${body}" ]]; then
            local pattern=""
            pattern="$(service_ready_pattern "${service}" 2>/dev/null || true)"
            if [[ -n "${pattern}" ]] && grep -Eq "${pattern}" <<<"${body}"; then
                health_status="ready"
            else
                health_status="reachable"
            fi
        else
            health_status="down"
        fi
    fi

    printf '%-18s pid=%-8s health=%s\n' "${label}" "${pid_status}" "${health_status}"
}

detect_model_path_nonfatal() {
    if [[ -n "${JARVIS_LLM_MODEL_PATH:-}" ]]; then
        printf '%s' "${JARVIS_LLM_MODEL_PATH}"
        [[ -f "${JARVIS_LLM_MODEL_PATH}" ]]
        return $?
    fi

    mapfile -t gguf_files < <(find "${JARVIS_LLM_MODEL_DIR}" -maxdepth 1 -type f -iname '*.gguf' | sort)
    if ((${#gguf_files[@]} == 1)); then
        printf '%s' "${gguf_files[0]}"
        return 0
    fi

    return 1
}

make_status_service_token() {
    if [[ -z "${SERVICE_JWT_SECRET:-}" ]] || [[ ! -f "${ROOT_DIR}/scripts/runtime/make_service_jwt.py" ]]; then
        return 1
    fi

    python3 "${ROOT_DIR}/scripts/runtime/make_service_jwt.py" \
        --secret "${SERVICE_JWT_SECRET}" \
        --subject "runtime-status" \
        --service "runtime-status" 2>/dev/null
}

print_ai_runtime_truth() {
    local token body
    token="$(make_status_service_token || true)"
    local -a auth_headers=()
    if [[ -n "${token}" ]]; then
        auth_headers=(-H "Authorization: Bearer ${token}")
    fi

    body="$(curl -fsS "${auth_headers[@]}" "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/runtime" 2>/dev/null || true)"
    [[ -n "${body}" ]] || return 0

    python3 - "${body}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
llm = payload.get("llm", {})
gpu = payload.get("gpu", {})
memory = payload.get("memory", {})
embedding = payload.get("embedding", {})
print("AI runtime truth:")
print(
    "  status={status} fullLocalAiReadiness={ready} llm.available={llm_available} memory.available={memory_available}".format(
        status=payload.get("status", "unknown"),
        ready=payload.get("fullLocalAiReadiness"),
        llm_available=llm.get("available"),
        memory_available=memory.get("available"),
    )
)
print(
    "  llm configured={configured} effective={effective} model={model} device={configured_device}->{effective_device} n_gpu_layers={configured_layers}->{effective_layers}".format(
        configured=llm.get("configuredProvider"),
        effective=llm.get("effectiveProvider"),
        model=llm.get("effectiveModel") or llm.get("configuredModel"),
        configured_device=llm.get("configuredDevicePath"),
        effective_device=llm.get("effectiveDevicePath"),
        configured_layers=llm.get("configuredGpuLayers"),
        effective_layers=llm.get("effectiveGpuLayers"),
    )
)
print(
    "  embedding configured={configured} effective={effective} model={model}".format(
        configured=embedding.get("configuredProvider"),
        effective=embedding.get("effectiveProvider"),
        model=embedding.get("effectiveModel") or embedding.get("configuredModel"),
    )
)
if gpu:
    cpu = gpu.get("canonicalCpuBaseline") or {}
    print(
        "  gpu availability={availability} readiness={status} reason={reason}".format(
            availability=gpu.get("available"),
            status=gpu.get("readinessStatus"),
            reason=gpu.get("readinessReason") or "-",
        )
    )
    print(
        "  gpu verifiedProfile={profile} verifiedAt={verified_at} cpuBaseline={device}/{layers}".format(
            profile=gpu.get("profile") or "-",
            verified_at=gpu.get("lastVerifiedAt") or "-",
            device=cpu.get("devicePath") or "-",
            layers=cpu.get("nGpuLayers"),
        )
    )
PY
}

printf 'Local runtime env: %s\n' "${LOCAL_ENV_FILE}"
printf 'API Gateway: %s\n' "${JARVIS_API_BASE_URL}"
printf 'LLM backend URL: %s\n' "${LLM_SERVER_URL}"
printf 'Memory enabled: %s\n' "${ENABLE_MEMORY}"
printf 'LLM enabled: %s\n' "${ENABLE_LLM}"
printf 'Canonical AI stack: %s\n' "${JARVIS_CANONICAL_LOCAL_AI_STACK:-unknown}"
printf 'Canonical LLM model: %s\n' "${JARVIS_LLM_MODEL_ID:-unknown}"
printf 'Canonical embedding model: %s\n' "${JARVIS_EMBEDDING_MODEL_ID:-unknown}"

if is_truthy "${ENABLE_LLM:-false}"; then
    if is_truthy "${JARVIS_LLM_MANAGED_SERVER:-true}"; then
        model_path="$(detect_model_path_nonfatal || true)"
        printf 'LLM model path: %s\n' "${model_path:-not resolved}"
    else
        printf 'LLM server mode: external\n'
    fi
fi

printf '\nCore services\n'
print_postgres_status
print_service_status "api-gateway" "api-gateway"
print_service_status "security-service" "security-service"
print_service_status "user-profile" "user-profile"
print_service_status "nlp-service" "nlp-service"
print_service_status "orchestrator" "orchestrator"
print_service_status "voice-gateway" "voice-gateway"
print_service_status "pc-control" "pc-control"
print_service_status "smart-home-service" "smart-home"
print_service_status "life-tracker" "life-tracker"
print_service_status "analytics-service" "analytics-service"
print_service_status "planner-service" "planner-service"

printf '\nAI services\n'
if is_truthy "${ENABLE_LLM:-false}"; then
    print_service_status "llm-server" "llm-server"
    print_service_status "llm-service" "llm-service"
else
    printf '%-18s %s\n' "llm-stack" "disabled"
fi

if is_truthy "${ENABLE_MEMORY:-false}"; then
    print_service_status "embedding-service" "embedding-service"
    print_service_status "memory-service" "memory-service"
else
    printf '%-18s %s\n' "memory-stack" "disabled"
fi

if is_truthy "${ENABLE_LLM:-false}" && service_is_running "llm-service"; then
    printf '\n'
    print_ai_runtime_truth
fi

printf '\nLogs: %s\n' "${LOG_DIR}"
