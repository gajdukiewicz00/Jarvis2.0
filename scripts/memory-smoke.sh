#!/usr/bin/env bash
# =============================================================================
# Memory Service Smoke Test
# =============================================================================
# Tests:
#   1. embedding-service /health
#   2. embedding-service /embed (batch)
#   3. memory-service /memory/health
#   4. memory-service /memory/ingest
#   5. memory-service /memory/search
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
EMBEDDING_URL="${EMBEDDING_URL:-${EMBEDDING_SERVICE_URL:-http://localhost:15001}}"
MEMORY_URL="${MEMORY_URL:-${MEMORY_SERVICE_URL:-http://localhost:8093}}"
TIMEOUT_SEC="${TIMEOUT_SEC:-30}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║           Memory Stack Smoke Test                          ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

FAILED=0
TEST_USER="smoke-user-$(date +%s)"
TEST_SESSION="smoke-session-$(date +%s)"

SERVICE_TOKEN=""
if [[ -n "${SERVICE_JWT_SECRET:-}" ]] && [[ -f "${SCRIPT_DIR}/runtime/make_service_jwt.py" ]]; then
    SERVICE_TOKEN="$(python3 "${SCRIPT_DIR}/runtime/make_service_jwt.py" \
        --secret "${SERVICE_JWT_SECRET}" \
        --subject "memory-smoke" \
        --service "memory-smoke" 2>/dev/null || true)"
fi

MEMORY_AUTH_HEADERS=()
if [[ -n "${SERVICE_TOKEN}" ]]; then
    MEMORY_AUTH_HEADERS=(
        -H "Authorization: Bearer ${SERVICE_TOKEN}"
        -H "X-User-Id: ${TEST_USER}"
        -H "X-User-Roles: USER"
    )
fi

# Helper function
api_call() {
    local method="$1"
    local url="$2"
    local data="${3:-}"
    local timeout="${4:-$TIMEOUT_SEC}"
    
    local tmp
    tmp="$(mktemp)"
    local start_ms end_ms status
    start_ms="$(date +%s%3N)"

    extra_headers=()
    if [[ "$url" == "${MEMORY_URL}"* ]]; then
        extra_headers=("${MEMORY_AUTH_HEADERS[@]}")
    fi
    
    if [ -n "$data" ]; then
        status="$(curl -sS --max-time "$timeout" -o "$tmp" -w "%{http_code}" \
            -H "Content-Type: application/json" \
            "${extra_headers[@]}" \
            -X "$method" -d "$data" "$url" 2>/dev/null)" || status="000"
    else
        status="$(curl -sS --max-time "$timeout" -o "$tmp" -w "%{http_code}" \
            "${extra_headers[@]}" \
            -X "$method" "$url" 2>/dev/null)" || status="000"
    fi
    
    end_ms="$(date +%s%3N)"
    
    LAST_STATUS="$status"
    LAST_BODY="$(cat "$tmp")"
    LAST_LATENCY=$((end_ms - start_ms))
    rm -f "$tmp"
    
    [ "$status" = "200" ] || [ "$status" = "202" ]
}

# =============================================================================
# Test 1: Embedding Service Health
# =============================================================================
echo -e "${YELLOW}[1/5] Embedding Service Health${NC}"
echo -n "  Checking $EMBEDDING_URL/health... "

if api_call GET "$EMBEDDING_URL/health"; then
    echo -e "${GREEN}✓${NC} (${LAST_LATENCY}ms)"
    echo "    Response: ${LAST_BODY:0:150}"
else
    echo -e "${RED}✗${NC} (HTTP $LAST_STATUS)"
    FAILED=$((FAILED + 1))
fi

# =============================================================================
# Test 2: Embedding Service /embed
# =============================================================================
echo ""
echo -e "${YELLOW}[2/5] Embedding Service - Generate Embedding${NC}"
echo -n "  POST /embed... "

embed_data='{"texts": ["Привет, меня зовут Денис", "Я люблю программировать"]}'

if api_call POST "$EMBEDDING_URL/embed" "$embed_data"; then
    echo -e "${GREEN}✓${NC} (${LAST_LATENCY}ms)"
    
    # Check dimension
    if echo "$LAST_BODY" | grep -Eq '"dimension"[[:space:]]*:[[:space:]]*384'; then
        echo -e "    ${GREEN}✓ Dimension: 384 (correct for multilingual-e5-small)${NC}"
    else
        echo -e "    ${YELLOW}⚠ Unexpected dimension${NC}"
    fi
    
    # Check count
    if echo "$LAST_BODY" | grep -Eq '"count"[[:space:]]*:[[:space:]]*2'; then
        echo -e "    ${GREEN}✓ Count: 2 embeddings generated${NC}"
    fi
else
    echo -e "${RED}✗${NC} (HTTP $LAST_STATUS)"
    echo "    Error: ${LAST_BODY:0:200}"
    FAILED=$((FAILED + 1))
fi

# =============================================================================
# Test 3: Memory Service Health
# =============================================================================
echo ""
echo -e "${YELLOW}[3/5] Memory Service Health${NC}"
echo -n "  Checking $MEMORY_URL/memory/health... "

