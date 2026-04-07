#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env

stop_service "llm-service"
stop_service "memory-service"
stop_service "embedding-service"
stop_service "llm-server"
stop_service "planner-service"
stop_service "analytics-service"
stop_service "life-tracker"
stop_service "smart-home-service"
stop_service "vision-security-service"
stop_service "pc-control"
stop_service "api-gateway"
stop_service "voice-gateway"
stop_service "orchestrator"
stop_service "nlp-service"
stop_service "user-profile"
stop_service "security-service"

if [[ -f "${RUNTIME_DIR}/llm-server-stub.pid" ]]; then
    stub_pid="$(cat "${RUNTIME_DIR}/llm-server-stub.pid" 2>/dev/null || true)"
    rm -f "${RUNTIME_DIR}/llm-server-stub.pid"
    if [[ -n "${stub_pid}" ]] && kill -0 "${stub_pid}" >/dev/null 2>&1; then
        log "Stopping LLM smoke stub (PID ${stub_pid})..."
        kill "${stub_pid}" >/dev/null 2>&1 || true
    fi
fi

if ! is_truthy "${JARVIS_KEEP_LOCAL_POSTGRES:-false}"; then
    stop_managed_postgres
fi

write_run_summary "stopped"
log "Local Jarvis runtime stopped."
