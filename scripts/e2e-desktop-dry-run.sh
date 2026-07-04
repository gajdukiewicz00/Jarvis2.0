#!/usr/bin/env bash
#
# E2E scenario 3: safe desktop action.
#   "Open browser." -> Jarvis plans the action, safety classifies it as SAFE,
#   and the desktop path accepts it (read-only / non-destructive).
#
# This script deliberately exercises only SAFE/read paths. It first reads the
# active window (a SAFE pc-control capability) and then submits a benign
# "open browser" intent to the orchestrator. It never confirms a dangerous
# action. Targets $JARVIS_GATEWAY_URL (default localhost).
#
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/e2e-common.sh"
e2e_require_curl

if ! e2e_token >/dev/null 2>&1; then
  e2e_skip "desktop dry-run" "no token (set JARVIS_SMOKE_TOKEN or JARVIS_SMOKE_USER/PASS)"
  e2e_finish
fi

e2e_step "Reading active window (SAFE pc-control capability)"
e2e_split "$(e2e_api GET /api/v1/pc/desktop/window/active)"
case "${E2E_CODE}" in
  2*) e2e_pass "active window read (HTTP ${E2E_CODE})"; e2e_info "${E2E_BODY}" ;;
  404|503) e2e_skip "active window" "pc-control unavailable / stub mode (HTTP ${E2E_CODE})" ;;
  *) e2e_info "active window returned HTTP ${E2E_CODE} (continuing)" ;;
esac

e2e_step "Submitting benign intent 'open browser' to the orchestrator"
e2e_split "$(e2e_api POST /api/v1/orchestrator/execute-detailed '{"text":"open browser","language":"en"}')"
if [[ "${E2E_CODE}" =~ ^2 ]]; then
  e2e_pass "orchestrator accepted the safe intent (HTTP ${E2E_CODE})"
  e2e_info "${E2E_BODY}"
elif [[ "${E2E_CODE}" == "404" ]]; then
  e2e_skip "orchestrator execute-detailed" "endpoint not present (HTTP 404)"
else
  e2e_fail "orchestrator rejected a SAFE intent (HTTP ${E2E_CODE}): ${E2E_BODY}"
fi

e2e_finish
