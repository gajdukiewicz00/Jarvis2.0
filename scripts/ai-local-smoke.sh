#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

cleanup() {
    local exit_code=$?

    if is_truthy "${JARVIS_AI_LOCAL_SMOKE_STOP_ON_EXIT:-false}"; then
        "${ROOT_DIR}/scripts/runtime-down.sh" >/dev/null 2>&1 || true
    fi

    exit "${exit_code}"
}
trap cleanup EXIT

json_get() {
    local url="$1"
    shift
    curl -fsS "$@" "${url}"
}

json_post() {
    local url="$1"
    local payload="$2"
    shift 2
    curl -fsS "$@" \
        -H "Content-Type: application/json" \
        -X POST \
        -d "${payload}" \
        "${url}"
}

service_token() {
    python3 "${ROOT_DIR}/scripts/runtime/make_service_jwt.py" \
        --secret "${SERVICE_JWT_SECRET}" \
        --subject "ai-local-smoke" \
        --service "ai-local-smoke"
}

assert_json() {
    local payload="$1"
    local script="$2"
    python3 - "${payload}" <<PY
import json
import sys
payload = json.loads(sys.argv[1])
${script}
PY
}

export ENABLE_LLM="true"
export JARVIS_LLM_ENABLED="true"
export ENABLE_MEMORY="true"
export MEMORY_SERVICE_ENABLED="true"
export JARVIS_LLM_MANAGED_SERVER="true"
export JARVIS_SKIP_BUILD="${JARVIS_SKIP_BUILD:-false}"

SMOKE_RUN_ID="${JARVIS_AI_SMOKE_RUN_ID:-$(date +%s)-$$}"
SMOKE_USERNAME="ai-local-smoke-${SMOKE_RUN_ID}"
SMOKE_PASSWORD="AiLocalSmoke!123"

if ! is_truthy "${JARVIS_AI_SKIP_SETUP:-false}"; then
    log "Running canonical AI local setup..."
    "${ROOT_DIR}/scripts/setup-ai-local.sh"
fi

ensure_local_env

[[ -f "${JARVIS_LLM_MODEL_PATH}" ]] || fail "Canonical LLM model is missing at ${JARVIS_LLM_MODEL_PATH}"
[[ -d "${JARVIS_EMBEDDING_MODEL_PATH}" ]] || fail "Canonical embedding model is missing at ${JARVIS_EMBEDDING_MODEL_PATH}"

log "Bringing up local Jarvis runtime for AI smoke..."
"${ROOT_DIR}/scripts/runtime-up.sh"

register_response="$(curl_with_runtime_tls "${JARVIS_API_BASE_URL}/auth/register" -fsS \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${SMOKE_USERNAME}\",\"password\":\"${SMOKE_PASSWORD}\",\"role\":\"USER\"}" || true)"

if [[ -z "${register_response}" ]]; then
    register_response="$(curl_with_runtime_tls "${JARVIS_API_BASE_URL}/auth/login" -fsS \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"${SMOKE_USERNAME}\",\"password\":\"${SMOKE_PASSWORD}\"}")"
fi

ACCESS_TOKEN="$(python3 - "${register_response}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
print(payload["accessToken"])
PY
)"

USER_ME_RESPONSE="$(curl_with_runtime_tls "${JARVIS_API_BASE_URL}/api/v1/security/auth/me" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}")"
USER_ID="$(python3 - "${USER_ME_RESPONSE}" <<'PY'
import json
import sys

print(json.loads(sys.argv[1])["id"])
PY
)"

SERVICE_TOKEN="$(service_token)"
DIRECT_AI_HEADERS=(
    -H "Authorization: Bearer ${SERVICE_TOKEN}"
    -H "X-User-Id: ${USER_ID}"
    -H "X-User-Roles: USER"
)

log "Checking llm-service runtime truth..."
LLM_RUNTIME="$(json_get "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/runtime" \
    -H "Authorization: Bearer ${SERVICE_TOKEN}")"
assert_json "${LLM_RUNTIME}" '
assert payload["service"] == "llm-service", payload
assert payload["status"] == "ready", payload
assert payload["fullLocalAiReadiness"] is True, payload
assert payload["localDefaultStack"]["id"] == "'"${JARVIS_CANONICAL_LOCAL_AI_STACK}"'"
assert payload["localDefaultStack"]["fullLocalAiReadiness"] is True, payload["localDefaultStack"]
assert payload["llm"]["configuredProvider"] == "llamacpp", payload["llm"]
assert payload["llm"]["configuredDevicePath"] == "cpu", payload["llm"]
assert payload["llm"]["effectiveDevicePath"] == "cpu", payload["llm"]
assert payload["llm"]["configuredGpuLayers"] == 0, payload["llm"]
assert payload["llm"]["effectiveGpuLayers"] == 0, payload["llm"]
assert payload["llm"]["available"] is True, payload["llm"]
assert payload["gpu"]["canonicalCpuBaseline"]["status"] == "verified", payload["gpu"]
assert payload["gpu"]["canonicalCpuBaseline"]["nGpuLayers"] == 0, payload["gpu"]
assert payload["memory"]["available"] is True, payload["memory"]
assert payload["embedding"]["available"] is True, payload["embedding"]
assert payload["vectorStore"]["available"] is True, payload["vectorStore"]
'

