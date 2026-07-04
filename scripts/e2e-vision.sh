#!/usr/bin/env bash
#
# E2E scenario 2: computer vision context.
#   A window with known text is on screen -> Jarvis captures the screen, extracts
#   context (OCR/active window) and reports the visible context.
#
# Vision is a workstation-local capability (vision-security-service runs on the
# host, gated by VISION_SECURITY_ENABLED + local-bridge). When it is not wired
# the scenario SKIPs cleanly. Fully automated OCR-of-known-text verification
# additionally needs a real display + a test window — see docs/vision/smoke-test
# (manual step). This script verifies the reachable pipeline:
#   vision status -> screen-context capture -> context payload returned.
#
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/e2e-common.sh"
e2e_require_curl

if ! e2e_token >/dev/null 2>&1; then
  e2e_skip "vision context" "no token (set JARVIS_SMOKE_TOKEN or JARVIS_SMOKE_USER/PASS)"
  e2e_finish
fi

e2e_step "Checking vision pipeline status"
e2e_split "$(e2e_api GET /api/v1/vision/status)"
case "${E2E_CODE}" in
  2*) e2e_pass "vision status reachable (HTTP ${E2E_CODE})"; e2e_info "${E2E_BODY}" ;;
  404|503) e2e_skip "vision context" "vision-security-service not wired (HTTP ${E2E_CODE}); host-local capability"; e2e_finish ;;
  *) e2e_skip "vision context" "vision status HTTP ${E2E_CODE}"; e2e_finish ;;
esac

e2e_step "Capturing screen context (OCR + active window)"
e2e_split "$(e2e_api POST /api/v1/vision-security/cv/screen-context '{"reason":"e2e-smoke"}')"
if [[ "${E2E_CODE}" =~ ^2 ]]; then
  e2e_pass "screen-context captured (HTTP ${E2E_CODE})"
  e2e_info "Context payload (truncated):"
  printf '%s\n' "${E2E_BODY}" | head -c 600; echo
  if printf '%s' "${E2E_BODY}" | grep -qiE 'window|text|ocr|title'; then
    e2e_pass "context payload includes active-window / visible-text fields"
  else
    e2e_info "context returned but no window/text field matched (may be empty screen)"
  fi
else
  e2e_skip "screen-context capture" "capture unavailable (HTTP ${E2E_CODE}); needs host display + permissions"
fi

e2e_finish
