#!/usr/bin/env bash
#
# e2e-common.sh — shared helpers for scripts/e2e-*.sh scenario scripts.
#
# Conventions:
#   * Target gateway: $JARVIS_GATEWAY_URL (default http://localhost:8080). This
#     defaults to a LOCAL gateway on purpose — e2e scripts never touch a remote
#     or production cluster unless you point them at one explicitly.
#   * Auth: $JARVIS_SMOKE_TOKEN, or $JARVIS_SMOKE_USER + $JARVIS_SMOKE_PASS to
#     auto-login. Without a token, auth-gated scenarios SKIP rather than fail.
#
if [[ -n "${JARVIS_E2E_COMMON_LOADED:-}" ]]; then return 0; fi
JARVIS_E2E_COMMON_LOADED=1

GATEWAY_URL="${JARVIS_GATEWAY_URL:-http://localhost:8080}"

# Virtual host for the ingress. The k3s ingress routes by Host header, so when the
# gateway is reached over an IP (or any non-localhost host) we must send the vhost.
# Explicit $JARVIS_GATEWAY_HOST always wins; otherwise default to api.jarvis.local for
# non-local targets and stay empty for localhost so bare-gateway mode is unchanged.
GATEWAY_HOST="${JARVIS_GATEWAY_HOST:-}"
if [[ -z "${GATEWAY_HOST}" ]]; then
  case "${GATEWAY_URL}" in
    *localhost*|*127.0.0.1*) GATEWAY_HOST="" ;;
    *) GATEWAY_HOST="api.jarvis.local" ;;
  esac
fi

c_green=$'\033[0;32m'; c_red=$'\033[0;31m'; c_yellow=$'\033[0;33m'; c_reset=$'\033[0m'
[[ -t 1 ]] || { c_green=""; c_red=""; c_yellow=""; c_reset=""; }

e2e_pass() { printf '%s✓ PASS%s %s\n' "${c_green}" "${c_reset}" "$1"; }
e2e_fail() { printf '%s✗ FAIL%s %s\n' "${c_red}" "${c_reset}" "$1"; E2E_FAILED=1; }
e2e_skip() { printf '%s- SKIP%s %s%s%s\n' "${c_yellow}" "${c_reset}" "$1" "${2:+ — }" "${2:-}"; E2E_SKIPPED=1; }
e2e_info() { printf '  %s\n' "$1"; }
e2e_step() { printf '\n%s» %s%s\n' "${c_yellow}" "$1" "${c_reset}"; }

e2e_require_curl() {
  command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }
}

# Resolve a bearer token, echoing it on stdout. Returns 1 if none available.
E2E_TOKEN_CACHE=""
e2e_token() {
  if [[ -n "${E2E_TOKEN_CACHE}" ]]; then printf '%s' "${E2E_TOKEN_CACHE}"; return 0; fi
  if [[ -n "${JARVIS_SMOKE_TOKEN:-}" ]]; then
    E2E_TOKEN_CACHE="${JARVIS_SMOKE_TOKEN}"; printf '%s' "${E2E_TOKEN_CACHE}"; return 0
  fi
  if [[ -n "${JARVIS_SMOKE_USER:-}" && -n "${JARVIS_SMOKE_PASS:-}" ]]; then
    local body hostargs=()
    [[ -n "${GATEWAY_HOST}" ]] && hostargs=(-H "Host: ${GATEWAY_HOST}")
    body="$(curl -ksS --max-time 6 -X POST "${GATEWAY_URL}/api/v1/security/auth/login" \
      ${hostargs[@]+"${hostargs[@]}"} \
      -H 'Content-Type: application/json' \
      -d "{\"username\":\"${JARVIS_SMOKE_USER}\",\"password\":\"${JARVIS_SMOKE_PASS}\"}" 2>/dev/null)"
    E2E_TOKEN_CACHE="$(printf '%s' "${body}" | grep -oE '"accessToken"[: ]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
    [[ -n "${E2E_TOKEN_CACHE}" ]] && { printf '%s' "${E2E_TOKEN_CACHE}"; return 0; }
  fi
  return 1
}

# e2e_api METHOD PATH [JSON_BODY] -> prints "<http_code>\n<body>"; uses token if available.
e2e_api() {
  local method="$1" path="$2" body="${3:-}"
  local token; token="$(e2e_token 2>/dev/null || true)"
  local args=(-ksS --max-time 10 -X "${method}" -w $'\n%{http_code}')
  [[ -n "${GATEWAY_HOST}" ]] && args+=(-H "Host: ${GATEWAY_HOST}")
  [[ -n "${token}" ]] && args+=(-H "Authorization: Bearer ${token}")
  if [[ -n "${body}" ]]; then args+=(-H 'Content-Type: application/json' -d "${body}"); fi
  curl "${args[@]}" "${GATEWAY_URL}${path}" 2>/dev/null
}

# Split the combined output of e2e_api into globals E2E_BODY / E2E_CODE.
e2e_split() {
  local combined="$1"
  E2E_CODE="$(printf '%s' "${combined}" | tail -n1)"
  E2E_BODY="$(printf '%s' "${combined}" | sed '$d')"
}

e2e_finish() {
  echo
  if [[ "${E2E_FAILED:-0}" == "1" ]]; then
    printf '%sE2E scenario FAILED%s\n' "${c_red}" "${c_reset}"; exit 1
  fi
  if [[ "${E2E_SKIPPED:-0}" == "1" ]]; then
    printf '%sE2E scenario completed with skips (prerequisites missing)%s\n' "${c_yellow}" "${c_reset}"; exit 0
  fi
  printf '%sE2E scenario PASSED%s\n' "${c_green}" "${c_reset}"; exit 0
}