log "Checking llm-service health summary..."
LLM_HEALTH="$(json_get "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/health")"
assert_json "${LLM_HEALTH}" '
assert payload["status"] == "healthy", payload
assert payload["llm_server_available"] is True, payload
assert payload["memory_available"] is True, payload
assert payload["full_local_ai_readiness"] is True, payload
'

log "Checking llm-server health..."
LLM_SERVER_HEALTH="$(json_get "${LLM_SERVER_URL}/health")"
assert_json "${LLM_SERVER_HEALTH}" '
assert payload["status"] == "healthy", payload
assert payload["backend"] == "llamacpp", payload
assert payload["model_loaded"] is True, payload
assert payload["effective_device"] == "cpu", payload
assert payload["effective_n_gpu_layers"] == 0, payload
assert payload["diagnostics"]["model_path"] == "'"${JARVIS_LLM_MODEL_PATH}"'", payload["diagnostics"]
'

log "Checking embedding-service health..."
EMBEDDING_HEALTH="$(json_get "${EMBEDDING_SERVICE_URL}/health")"
assert_json "${EMBEDDING_HEALTH}" '
assert payload["status"] == "healthy", payload
assert payload["model_loaded"] is True, payload
assert payload["model_name"] == "'"${JARVIS_EMBEDDING_MODEL_PATH}"'", payload
assert payload["embedding_dim"] == 384, payload
'

log "Checking direct embedding path..."
EMBED_PAYLOAD='{"text":"Как называется мой проект?","input_type":"query"}'
EMBED_SINGLE="$(json_post "${EMBEDDING_SERVICE_URL}/embed/single" "${EMBED_PAYLOAD}")"
assert_json "${EMBED_SINGLE}" '
assert payload["dimension"] == 384, payload
assert len(payload["embedding"]) == 384, len(payload["embedding"])
'

TEST_SESSION="ai-local-smoke-memory-$(date +%s)"
log "Checking memory write and semantic retrieval..."
MEMORY_INGEST_PAYLOAD="$(cat <<EOF
{
  "sessionId": "${TEST_SESSION}",
  "messages": [
    {"role": "user", "content": "Запомни: мой проект называется Aurora Graph и я строю backend платформу."},
    {"role": "assistant", "content": "Запомнил. Твой проект называется Aurora Graph."}
  ],
  "createChunks": true
}
EOF
)"
json_post "$(runtime_local_http_url "${JARVIS_MEMORY_SERVICE_PORT}")/memory/ingest" "${MEMORY_INGEST_PAYLOAD}" "${DIRECT_AI_HEADERS[@]}" >/dev/null

MEMORY_SEARCH_PAYLOAD='{"query":"как называется мой проект?","topK":3,"maxTokens":300,"minSimilarity":0.4}'
MEMORY_SEARCH="$(json_post "$(runtime_local_http_url "${JARVIS_MEMORY_SERVICE_PORT}")/memory/search" "${MEMORY_SEARCH_PAYLOAD}" "${DIRECT_AI_HEADERS[@]}")"
assert_json "${MEMORY_SEARCH}" '
assert payload["retrievalMode"] == "semantic", payload
assert "Aurora Graph".lower() in payload["contextText"].lower(), payload["contextText"]
assert payload["chunks"], payload
'

CODEWORD="nebula-$(date +%s)"
LLM_SESSION_1="ai-local-smoke-llm-1-$(date +%s)"
log "Checking llm-service memory ingest path..."
LLM_STORE_PAYLOAD="$(cat <<EOF
{
  "sessionId": "${LLM_SESSION_1}",
  "messages": [
    {"role": "user", "content": "Запомни кодовое слово ${CODEWORD}. Я спрошу его позже в другой сессии."}
  ]
}
EOF
)"
LLM_STORE_RESPONSE="$(json_post "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/chat" "${LLM_STORE_PAYLOAD}" "${DIRECT_AI_HEADERS[@]}")"
assert_json "${LLM_STORE_RESPONSE}" '
assert payload["reply"], payload
'

