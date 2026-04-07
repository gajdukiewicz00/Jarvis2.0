#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="${JARVIS_VERIFY_ROOT_DIR:-${DEFAULT_ROOT}}"

PORT_FORWARD_PIDS=()

log() {
    echo "[verify-ai] $1"
}

warn() {
    echo "[verify-ai] WARN: $1" >&2
}

fail() {
    echo "[verify-ai] ERROR: $1" >&2
    exit 1
}

cleanup_port_forwards() {
    local pid
    for pid in "${PORT_FORWARD_PIDS[@]}"; do
        if kill -0 "${pid}" >/dev/null 2>&1; then
            kill "${pid}" >/dev/null 2>&1 || true
            wait "${pid}" 2>/dev/null || true
        fi
    done
}

trap cleanup_port_forwards EXIT

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "Missing dependency: $1"
}

check_file() {
    local path="$1"
    [[ -f "${path}" ]] || fail "Missing required file: ${path}"
}

check_dir() {
    local path="$1"
    [[ -d "${path}" ]] || fail "Missing required directory: ${path}"
}

is_truthy() {
    local value="${1:-}"
    case "${value,,}" in
        1|true|yes|on)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

is_k8s_available() {
    command -v kubectl >/dev/null 2>&1 && kubectl cluster-info --request-timeout=5s >/dev/null 2>&1
}

deployment_ready() {
    local deployment="$1"
    local namespace="${JARVIS_NAMESPACE:-jarvis}"
    local ready

    ready="$(kubectl -n "${namespace}" get deployment "${deployment}" \
        -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
    [[ "${ready}" =~ ^[0-9]+$ ]] || ready="0"
    [[ "${ready}" -gt 0 ]]
}

should_run_stack() {
    local mode="${1:-auto}"
    local deployment_a="$2"
    local deployment_b="$3"

    if is_truthy "${mode}"; then
        return 0
    fi

    case "${mode,,}" in
        0|false|no|off)
            return 1
            ;;
        auto|"")
            is_k8s_available && deployment_ready "${deployment_a}" && deployment_ready "${deployment_b}"
            ;;
        *)
            warn "Unknown stack mode '${mode}', skipping"
            return 1
            ;;
    esac
}

should_run_preflight() {
    local mode="${1:-auto}"

    if is_truthy "${mode}"; then
        return 0
    fi

    case "${mode,,}" in
        0|false|no|off)
            return 1
            ;;
        auto|"")
            is_k8s_available
            ;;
        *)
            warn "Unknown preflight mode '${mode}', skipping"
            return 1
            ;;
    esac
}

start_port_forward() {
    local service="$1"
    local local_port="$2"
    local remote_port="$3"
    local namespace="${JARVIS_NAMESPACE:-jarvis}"

    kubectl -n "${namespace}" get service "${service}" >/dev/null 2>&1 \
        || fail "Service not found: ${namespace}/${service}"

    kubectl -n "${namespace}" port-forward "svc/${service}" \
        "${local_port}:${remote_port}" >/dev/null 2>&1 &
    local pf_pid=$!
    PORT_FORWARD_PIDS+=("${pf_pid}")

    sleep 2
    kill -0 "${pf_pid}" >/dev/null 2>&1 \
        || fail "Failed to start port-forward for ${namespace}/${service}"
}

run_verify_prod() {
    local script="${PROJECT_ROOT}/scripts/verify-prod.sh"
    if [[ -x "${script}" ]]; then
        log "Running verify-prod"
        "${script}"
    else
        warn "verify-prod.sh not found under ${PROJECT_ROOT}; skipping prod verification"
    fi
}

run_k8s_preflight() {
    local mode="${JARVIS_VERIFY_PREFLIGHT:-auto}"
    local script="${PROJECT_ROOT}/scripts/ci/k8s-preflight.sh"

    if ! should_run_preflight "${mode}"; then
        log "k8s preflight skipped (JARVIS_VERIFY_PREFLIGHT=${mode})"
        return 0
    fi

    if [[ ! -x "${script}" ]]; then
        warn "k8s preflight script missing: ${script}"
        return 0
    fi

    log "Running k8s preflight"
    "${script}"
}

run_llm_smoke() {
    local llm_service_port="${JARVIS_ACCEPT_LLM_SERVICE_PORT:-18091}"
    local llm_server_port="${JARVIS_ACCEPT_LLM_SERVER_PORT:-15000}"
    local llm_service_url="${LLM_SERVICE_URL:-http://127.0.0.1:${llm_service_port}}"
    local llm_server_url="${LLM_SERVER_URL:-http://127.0.0.1:${llm_server_port}}"

    if is_k8s_available; then
        log "Setting up LLM port-forwards"
        start_port_forward "llm-service" "${llm_service_port}" "8091"
        start_port_forward "llm-server" "${llm_server_port}" "5000"
    fi

    log "Running llm-smoke"
    LLM_SERVICE_URL="${llm_service_url}" \
    LLM_SERVER_URL="${llm_server_url}" \
    "${PROJECT_ROOT}/scripts/llm-smoke.sh"
}

