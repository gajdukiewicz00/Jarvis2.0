#!/usr/bin/env bash
#
# E2E scenario 6: "Jarvis status report".
# User asks for a status report -> Jarvis returns OK/DEGRADED/BROKEN for every
# major subsystem via GET /api/v1/status/report (StatusReportController).
#
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/e2e-common.sh"
e2e_require_curl

e2e_step "Requesting cross-subsystem status report from ${GATEWAY_URL}"
if ! e2e_token >/dev/null 2>&1; then
  e2e_skip "status report" "no token (set JARVIS_SMOKE_TOKEN or JARVIS_SMOKE_USER/PASS)"
  e2e_finish
fi

e2e_split "$(e2e_api GET /api/v1/status/report)"
if [[ ! "${E2E_CODE}" =~ ^2 ]]; then
  if [[ "${E2E_CODE}" == "404" ]]; then
    e2e_skip "status report" "endpoint not deployed in this gateway build (HTTP 404)"
    e2e_finish
  fi
  e2e_fail "status report request (HTTP ${E2E_CODE})"
  e2e_finish
fi

e2e_pass "status report returned HTTP ${E2E_CODE}"
e2e_info "Report payload:"
if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "${E2E_BODY}" | jq '{overall, runtimeMode, subsystems}' 2>/dev/null || printf '%s\n' "${E2E_BODY}"
else
  printf '%s\n' "${E2E_BODY}"
fi

for sub in Voice Vision LLM Memory Desktop Commands Infra; do
  if printf '%s' "${E2E_BODY}" | grep -q "\"${sub}\""; then
    e2e_pass "subsystem present: ${sub}"
  else
    e2e_fail "subsystem missing: ${sub}"
  fi
done

e2e_finish
