#!/usr/bin/env bash
#
# E2E scenario 1: long-term memory write + recall.
#   "Remember that my project uses MicroK8s."  -> Jarvis writes a memory note.
#   "What do you remember about deployment?"   -> Jarvis retrieves it.
#
# Writes a clearly tagged probe note (tag: e2e-smoke). Requires ENABLE_MEMORY and
# a token; otherwise SKIPs. Targets $JARVIS_GATEWAY_URL (default localhost).
#
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/e2e-common.sh"
e2e_require_curl

PROBE="My project uses MicroK8s for deployment (e2e-smoke $(date +%s))"

e2e_step "Writing a memory note via /api/v1/memory/notes"
if ! e2e_token >/dev/null 2>&1; then
  e2e_skip "memory write/recall" "no token (set JARVIS_SMOKE_TOKEN or JARVIS_SMOKE_USER/PASS)"
  e2e_finish
fi

WRITE_BODY="$(printf '{"title":"e2e deployment fact","content":"%s","tags":["e2e-smoke","deployment"],"importance":"low"}' "${PROBE}")"
e2e_split "$(e2e_api POST /api/v1/memory/notes "${WRITE_BODY}")"
case "${E2E_CODE}" in
  2*) e2e_pass "memory note written (HTTP ${E2E_CODE})" ;;
  404|503) e2e_skip "memory write/recall" "memory feature disabled/unavailable (HTTP ${E2E_CODE})"; e2e_finish ;;
  *) e2e_fail "memory write (HTTP ${E2E_CODE}): ${E2E_BODY}"; e2e_finish ;;
esac

MEM_ID="$(printf '%s' "${E2E_BODY}" | grep -oE '"(id|memoryId)"[: ]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
[[ -n "${MEM_ID}" ]] && e2e_info "memoryId=${MEM_ID}"

e2e_step "Recalling memory about deployment"
# Prefer the semantic tool-search endpoint; fall back to note listing.
e2e_split "$(e2e_api POST /api/v1/tools/memory/search '{"query":"What do you remember about deployment?","limit":5}')"
if [[ "${E2E_CODE}" =~ ^2 ]] && printf '%s' "${E2E_BODY}" | grep -qi "microk8s"; then
  e2e_pass "semantic search recalled the MicroK8s deployment fact"
  e2e_finish
fi

# Fallback: read the note back by id.
if [[ -n "${MEM_ID}" ]]; then
  e2e_split "$(e2e_api GET "/api/v1/memory/notes/${MEM_ID}")"
  if [[ "${E2E_CODE}" =~ ^2 ]] && printf '%s' "${E2E_BODY}" | grep -qi "microk8s"; then
    e2e_pass "memory note read back by id contains the fact"
    e2e_finish
  fi
fi

e2e_fail "could not recall the written memory (search HTTP ${E2E_CODE})"
e2e_finish