run_memory_smoke() {
    local memory_service_port="${JARVIS_ACCEPT_MEMORY_SERVICE_PORT:-18093}"
    local embedding_service_port="${JARVIS_ACCEPT_EMBEDDING_SERVICE_PORT:-15001}"
    local memory_url="${MEMORY_URL:-http://127.0.0.1:${memory_service_port}}"
    local embedding_url="${EMBEDDING_URL:-http://127.0.0.1:${embedding_service_port}}"

    if is_k8s_available; then
        log "Setting up Memory port-forwards"
        start_port_forward "memory-service" "${memory_service_port}" "8093"
        start_port_forward "embedding-service" "${embedding_service_port}" "5001"
    fi

    log "Running memory-smoke"
    MEMORY_URL="${memory_url}" \
    EMBEDDING_URL="${embedding_url}" \
    "${PROJECT_ROOT}/scripts/memory-smoke.sh"
}

run_optional_smokes() {
    local llm_mode="${JARVIS_VERIFY_LLM:-${JARVIS_ACCEPT_LLM:-auto}}"
    local memory_mode="${JARVIS_VERIFY_MEMORY:-${JARVIS_ACCEPT_MEMORY:-auto}}"

    if should_run_stack "${llm_mode}" "llm-service" "llm-server"; then
        run_llm_smoke
    else
        log "LLM smoke skipped (mode=${llm_mode})"
    fi

    if should_run_stack "${memory_mode}" "memory-service" "embedding-service"; then
        run_memory_smoke
    else
        log "Memory smoke skipped (mode=${memory_mode})"
    fi
}

check_required_paths() {
    check_file "${PROJECT_ROOT}/README.md"
    check_file "${PROJECT_ROOT}/ARCHITECTURE.md"
    check_file "${PROJECT_ROOT}/docs/architecture.md"
    check_file "${PROJECT_ROOT}/RUNBOOK_LLM.md"
    check_file "${PROJECT_ROOT}/scripts/acceptance-ai.sh"
    check_file "${PROJECT_ROOT}/scripts/product/jarvis-run-acceptance.sh"
    check_file "${PROJECT_ROOT}/scripts/setup-ai-local.sh"
    check_file "${PROJECT_ROOT}/scripts/ai-local-smoke.sh"
    check_file "${PROJECT_ROOT}/scripts/ai-gpu-smoke.sh"
    check_file "${PROJECT_ROOT}/scripts/llm-smoke.sh"
    check_file "${PROJECT_ROOT}/scripts/memory-smoke.sh"
    check_file "${PROJECT_ROOT}/apps/llm-service/src/main/resources/prompts/llm-orchestrator-system.txt"
    check_file "${PROJECT_ROOT}/apps/llm-service/src/main/resources/tools/registry.json"
    check_dir "${PROJECT_ROOT}/apps/llm-service/src/main/java/org/jarvis/llm/orchestrator"
}

check_llm_prompt_contract() {
    local prompt="${PROJECT_ROOT}/apps/llm-service/src/main/resources/prompts/llm-orchestrator-system.txt"
    local registry="${PROJECT_ROOT}/apps/llm-service/src/main/resources/tools/registry.json"

    if ! rg -q '\{\{TOOLS_JSON\}\}' "${prompt}"; then
        fail "System prompt missing {{TOOLS_JSON}} placeholder"
    fi

    local tool
    for tool in \
        create_todo \
        update_todo \
        list_todos \
        complete_todo \
        create_event \
        move_event \
        list_events \
        find_free_slot \
        list_transactions \
        summarize_month \
        analyze_spending \
        budget_status \
        search_memory; do
        if ! rg -q "\"name\"[[:space:]]*:[[:space:]]*\"${tool}\"" "${registry}"; then
            fail "Tool registry missing tool: ${tool}"
        fi
    done
}

