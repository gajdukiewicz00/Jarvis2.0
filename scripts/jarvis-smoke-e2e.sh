#!/usr/bin/env bash
# =============================================================================
# jarvis-smoke-e2e.sh — post-deploy smoke check.
#
# Deliberately narrow: login -> LLM chat -> one memory endpoint -> one
# planner endpoint, each asserted PASS/FAIL. This is the fast gate meant to
# run right after `./jarvis up` / `scripts/jarvis-oneclick.sh` / a rollout, to
# answer "is the stack actually usable, not just Ready" in under a minute.
#
# For the fuller acceptance sweep (voice, obsidian unified search, android
# dry-run, ...) use scripts/jarvis-final-check.sh or
# scripts/jarvis-smoke-verify.sh instead — those remain the canonical deep
# checks; this script is the lightweight one wired for CI-style gating.
#
# Auth: JARVIS_TEST_USER/JARVIS_TEST_PASS (default test1111/test1111, the
# repo's standing local test account — see docs/HUMAN_LAYER_DEMO_RUNBOOK.md).
#
# Gateway target resolution (never hardcodes an IP if it can help it):
#   1. JARVIS_API_BASE, if set, always wins.
#   2. Otherwise, ask the live cluster for the current node InternalIP via
#      kubectl (mirrors ./jarvis's detect_node_ip).
#   3. Only if neither is available, fall back to the last-known-good IP used
#      elsewhere in this repo (10.113.0.176) purely as a last resort so the
#      script still gives a clear FAIL instead of an unbound-variable crash.
#
# Usage:
#   scripts/jarvis-smoke-e2e.sh
#   JARVIS_API_BASE=https://10.20.30.40 scripts/jarvis-smoke-e2e.sh
#
# Exit codes: 0 all checks passed · 1 one or more checks failed
# =============================================================================
set -uo pipefail

usage() {
  cat <<EOF
Usage: $(basename "${BASH_SOURCE[0]}") [--help]

Post-deploy smoke check: login -> LLM chat -> memory endpoint -> planner
endpoint, each asserted PASS/FAIL. See docs/DEPLOYMENT_CANONICAL.md.

Environment:
  JARVIS_API_BASE    Gateway base URL (default: auto-detect node IP via kubectl)
  JARVIS_API_HOST    Ingress Host header (default: api.jarvis.local)
  JARVIS_TEST_USER   Login username (default: test1111)
  JARVIS_TEST_PASS   Login password (default: test1111)
  JARVIS_NAMESPACE   Namespace used only for node-IP auto-detection (default: jarvis-prod)
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; usage >&2; exit 2 ;;
  esac
done

NS="${JARVIS_NAMESPACE:-jarvis-prod}"

detect_node_ip() {
  local kctl=""
  if kubectl version >/dev/null 2>&1 && kubectl -n "${NS}" get ns >/dev/null 2>&1; then
    kctl="kubectl"
  elif sudo -n k3s kubectl version >/dev/null 2>&1 && sudo -n k3s kubectl -n "${NS}" get ns >/dev/null 2>&1; then
    kctl="sudo k3s kubectl"
  else
    return 1
  fi
  ${kctl} get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null | awk '{print $1}'
}

GW="${JARVIS_API_BASE:-}"
if [[ -z "${GW}" ]]; then
  NODE_IP="$(detect_node_ip || true)"
  GW="https://${NODE_IP:-10.113.0.176}"
fi
HOST="${JARVIS_API_HOST:-api.jarvis.local}"
USER_LOGIN="${JARVIS_TEST_USER:-test1111}"
USER_PASS="${JARVIS_TEST_PASS:-test1111}"

pass=0
fail=0
ok()   { printf '  \033[0;32m[PASS]\033[0m %s\n' "$1"; pass=$((pass + 1)); }
no()   { printf '  \033[0;31m[FAIL]\033[0m %s\n' "$1"; fail=$((fail + 1)); }
step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

json_field() {
  # json_field <field> — reads a JSON body from stdin, prints the field or "".
  # python3 is used elsewhere in this repo's smoke scripts (jarvis-smoke-verify.sh,
  # jarvis-final-check.sh) for the same purpose, so this mirrors that convention.
  local field="$1"
  python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    print(data.get(\"${field}\", '') or '')
except Exception:
    print('')
" 2>/dev/null
}

echo "== Jarvis post-deploy smoke e2e (gateway=${GW}, host=${HOST}) =="

# --- 1. login ------------------------------------------------------------------
step "1. login (${USER_LOGIN})"
TOKEN="$(curl -sk -m10 -H "Host: ${HOST}" "${GW}/api/v1/security/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER_LOGIN}\",\"password\":\"${USER_PASS}\"}" 2>/dev/null \
  | json_field accessToken)"
AUTH_ARGS=(-sk -m20 -H "Host: ${HOST}" -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json')
if [[ -n "${TOKEN}" ]]; then
  ok "login acquired an accessToken"
else
  no "login failed (no accessToken from ${GW}/api/v1/security/auth/login)"
fi

# --- 2. brain /llm/chat returns a model ------------------------------------------
step "2. brain /llm/chat"
if [[ -n "${TOKEN}" ]]; then
  MODEL="$(curl "${AUTH_ARGS[@]}" -m45 -X POST "${GW}/api/v1/llm/chat" \
    -d '{"sessionId":"smoke-e2e","messages":[{"role":"user","content":"reply with one word: alive /no_think"}]}' 2>/dev/null \
    | json_field model)"
  if [[ -n "${MODEL}" ]]; then
    ok "llm chat returned model=${MODEL}"
  else
    no "llm chat (no model in response)"
  fi
else
  no "llm chat — skipped (no token)"
fi

# --- 3. memory endpoint returns 200 -----------------------------------------------
step "3. memory endpoint"
if [[ -n "${TOKEN}" ]]; then
  MCODE="$(curl "${AUTH_ARGS[@]}" -H 'X-User-Id: 2' -o /dev/null -w '%{http_code}' -X POST \
    "${GW}/api/v1/memory/search" -d '{"query":"jarvis","topK":2}' 2>/dev/null)"
  [[ "${MCODE}" == "200" ]] && ok "memory /search returned HTTP 200" || no "memory /search returned HTTP ${MCODE:-000} (want 200)"
else
  no "memory endpoint — skipped (no token)"
fi

# --- 4. planner endpoint returns 200 -----------------------------------------------
step "4. planner endpoint"
if [[ -n "${TOKEN}" ]]; then
  PCODE="$(curl "${AUTH_ARGS[@]}" -o /dev/null -w '%{http_code}' -X POST \
    "${GW}/api/v1/planner/llm/recommend" -d '{"userId":"2","context":"smoke-e2e"}' 2>/dev/null)"
  [[ "${PCODE}" == "200" ]] && ok "planner /llm/recommend returned HTTP 200" || no "planner /llm/recommend returned HTTP ${PCODE:-000} (want 200)"
else
  no "planner endpoint — skipped (no token)"
fi

# --- summary -------------------------------------------------------------------
printf '\n\033[1m== smoke-e2e result: %d passed, %d failed ==\033[0m\n' "${pass}" "${fail}"
[[ "${fail}" -eq 0 ]]
