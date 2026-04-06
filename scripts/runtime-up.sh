#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env

if is_truthy "${ENABLE_LLM:-false}" && is_truthy "${JARVIS_LLM_MANAGED_SERVER:-true}"; then
    resolve_llm_model_path >/dev/null
fi

maybe_build_services
ensure_local_postgres

start_service "security-service"
wait_for_service "security-service"

start_service "user-profile"
wait_for_service "user-profile"

start_service "nlp-service"
wait_for_service "nlp-service"

start_service "orchestrator"
wait_for_service "orchestrator"

start_service "voice-gateway"
wait_for_service "voice-gateway"

start_service "pc-control"
wait_for_service "pc-control"

start_service "smart-home-service"
wait_for_service "smart-home-service"

start_service "life-tracker"
wait_for_service "life-tracker"

start_service "analytics-service"
wait_for_service "analytics-service"

start_service "api-gateway"
wait_for_service "api-gateway"

start_service "planner-service"
wait_for_service "planner-service"

if is_truthy "${ENABLE_LLM:-false}"; then
    if is_truthy "${JARVIS_LLM_MANAGED_SERVER:-true}"; then
        start_python_service "llm-server"
        wait_for_service "llm-server" 600
    else
        log "Using externally managed LLM backend at ${LLM_SERVER_URL}"
        wait_for_http_health "external llm-server" "${LLM_SERVER_URL}/health" '"status"[[:space:]]*:[[:space:]]*"healthy"' 180 \
            || fail "External LLM backend did not become healthy at ${LLM_SERVER_URL}/health"
    fi
fi

if is_truthy "${ENABLE_MEMORY:-false}"; then
    start_python_service "embedding-service"
    wait_for_service "embedding-service" 180

    start_service "memory-service"
    wait_for_service "memory-service" 180
fi

if is_truthy "${ENABLE_LLM:-false}"; then
    start_service "llm-service"
    wait_for_service "llm-service"
fi

write_run_summary "ready"
log "Local Jarvis runtime is ready."
log "API Gateway: ${JARVIS_API_BASE_URL}"
log "Voice WebSocket: $(runtime_voice_ws_url)"