check_tool_api_contract() {
    local tool_todo="${PROJECT_ROOT}/apps/planner-service/src/main/java/org/jarvis/planner/controller/ToolTodoController.java"
    local tool_calendar="${PROJECT_ROOT}/apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolCalendarController.java"
    local tool_finance="${PROJECT_ROOT}/apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolFinanceController.java"
    local tool_memory="${PROJECT_ROOT}/apps/memory-service/src/main/java/org/jarvis/memory/controller/ToolMemoryController.java"

    check_file "${tool_todo}"
    check_file "${tool_calendar}"
    check_file "${tool_finance}"
    check_file "${tool_memory}"

    check_file "${PROJECT_ROOT}/apps/planner-service/src/main/java/org/jarvis/planner/tooling/ToolUserIdFilter.java"
    check_file "${PROJECT_ROOT}/apps/life-tracker/src/main/java/org/jarvis/lifetracker/tooling/ToolUserIdFilter.java"
    check_file "${PROJECT_ROOT}/apps/memory-service/src/main/java/org/jarvis/memory/tooling/ToolUserIdFilter.java"
    check_file "${PROJECT_ROOT}/apps/planner-service/src/main/java/org/jarvis/planner/tooling/ToolRequestCleanup.java"
    check_file "${PROJECT_ROOT}/apps/life-tracker/src/main/java/org/jarvis/lifetracker/tooling/ToolRequestCleanup.java"

    if ! rg -q 'X-Idempotency-Key' "${tool_todo}"; then
        fail "ToolTodoController must require X-Idempotency-Key for mutations"
    fi
    if ! rg -q 'X-Idempotency-Key' "${tool_calendar}"; then
        fail "ToolCalendarController must require X-Idempotency-Key for mutations"
    fi

    local controller
    for controller in "${tool_todo}" "${tool_calendar}" "${tool_finance}" "${tool_memory}"; do
        if ! rg -q '@RequestAttribute\("toolUserId"\)' "${controller}"; then
            fail "Tool controller missing toolUserId attribute: ${controller}"
        fi
    done

    if ! rg -q '@EnableScheduling' "${PROJECT_ROOT}/apps/planner-service/src/main/java/org/jarvis/planner/PlannerServiceApplication.java"; then
        fail "PlannerServiceApplication must enable scheduling for tooling cleanup"
    fi
    if ! rg -q '@EnableScheduling' "${PROJECT_ROOT}/apps/life-tracker/src/main/java/org/jarvis/lifetracker/LifeTrackerApplication.java"; then
        fail "LifeTrackerApplication must enable scheduling for tooling cleanup"
    fi
}

check_orchestrator_isolation() {
    local orchestrator_dir="${PROJECT_ROOT}/apps/llm-service/src/main/java/org/jarvis/llm/orchestrator"

    if rg -n 'JpaRepository|JdbcTemplate|RestTemplate|WebClient|MemoryClient' "${orchestrator_dir}" >/dev/null; then
        rg -n 'JpaRepository|JdbcTemplate|RestTemplate|WebClient|MemoryClient' "${orchestrator_dir}" >&2
        fail "LLM orchestrator package must not call persistence or HTTP clients directly"
    fi
}

check_repo_hygiene() {
    if ! git -C "${PROJECT_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        return 0
    fi

    local tracked_files=()
    while IFS= read -r -d '' path; do
        [[ "${path}" == docs/* ]] && continue
        [[ "${path}" == scripts/verify-ai.sh ]] && continue
        [[ "${path}" == *application-dev.yaml ]] && continue
        [[ "${path}" == *application-dev.yml ]] && continue
        [[ "${path}" == *application-docker.yml ]] && continue
        [[ -f "${PROJECT_ROOT}/${path}" ]] || continue

        if [[ "${path}" == .env* ]]; then
            fail "Tracked .env files are not allowed: ${path}"
        fi
        tracked_files+=("${PROJECT_ROOT}/${path}")
    done < <(git -C "${PROJECT_ROOT}" ls-files -z)

    if ((${#tracked_files[@]} == 0)); then
        return 0
    fi

    local bash_matches
    bash_matches="$(rg -n '/usr/bin/bash' "${tracked_files[@]}" || true)"
    if [[ -n "${bash_matches}" ]]; then
        echo "${bash_matches}" >&2
        fail "Hardcoded /usr/bin/bash found in tracked files"
    fi

    local secret_matches
    secret_matches="$(rg -n \
        'BEGIN (RSA|EC|PRIVATE) KEY|PRIVATE KEY|Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9._-]{16,}|(password|token|secret|api[_-]?key)[[:space:]]*[:=][[:space:]]*["'"'"'"][^$"'"'"'[:space:]][^"'"'"']{7,}["'"'"'"]|aws_access_key|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}' \
        "${tracked_files[@]}" || true)"
    if [[ -n "${secret_matches}" ]]; then
        echo "${secret_matches}" >&2
        fail "Potential secrets detected in tracked files"
    fi
}

main() {
    require_cmd rg
    require_cmd grep

    run_verify_prod
    check_required_paths
    check_llm_prompt_contract
    check_tool_api_contract
    check_orchestrator_isolation
    check_repo_hygiene
    run_k8s_preflight
    run_optional_smokes

    echo "[verify-ai] OK"
}

main "$@"
