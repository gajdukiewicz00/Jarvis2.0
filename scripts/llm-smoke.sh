#!/usr/bin/env bash
# =============================================================================
# LLM Stack Smoke Test
# =============================================================================
# Tests:
#   1. llm-server /health (GPU availability)
#   2. llm-server /api/v1/llm/chat (single inference)
#   3. llm-service /api/v1/llm/health (Java service)
#   4. llm-service chat with memory check
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
RUNTIME_ENV_FILE="${JARVIS_HOME}/run/local-runtime/local.env"
if [[ -f "${RUNTIME_ENV_FILE}" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "${RUNTIME_ENV_FILE}"
    set +a
fi

# Configuration
LLM_SERVER_URL="${LLM_SERVER_URL:-http://localhost:15000}"
LLM_SERVICE_URL="${LLM_SERVICE_URL:-http://localhost:8091}"
TIMEOUT_SEC="${TIMEOUT_SEC:-120}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-10}"

SERVICE_TOKEN=""
if [[ -n "${SERVICE_JWT_SECRET:-}" ]] && [[ -f "${SCRIPT_DIR}/runtime/make_service_jwt.py" ]]; then
    SERVICE_TOKEN="$(python3 "${SCRIPT_DIR}/runtime/make_service_jwt.py" \
        --secret "${SERVICE_JWT_SECRET}" \
        --subject "llm-smoke" \
        --service "llm-smoke" 2>/dev/null || true)"
fi

SERVICE_AUTH_HEADERS=()
if [[ -n "${SERVICE_TOKEN}" ]]; then
    SERVICE_AUTH_HEADERS=(-H "Authorization: Bearer ${SERVICE_TOKEN}")
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║           LLM Stack Smoke Test                             ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Helper function
check_health() {
    local name="$1"
    local url="$2"
    local timeout="${3:-$HEALTH_TIMEOUT_SEC}"
    
    echo -n "  Checking $name at $url... "
    
    local tmp
    tmp="$(mktemp)"
    local status
    status="$(curl -sS --max-time "$timeout" -o "$tmp" -w "%{http_code}" "$url" 2>/dev/null)" || status="000"
    local body
    body="$(cat "$tmp")"
    LAST_STATUS="$status"
    LAST_BODY="$body"
    rm -f "$tmp"
    
    if [ "$status" = "200" ]; then
        echo -e "${GREEN}✓${NC} (HTTP $status)"
        echo "    Response: ${body:0:200}"
        return 0
    else
        echo -e "${RED}✗${NC} (HTTP $status)"
        [ -n "$body" ] && echo "    Body: ${body:0:200}"
        return 1
    fi
}

chat_request() {
    local url="$1"
    local prompt="$2"
    local timeout="${3:-$TIMEOUT_SEC}"
    
    echo -n "  Sending chat: \"${prompt:0:50}\"... "
    
    local payload
    payload=$(cat <<EOF
{
    "messages": [
        {"role": "user", "content": "$prompt"}
    ]
}
EOF
)
    
    local tmp
    tmp="$(mktemp)"
    local start_ms end_ms status
    start_ms="$(date +%s%3N)"
    status="$(curl -sS --max-time "$timeout" -o "$tmp" -w "%{http_code}" \
        -H "Content-Type: application/json" \
        -X POST -d "$payload" "$url" 2>/dev/null)" || status="000"
    end_ms="$(date +%s%3N)"
    
    local body
    body="$(cat "$tmp")"
    rm -f "$tmp"
    
    local latency_ms=$((end_ms - start_ms))
    
    if [ "$status" = "200" ]; then
        echo -e "${GREEN}✓${NC} (HTTP $status, ${latency_ms}ms)"
        # Extract reply
        local reply
        reply="$(echo "$body" | grep -oP '"reply"\s*:\s*"\K[^"]*' | head -1 || echo "")"
        if [ -n "$reply" ]; then
            echo "    Reply: ${reply:0:150}..."
        else
            echo "    Response: ${body:0:150}"
        fi
        LAST_RESPONSE="$body"
        return 0
    else
        echo -e "${RED}✗${NC} (HTTP $status, ${latency_ms}ms)"
        echo "    Error: ${body:0:200}"
        return 1
    fi
}

FAILED=0

# =============================================================================
# Test 1: LLM Server Health
# =============================================================================
echo ""
echo -e "${YELLOW}[1/4] LLM Server Health Check${NC}"
if check_health "llm-server" "${LLM_SERVER_URL}/health"; then
    # Check GPU status from response
    if echo "${LAST_BODY}" | grep -Eq '"gpu_available"[[:space:]]*:[[:space:]]*true'; then
        echo -e "    ${GREEN}✓ GPU is available${NC}"
    else
        echo -e "    ${YELLOW}⚠ GPU not detected (running on CPU)${NC}"
    fi
else
    echo -e "    ${RED}✗ LLM Server is not healthy${NC}"
    FAILED=$((FAILED + 1))
fi

# =============================================================================
# Test 2: LLM Server Chat
# =============================================================================
echo ""
echo -e "${YELLOW}[2/4] LLM Server Direct Chat${NC}"
if chat_request "${LLM_SERVER_URL}/api/v1/llm/chat" "Привет! Скажи одно слово." 60; then
    echo -e "    ${GREEN}✓ LLM Server inference works${NC}"
else
    echo -e "    ${RED}✗ LLM Server inference failed${NC}"
    FAILED=$((FAILED + 1))
fi

# =============================================================================
# Test 3: LLM Service Health
# =============================================================================
echo ""
echo -e "${YELLOW}[3/4] LLM Service Health Check${NC}"
if check_health "llm-service" "${LLM_SERVICE_URL}/api/v1/llm/health"; then
    echo -e "    ${GREEN}✓ LLM Service is healthy${NC}"
else
    echo -e "    ${YELLOW}⚠ LLM Service not available (optional for direct llm-server testing)${NC}"
fi

# =============================================================================
# Test 4: LLM Service Chat with Memory
# =============================================================================
echo ""
echo -e "${YELLOW}[4/4] LLM Service Chat (with memory integration)${NC}"

# First request - store information
CODEWORD="jarvis42"
SESSION_ID="smoke-$(date +%s)"

echo "  Step 1: Store codeword..."
payload1=$(cat <<EOF
{
    "sessionId": "$SESSION_ID",
    "messages": [
        {"role": "user", "content": "Запомни кодовое слово: $CODEWORD"}
    ]
}
EOF
)

tmp1="$(mktemp)"
status1="$(curl -sS --max-time "$TIMEOUT_SEC" -o "$tmp1" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    "${SERVICE_AUTH_HEADERS[@]}" \
    -X POST -d "$payload1" "${LLM_SERVICE_URL}/api/v1/llm/chat" 2>/dev/null)" || status1="000"
rm -f "$tmp1"

if [ "$status1" = "200" ]; then
    echo -e "    ${GREEN}✓${NC} Stored codeword"
    
    # Second request - recall
    echo "  Step 2: Recall codeword..."
    sleep 1
    
    payload2=$(cat <<EOF
{
    "sessionId": "$SESSION_ID",
    "messages": [
        {"role": "user", "content": "Какое кодовое слово я сказал?"}
    ]
}
EOF
)
    
    tmp2="$(mktemp)"
    status2="$(curl -sS --max-time "$TIMEOUT_SEC" -o "$tmp2" -w "%{http_code}" \
        -H "Content-Type: application/json" \
        "${SERVICE_AUTH_HEADERS[@]}" \
        -X POST -d "$payload2" "${LLM_SERVICE_URL}/api/v1/llm/chat" 2>/dev/null)" || status2="000"
    body2="$(cat "$tmp2")"
    rm -f "$tmp2"
    
    if [ "$status2" = "200" ]; then
        if echo "$body2" | grep -qi "$CODEWORD"; then
            echo -e "    ${GREEN}✓ Memory working - codeword recalled correctly${NC}"
        else
            echo -e "    ${YELLOW}⚠ Response received but codeword not found${NC}"
            echo "    Response: ${body2:0:150}"
        fi
    else
        echo -e "    ${RED}✗ Recall failed (HTTP $status2)${NC}"
        FAILED=$((FAILED + 1))
    fi
else
    echo -e "    ${YELLOW}⚠ LLM Service not available (HTTP $status1)${NC}"
fi

# =============================================================================
# Summary
# =============================================================================
echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ All smoke tests passed!${NC}"
    exit 0
else
    echo -e "${RED}✗ $FAILED test(s) failed${NC}"
    exit 1
fi
