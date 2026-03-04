#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

log() {
  echo "[acceptance-ai] $1"
}

warn() {
  echo "[acceptance-ai] WARN: $1" >&2
}

fail() {
  echo "[acceptance-ai] ERROR: $1" >&2
  exit 1
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
  ready="$(kubectl -n "${namespace}" get deployment "${deployment}" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
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
      warn "Unknown mode '${mode}', skipping optional stack checks"
      return 1
      ;;
  esac
}

PORT_FORWARD_PIDS=()

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

start_port_forward() {
  local service="$1"
  local local_port="$2"
  local remote_port="$3"
  local namespace="${JARVIS_NAMESPACE:-jarvis}"

  kubectl -n "${namespace}" get service "${service}" >/dev/null 2>&1 || fail "Service not found: ${namespace}/${service}"
  kubectl -n "${namespace}" port-forward "svc/${service}" "${local_port}:${remote_port}" >/dev/null 2>&1 &
  local pf_pid=$!
  PORT_FORWARD_PIDS+=("${pf_pid}")

  sleep 2
  if ! kill -0 "${pf_pid}" >/dev/null 2>&1; then
    fail "Failed to start port-forward for ${namespace}/${service} (${local_port}:${remote_port})"
  fi
}

run_llm_smoke() {
  local llm_service_port="${JARVIS_ACCEPT_LLM_SERVICE_PORT:-18091}"
  local llm_server_port="${JARVIS_ACCEPT_LLM_SERVER_PORT:-15000}"
  local llm_service_url="${LLM_SERVICE_URL:-http://127.0.0.1:${llm_service_port}}"
  local llm_server_url="${LLM_SERVER_URL:-http://127.0.0.1:${llm_server_port}}"

  if is_k8s_available; then
    log "Setting up LLM port-forward (llm-service, llm-server)"
    start_port_forward "llm-service" "${llm_service_port}" "8091"
    start_port_forward "llm-server" "${llm_server_port}" "5000"
  fi

  log "Running llm-smoke"
  LLM_SERVICE_URL="${llm_service_url}" LLM_SERVER_URL="${llm_server_url}" "${ROOT_DIR}/scripts/llm-smoke.sh"
}

run_memory_smoke() {
  local memory_service_port="${JARVIS_ACCEPT_MEMORY_SERVICE_PORT:-18093}"
  local embedding_service_port="${JARVIS_ACCEPT_EMBEDDING_SERVICE_PORT:-15001}"
  local memory_url="${MEMORY_URL:-http://127.0.0.1:${memory_service_port}}"
  local embedding_url="${EMBEDDING_URL:-http://127.0.0.1:${embedding_service_port}}"

  if is_k8s_available; then
    log "Setting up Memory port-forward (memory-service, embedding-service)"
    start_port_forward "memory-service" "${memory_service_port}" "8093"
    start_port_forward "embedding-service" "${embedding_service_port}" "5001"
  fi

  log "Running memory-smoke"
  MEMORY_URL="${memory_url}" EMBEDDING_URL="${embedding_url}" "${ROOT_DIR}/scripts/memory-smoke.sh"
}

run_verify_suite() {
  local verify_ai="${ROOT_DIR}/scripts/verify-ai.sh"
  local llm_mode="${JARVIS_ACCEPT_LLM:-auto}"
  local memory_mode="${JARVIS_ACCEPT_MEMORY:-auto}"

  if [[ -x "${verify_ai}" ]]; then
    log "Running verify-ai"
    "${verify_ai}"
    return
  fi

  log "verify-ai.sh not found, running fallback verification suite"
  log "Running verify-prod"
  "${ROOT_DIR}/scripts/verify-prod.sh"

  if is_k8s_available; then
    log "Running k8s preflight"
    "${ROOT_DIR}/scripts/ci/k8s-preflight.sh"
  else
    log "k8s preflight skipped (kubectl cluster unavailable)"
  fi

  if should_run_stack "${llm_mode}" "llm-service" "llm-server"; then
    run_llm_smoke
  else
    log "LLM smoke skipped (JARVIS_ACCEPT_LLM=${llm_mode})"
  fi

  if should_run_stack "${memory_mode}" "memory-service" "embedding-service"; then
    run_memory_smoke
  else
    log "Memory smoke skipped (JARVIS_ACCEPT_MEMORY=${memory_mode})"
  fi
}