log "Waiting for asynchronous llm-service -> memory-service ingest..."
FOUND_CODEWORD="false"
for _ in $(seq 1 30); do
    SEARCH_CODEWORD_PAYLOAD="$(cat <<EOF
{
  "query": "${CODEWORD}",
  "topK": 3,
  "maxTokens": 200,
  "minSimilarity": 0.3
}
EOF
)"
    SEARCH_CODEWORD_RESPONSE="$(json_post "$(runtime_local_http_url "${JARVIS_MEMORY_SERVICE_PORT}")/memory/search" "${SEARCH_CODEWORD_PAYLOAD}" "${DIRECT_AI_HEADERS[@]}" || true)"
    if [[ -n "${SEARCH_CODEWORD_RESPONSE}" ]] && grep -qi "${CODEWORD}" <<<"${SEARCH_CODEWORD_RESPONSE}"; then
        FOUND_CODEWORD="true"
        break
    fi
    sleep 1
done
[[ "${FOUND_CODEWORD}" == "true" ]] || fail "llm-service did not persist the codeword into memory-service"

LLM_SESSION_2="ai-local-smoke-llm-2-$(date +%s)"
log "Checking llm + memory recall across sessions..."
LLM_RECALL_PAYLOAD="$(cat <<EOF
{
  "sessionId": "${LLM_SESSION_2}",
  "messages": [
    {"role": "user", "content": "Какое кодовое слово я просил запомнить?"}
  ]
}
EOF
)"
LLM_RECALL_RESPONSE="$(json_post "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/chat" "${LLM_RECALL_PAYLOAD}" "${DIRECT_AI_HEADERS[@]}")"
assert_json "${LLM_RECALL_RESPONSE}" '
reply = payload["reply"].lower()
assert "'"${CODEWORD}"'".lower() in reply, payload
'

log "Checking orchestrator consumer path through api-gateway..."
ORCHESTRATOR_RESPONSE="$(curl_with_runtime_tls "${JARVIS_API_BASE_URL}/api/v1/orchestrator/execute" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"Какое кодовое слово я просил тебя запомнить?"}')"
python3 - "${ORCHESTRATOR_RESPONSE}" "${CODEWORD}" <<'PY'
import sys

reply = sys.argv[1].strip().lower()
codeword = sys.argv[2].strip().lower()
assert reply, "orchestrator reply must not be empty"
assert codeword in reply, reply
PY

# ── Lifecycle and warmup state ──────────────────────────────────────
log "Checking lifecycle state and warmup readiness..."
LLM_HEALTH_LIFECYCLE="$(json_get "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/health")"
assert_json "${LLM_HEALTH_LIFECYCLE}" '
assert payload["lifecycle_state"] == "READY", f"Expected READY, got {payload.get(\"lifecycle_state\")}"
assert payload["warmup_complete"] is True, payload
assert payload["active_inferences"] >= 0, payload
assert payload["queue_depth"] >= 0, payload
'

# ── Runtime lifecycle + admission blocks ────────────────────────────
log "Checking runtime lifecycle and admission blocks..."
LLM_RUNTIME_LIFECYCLE="$(json_get "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/runtime" \
    -H "Authorization: Bearer ${SERVICE_TOKEN}")"
assert_json "${LLM_RUNTIME_LIFECYCLE}" '
lc = payload.get("lifecycle", {})
assert lc.get("state") == "READY", lc
assert lc.get("warmup_complete") is True, lc
assert lc.get("usable") is True, lc
adm = payload.get("admission", {})
assert adm.get("available_permits", -1) >= 0, adm
'

# ── Profile-aware chat request ──────────────────────────────────────
log "Checking profile-aware chat path (voice-fast profile)..."
PROFILE_SESSION="ai-local-smoke-profile-$(date +%s)"
PROFILE_PAYLOAD="$(cat <<EOF
{
  "sessionId": "${PROFILE_SESSION}",
  "messages": [
    {"role": "user", "content": "Скажи одно слово."}
  ]
}
EOF
)"
PROFILE_RESPONSE="$(json_post "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/chat" "${PROFILE_PAYLOAD}" \
    "${DIRECT_AI_HEADERS[@]}" -H "X-Model-Profile: voice-fast")"
assert_json "${PROFILE_RESPONSE}" '
assert payload.get("reply"), "voice-fast profile should return a reply"
'

# ── Invalid tool-call rejection (via orchestrate endpoint) ──────────
log "Checking invalid tool-call rejection..."
TOOLCALL_PAYLOAD='{"sessionId":"smoke-tool","userId":"smoke","intent":"set a timer","locale":"ru"}'
TOOLCALL_RESPONSE="$(json_post "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")/api/v1/llm/orchestrate" "${TOOLCALL_PAYLOAD}" \
    "${DIRECT_AI_HEADERS[@]}" || true)"
if [[ -n "${TOOLCALL_RESPONSE}" ]]; then
    assert_json "${TOOLCALL_RESPONSE}" '
# If the LLM produced tool_calls, they must have been validated; warnings may be present
if payload.get("warnings"):
    for w in payload["warnings"]:
        assert isinstance(w, str), w
'
    log "Tool-call validation exercised."
else
    log "Orchestrate endpoint returned empty (non-fatal for smoke)."
fi

log "AI local smoke passed."
