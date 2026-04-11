#!/usr/bin/env bash
#
# Start the AI services (llm-server, embedding-service, memory-service, llm-service)
# independently of the core runtime. Core runtime must already be running.
#
# Usage:
#   ./scripts/ai-up.sh
#   ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/ai-up.sh
#   N_GPU_LAYERS=-1 DEVICE=auto ./scripts/ai-up.sh  # GPU mode

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ENABLE_LLM="${ENABLE_LLM:-true}"
ENABLE_MEMORY="${ENABLE_MEMORY:-true}"

ensure_local_env

if ! is_truthy "${ENABLE_LLM}" && ! is_truthy "${ENABLE_MEMORY}"; then
    fail "Both ENABLE_LLM and ENABLE_MEMORY are disabled. Nothing to start. Set ENABLE_LLM=true and/or ENABLE_MEMORY=true."
fi

if ! service_is_running "api-gateway"; then
    log "WARNING: api-gateway is not running. AI services may not be reachable through the gateway."
    log "Consider running runtime-up.sh first for the full stack."
fi

AI_SERVICES_OK=0

if is_truthy "${ENABLE_LLM}"; then
    if is_truthy "${JARVIS_LLM_MANAGED_SERVER:-true}"; then
        resolve_llm_model_path >/dev/null
        if ! service_is_running "llm-server"; then
            start_python_service "llm-server"
            wait_for_service "llm-server" 600
        else
            log "llm-server is already running."
        fi
    else
        log "Using externally managed LLM backend at ${LLM_SERVER_URL}"
        wait_for_http_health "external llm-server" "${LLM_SERVER_URL}/health" '"status"[[:space:]]*:[[:space:]]*"healthy"' 180 \
            || fail "External LLM backend did not become healthy at ${LLM_SERVER_URL}/health"
    fi
    ((AI_SERVICES_OK+=1))
fi

if is_truthy "${ENABLE_MEMORY}"; then
    if ! service_is_running "embedding-service"; then
        start_python_service "embedding-service"
        wait_for_service "embedding-service" 180
    else
        log "embedding-service is already running."
    fi

    if ! service_is_running "memory-service"; then
        start_service "memory-service"
        wait_for_service "memory-service" 180
    else
        log "memory-service is already running."
    fi
    ((AI_SERVICES_OK+=1))
fi

if is_truthy "${ENABLE_LLM}"; then
    if ! service_is_running "llm-service"; then
        start_service "llm-service"
        wait_for_service "llm-service"
    else
        log "llm-service is already running."
    fi
    ((AI_SERVICES_OK+=1))
fi

if ((AI_SERVICES_OK == 0)); then
    fail "No AI services were started or confirmed running."
fi

log "AI services are ready."