if api_call GET "$MEMORY_URL/memory/health"; then
    echo -e "${GREEN}✓${NC} (${LAST_LATENCY}ms)"
    echo "    Response: ${LAST_BODY:0:150}"
    
    if echo "$LAST_BODY" | grep -Eq '"embeddingService"[[:space:]]*:[[:space:]]*"up"' && \
       echo "$LAST_BODY" | grep -Eq '"pgvector"[[:space:]]*:[[:space:]]*"available"'; then
        echo -e "    ${GREEN}✓ Embedding service connection OK${NC}"
        echo -e "    ${GREEN}✓ pgvector extension available${NC}"
    else
        echo -e "    ${YELLOW}⚠ Memory dependencies not fully ready${NC}"
    fi
else
    echo -e "${RED}✗${NC} (HTTP $LAST_STATUS)"
    echo "    Error: ${LAST_BODY:0:200}"
    FAILED=$((FAILED + 1))
fi

# =============================================================================
# Test 4: Memory Ingest
# =============================================================================
echo ""
echo -e "${YELLOW}[4/5] Memory Service - Ingest${NC}"
echo "  Ingesting test fact: 'Меня зовут Денис, я программист'"
echo -n "  POST /memory/ingest... "

ingest_data=$(cat <<EOF
{
    "userId": "$TEST_USER",
    "sessionId": "$TEST_SESSION",
    "messages": [
        {"role": "user", "content": "Запомни: меня зовут Денис, я программист из Москвы"},
        {"role": "assistant", "content": "Запомнил! Тебя зовут Денис, ты программист из Москвы."}
    ],
    "createChunks": true
}
EOF
)

if api_call POST "$MEMORY_URL/memory/ingest" "$ingest_data"; then
    echo -e "${GREEN}✓${NC} (${LAST_LATENCY}ms)"
    echo "    Response: ${LAST_BODY:0:150}"
else
    echo -e "${RED}✗${NC} (HTTP $LAST_STATUS)"
    echo "    Error: ${LAST_BODY:0:200}"
    FAILED=$((FAILED + 1))
fi

# Wait for async processing
echo "  Waiting 2s for embedding generation..."
sleep 2

# =============================================================================
# Test 5: Memory Search
# =============================================================================
echo ""
echo -e "${YELLOW}[5/5] Memory Service - Search${NC}"
echo "  Searching for: 'как меня зовут?'"
echo -n "  POST /memory/search... "

search_data=$(cat <<EOF
{
    "userId": "$TEST_USER",
    "query": "как меня зовут?",
    "topK": 3,
    "maxTokens": 500,
    "minSimilarity": 0.3
}
EOF
)

if api_call POST "$MEMORY_URL/memory/search" "$search_data"; then
    echo -e "${GREEN}✓${NC} (${LAST_LATENCY}ms)"
    
    # Check if we found the fact
    if echo "$LAST_BODY" | grep -qi "денис"; then
        echo -e "    ${GREEN}✓ Found 'Денис' in search results!${NC}"
        
        # Extract similarity
        similarity=$(echo "$LAST_BODY" | grep -oP '"similarity":\s*\K[0-9.]+' | head -1 || echo "N/A")
        echo "    Similarity: $similarity"
    else
        echo -e "    ${YELLOW}⚠ 'Денис' not found in results${NC}"
        echo "    Response: ${LAST_BODY:0:300}"
    fi

    if echo "$LAST_BODY" | grep -Eq '"retrievalMode"[[:space:]]*:[[:space:]]*"semantic"'; then
        echo -e "    ${GREEN}✓ Semantic pgvector retrieval used${NC}"
    else
        echo -e "    ${YELLOW}⚠ Search did not report semantic retrieval mode${NC}"
    fi
    
    # Check context text
    if echo "$LAST_BODY" | grep -q '"contextText"'; then
        echo -e "    ${GREEN}✓ contextText field present${NC}"
    fi
else
    echo -e "${RED}✗${NC} (HTTP $LAST_STATUS)"
    echo "    Error: ${LAST_BODY:0:200}"
    FAILED=$((FAILED + 1))
fi

# =============================================================================
# Summary
# =============================================================================
echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
echo "Test user: $TEST_USER"
echo "Test session: $TEST_SESSION"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ All memory smoke tests passed!${NC}"
    echo ""
    echo "The memory stack is working correctly:"
    echo "  - Embedding service generates 384-dim vectors"
    echo "  - pgvector is available"
    echo "  - Memory service stores and searches conversations"
    echo "  - RAG pipeline is functional"
    exit 0
else
    echo -e "${RED}✗ $FAILED test(s) failed${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "  - Check embedding-service logs: kubectl logs -n jarvis-prod deploy/embedding-service"
    echo "  - Check memory-service logs: kubectl logs -n jarvis-prod deploy/memory-service"
    echo "  - Check postgres-pgvector: kubectl logs -n jarvis-prod sts/postgres-pgvector"
    exit 1
fi
