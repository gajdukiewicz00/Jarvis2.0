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

log "Running verify-ai"
"$ROOT_DIR/scripts/verify-ai.sh"
