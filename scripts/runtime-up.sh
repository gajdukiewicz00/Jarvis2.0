#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env
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
    start_service "llm-service"
    wait_for_service "llm-service"
fi

write_run_summary "ready"
log "Local Jarvis runtime is ready."
log "API Gateway: http://127.0.0.1:8080"
log "Voice WebSocket: ws://127.0.0.1:8080/ws/voice"