run_flyway_info() {
  if command -v flyway >/dev/null 2>&1; then
    if [[ -n "${FLYWAY_URL:-}" || -n "${FLYWAY_CONFIG_FILES:-}" ]]; then
      log "Running flyway info"
      flyway info || warn "flyway info failed"
    else
      log "flyway info skipped (set FLYWAY_URL or FLYWAY_CONFIG_FILES to enable)"
    fi
  else
    log "flyway info skipped (flyway not installed)"
  fi
}

require_internal_base_url() {
  local base_url="$1"
  if [[ -z "$base_url" ]]; then
    return
  fi
  if [[ "$base_url" == *"api.jarvis.local"* && "${JARVIS_ACCEPT_INGRESS:-}" != "1" ]]; then
    fail "Acceptance must use an internal port-forward URL. Set JARVIS_ACCEPT_INGRESS=1 to override."
  fi
}

run_ingress_tool_check() {
  local ingress_url="${JARVIS_INGRESS_URL:-}"
  if [[ -z "$ingress_url" ]]; then
    log "Ingress tool check skipped (set JARVIS_INGRESS_URL)"
    return
  fi
  if ! command -v curl >/dev/null 2>&1; then
    log "Ingress tool check skipped (curl not installed)"
    return
  fi

  local url="${ingress_url%/}/api/v1/tools/todo/list"
  local curl_args=(-sS --connect-timeout 2 -o /dev/null -w "%{http_code}")
  if [[ -n "${JARVIS_INGRESS_RESOLVE:-}" ]]; then
    curl_args+=(--resolve "${JARVIS_INGRESS_RESOLVE}")
  fi
  if [[ -n "${JARVIS_INGRESS_CA_CERT:-}" ]]; then
    curl_args+=(--cacert "${JARVIS_INGRESS_CA_CERT}")
  elif [[ "${JARVIS_INGRESS_INSECURE:-}" == "1" ]]; then
    curl_args+=(-k)
  fi

  local status
  status=$(curl "${curl_args[@]}" -H "Content-Type: application/json" -d '{}' "$url" || true)
  if [[ "$status" != "401" && "$status" != "403" ]]; then
    fail "Ingress tool check expected 401/403, got ${status:-unknown}"
  fi
  log "Ingress tool check passed (${status})"
}

run_idempotency_check() {
  local base_url="${JARVIS_API_BASE_URL:-}"
  local user_id="${JARVIS_USER_ID:-}"
  local resolve_entry="${JARVIS_API_RESOLVE:-}"
  local ca_cert="${JARVIS_CA_CERT:-}"
  local insecure="${JARVIS_INSECURE:-}"
  if [[ -z "$base_url" ]]; then
    log "Idempotency check skipped (set JARVIS_API_BASE_URL)"
    return
  fi
  require_internal_base_url "$base_url"
  if [[ -z "$user_id" ]]; then
    log "Idempotency check skipped (set JARVIS_USER_ID)"
    return
  fi
  if ! command -v curl >/dev/null 2>&1; then
    log "Idempotency check skipped (curl not installed)"
    return
  fi

  local key
  if command -v uuidgen >/dev/null 2>&1; then
    key=$(uuidgen)
  else
    key="acceptance-$(date +%s)"
  fi

  local url="${base_url%/}/api/v1/tools/todo/create"
  local payload='{"title":"Acceptance todo","priority":"LOW"}'

  log "Running idempotency check against $url"
  local curl_args=(-sS --connect-timeout 2)
  if [[ -n "$resolve_entry" ]]; then
    curl_args+=(--resolve "$resolve_entry")
  fi
  if [[ -n "$ca_cert" ]]; then
    curl_args+=(--cacert "$ca_cert")
  elif [[ "$insecure" == "1" ]]; then
    curl_args+=(-k)
  fi

  local resp1 resp2
  resp1=$(curl "${curl_args[@]}" -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" -H "X-Idempotency-Key: ${key}" \
    -d "$payload" "$url" || true)
  resp2=$(curl "${curl_args[@]}" -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" -H "X-Idempotency-Key: ${key}" \
    -d "$payload" "$url" || true)

  if [[ -n "$resp1" && "$resp1" == "$resp2" ]]; then
    log "Idempotency check passed"
  else
    fail "Idempotency check did not return stable responses"
  fi
}

run_flyway_info
run_ingress_tool_check
run_idempotency_check

run_verify_suite
