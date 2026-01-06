#!/usr/bin/env bash
# verify-iteration-1.1.sh - Автоматическая проверка Iteration 1.1
# Usage: ./verify-iteration-1.1.sh [--require-https]
#   --require-https: strict mode - requires HTTPS/TLS fully configured (Iteration 7)

set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASSED=0
FAILED=0
REQUIRE_HTTPS=false

# Parse arguments
if [ "$1" = "--require-https" ]; then
    REQUIRE_HTTPS=true
fi

check() {
    local name="$1"
    local cmd="$2"
    local expected="$3"
    
    echo -n "Checking $name... "
    if eval "$cmd" > /dev/null 2>&1; then
        echo -e "${GREEN}✅ PASSED${NC}"
        PASSED=$((PASSED + 1))
        return 0
    else
        echo -e "${RED}❌ FAILED${NC}"
        echo "  Command: $cmd"
        echo "  Expected: $expected"
        FAILED=$((FAILED + 1))
        return 1
    fi
}

echo "=== Iteration 1.1 Verification ==="
echo ""

# 1. Service URLs (раздельная проверка: external vs internal)
echo "1. Checking Service URLs..."

# 1.1. Desktop-client (external) - после Iteration 7 только https:// и wss://
echo "   1.1. Desktop-client (external URLs)..."
HTTP_EXTERNAL_COUNT=0
HTTP_EXTERNAL_LIST=""
if [ -d "apps/desktop-client-javafx/src" ]; then
    # Проверка: запрещены любые http:// (кроме исключений)
    HTTP_EXTERNAL=$(grep -r "http://" apps/desktop-client-javafx/src/ 2>/dev/null | \
        grep -v "xmlns=" | \
        grep -v "xsi:schemaLocation" | \
        grep -v "console.picovoice.ai" | \
        grep -v "WAKE_WORD_SETUP" | \
        grep -v "https://" | \
        wc -l)
    HTTP_EXTERNAL_COUNT=$HTTP_EXTERNAL
    
    if [ "$HTTP_EXTERNAL_COUNT" -gt 0 ]; then
        HTTP_EXTERNAL_LIST=$(grep -r "http://" apps/desktop-client-javafx/src/ 2>/dev/null | \
            grep -v "xmlns=" | grep -v "xsi:schemaLocation" | grep -v "console.picovoice.ai" | head -5)
    fi
    
    # Проверка: должны быть только https://api.jarvis.local и wss://voice.jarvis.local
    HTTPS_COUNT=$(grep -r "https://api\.jarvis\.local\|wss://voice\.jarvis\.local" \
        apps/desktop-client-javafx/src/ 2>/dev/null | wc -l)
    
    if [ "$REQUIRE_HTTPS" = "true" ]; then
        if [ "$HTTP_EXTERNAL_COUNT" -gt 0 ]; then
            echo -e "  ${RED}❌ Found $HTTP_EXTERNAL_COUNT HTTP endpoints in desktop-client (HTTPS required)${NC}"
            echo "$HTTP_EXTERNAL_LIST"
            FAILED=$((FAILED + 1))
        elif [ "$HTTPS_COUNT" -gt 0 ]; then
            echo -e "  ${GREEN}✅ Desktop-client uses HTTPS/WSS only${NC}"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️  No HTTPS/WSS endpoints found in desktop-client${NC}"
        fi
        
        # Strict mode: desktop-client ОБЯЗАН содержать https://api.jarvis.local
        HTTPS_API_COUNT=$(grep -r "https://api\.jarvis\.local" \
            apps/desktop-client-javafx/src/ 2>/dev/null | \
            grep -v "xmlns=" | grep -v "xsi:schemaLocation" | wc -l)
        
        # Проверка в product config (AppConfig.kt или переменные окружения)
        CONFIG_HTTPS=$(grep -r "api\.jarvis\.local\|JARVIS_API_BASE_URL\|API_URL" \
            apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt 2>/dev/null | \
            grep -v "http://localhost" | wc -l)
        
        if [ "$HTTPS_API_COUNT" -gt 0 ] || [ "$CONFIG_HTTPS" -gt 0 ]; then
            echo -e "  ${GREEN}✅ Desktop-client contains https://api.jarvis.local (or config for it)${NC}"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌ Desktop-client MUST contain https://api.jarvis.local (strict mode)${NC}"
            echo "   Check: AppConfig.kt or product config should use https://api.jarvis.local"
            FAILED=$((FAILED + 1))
        fi
    else
        if [ "$HTTP_EXTERNAL_COUNT" -gt 0 ]; then
            echo -e "  ${YELLOW}⚠️  Found $HTTP_EXTERNAL_COUNT HTTP endpoints (will require HTTPS after Iteration 7)${NC}"
        else
            echo -e "  ${GREEN}✅ Desktop-client URLs OK${NC}"
            PASSED=$((PASSED + 1))
        fi
    fi
fi

# 1.2. K8s/Apps (internal) - разрешены http://<service>.jarvis.svc.cluster.local
echo "   1.2. K8s/Apps (internal URLs)..."
BAD_INTERNAL_COUNT=0
BAD_INTERNAL_LIST=""

# Запрещены: "голый" http://jarvis.svc.cluster.local (без service prefix)
BAD_INTERNAL=$(grep -r "http://jarvis\.svc\.cluster\.local" k8s/ apps/ scripts/ 2>/dev/null | \
    grep -v "^Binary" | \
    grep -v "# Hostnames используют" | \
    grep -v "xmlns=" | \
    grep -v "xsi:schemaLocation" | \
    wc -l)
BAD_INTERNAL_COUNT=$BAD_INTERNAL

# Запрещены: внешние http домены (не .svc.cluster.local)
EXTERNAL_HTTP=$(grep -r "http://.*\.jarvis\.local\|http://.*localhost" k8s/ apps/ scripts/ 2>/dev/null | \
    grep -v "^Binary" | \
    grep -v "xmlns=" | \
    grep -v "xsi:schemaLocation" | \
    grep -v "\.svc\.cluster\.local" | \
    wc -l)

# Правильные: http://<service>.jarvis.svc.cluster.local (внутри кластера - OK)
GOOD_INTERNAL=$(grep -r "http://[a-z-]*\.jarvis\.svc\.cluster\.local" k8s/ apps/ 2>/dev/null | \
    grep -v "^Binary" | \
    grep -v "# Hostnames используют" | \
    wc -l)

if [ "$BAD_INTERNAL_COUNT" -gt 0 ]; then
    echo -e "  ${RED}❌ Found $BAD_INTERNAL_COUNT incorrect internal URLs (missing service prefix)${NC}"
    grep -r "http://jarvis\.svc\.cluster\.local" k8s/ apps/ scripts/ 2>/dev/null | \
        grep -v "^Binary" | head -3
    FAILED=$((FAILED + 1))
elif [ "$EXTERNAL_HTTP" -gt 0 ]; then
    echo -e "  ${RED}❌ Found $EXTERNAL_HTTP external HTTP URLs in k8s/apps (should be internal only)${NC}"
    grep -r "http://.*\.jarvis\.local\|http://.*localhost" k8s/ apps/ scripts/ 2>/dev/null | \
        grep -v "^Binary" | grep -v "\.svc\.cluster\.local" | head -3
    FAILED=$((FAILED + 1))
elif [ "$GOOD_INTERNAL" -gt 0 ]; then
    echo -e "  ${GREEN}✅ Internal URLs are correct (found $GOOD_INTERNAL valid internal URLs)${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${YELLOW}⚠️  No internal URLs found${NC}"
fi

# 2. Secrets exist and contain all required variables
echo ""
echo "2. Checking Secrets..."
if kubectl -n jarvis get secret jarvis-secrets > /dev/null 2>&1; then
    echo -e "${GREEN}✅ jarvis-secrets exists${NC}"
    PASSED=$((PASSED + 1))
    
    # Get all keys from secret
    SECRET_KEYS=$(kubectl -n jarvis get secret jarvis-secrets -o jsonpath='{.data}' 2>/dev/null | \
        jq -r 'keys[]' 2>/dev/null | sort || \
        kubectl -n jarvis get secret jarvis-secrets -o yaml 2>/dev/null | \
        grep -E "^\s+[A-Z_]+:" | sed 's/.*\([A-Z_]*\):.*/\1/' | sort)
    
    # Extract all ${ENV_VAR} from all application*.yml files (excluding target/)
    echo "   Extracting required env vars from application*.yml files..."
    REQUIRED_VARS=$(find apps/ -name "application*.yml" -o -name "application*.yaml" 2>/dev/null | \
        grep -v "/target/" | \
        xargs grep -h '\${[A-Z_]*}' 2>/dev/null | \
        sed 's/.*\${\([A-Z_]*\)}.*/\1/' | \
        sort -u)
    
    if [ -z "$REQUIRED_VARS" ]; then
        echo -e "  ${YELLOW}⚠️  No env vars found in application*.yml files${NC}"
    else
        echo "   Found $(echo "$REQUIRED_VARS" | wc -l) unique env vars in application*.yml"
        MISSING_VARS=""
        MISSING_COUNT=0
        NOT_IN_DEPLOYMENT_COUNT=0
        
        for var in $REQUIRED_VARS; do
            # Проверка 1: есть ли в jarvis-secrets?
            IN_SECRET=false
            if echo "$SECRET_KEYS" | grep -q "^${var}$"; then
                IN_SECRET=true
            fi
            
            # Проверка 2: есть ли в deployment env (явно задано) - RUNTIME проверка через kubectl exec + printenv
            IN_DEPLOYMENT=false
            IN_POD_ENV=false
            
            # Реальная проверка через kubectl exec + printenv внутри pod (100% способ)
            # Найти running pod'ы, которые могут использовать эту переменную
            # Проверяем security-service и api-gateway (они точно используют JWT_SECRET и DB credentials)
            TEST_PODS=""
            if [[ "$var" =~ ^(JWT_SECRET|SPRING_DATASOURCE_|SPRING_RABBITMQ_) ]]; then
                # Для секретов проверяем security-service или api-gateway
                TEST_PODS=$(kubectl -n jarvis get pods -l app=security-service -o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' 2>/dev/null | awk '{print $1}')
                if [ -z "$TEST_PODS" ]; then
                    TEST_PODS=$(kubectl -n jarvis get pods -l app=api-gateway -o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' 2>/dev/null | awk '{print $1}')
                fi
            else
                # Для других переменных берём первый running pod
                TEST_PODS=$(kubectl -n jarvis get pods -o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' 2>/dev/null | awk '{print $1}')
            fi
            
            if [ -n "$TEST_PODS" ]; then
                # Получить имя контейнера из pod
                CONTAINER_NAME=$(kubectl -n jarvis get pod "$TEST_PODS" -o jsonpath='{.spec.containers[0].name}' 2>/dev/null || echo "")
                if [ -n "$CONTAINER_NAME" ]; then
                    # Проверить реальное присутствие переменной в pod через printenv
                    POD_ENV_VALUE=$(kubectl -n jarvis exec "$TEST_PODS" -c "$CONTAINER_NAME" -- printenv "$var" 2>/dev/null || echo "")
                    if [ -n "$POD_ENV_VALUE" ]; then
                        IN_POD_ENV=true
                        IN_DEPLOYMENT=true
                    fi
                fi
            fi
            
            # Fallback: проверка через kubectl describe (если exec недоступен)
            if [ "$IN_DEPLOYMENT" = "false" ]; then
                for deploy_name in $(kubectl -n jarvis get deploy -o jsonpath='{.items[*].metadata.name}' 2>/dev/null); do
                    if kubectl -n jarvis describe deploy/"$deploy_name" 2>/dev/null | \
                        grep -A 100 "Environment:" | \
                        grep -q "^\s*${var}"; then
                        IN_DEPLOYMENT=true
                        break
                    fi
                done
            fi
            
            # Fallback: проверка в YAML файлах (если kubectl недоступен)
            if [ "$IN_DEPLOYMENT" = "false" ]; then
                for deploy_file in k8s/base/*/deployment.yaml; do
                    if [ -f "$deploy_file" ] && grep -q "name: ${var}" "$deploy_file" 2>/dev/null; then
                        IN_DEPLOYMENT=true
                        break
                    fi
                done
                for deploy_file in k8s/overlays/*/*.yaml; do
                    if [ -f "$deploy_file" ] && grep -q "name: ${var}" "$deploy_file" 2>/dev/null; then
                        IN_DEPLOYMENT=true
                        break
                    fi
                done
            fi
            
            # Если переменная задана через envFrom (jarvis-secrets), она должна быть в secret
            # Если переменная задана явно в deployment env, она может не быть в secret
            if [ "$IN_SECRET" = "true" ]; then
                # Переменная в secret - проверяем реальное присутствие в pod
                if [ "$IN_POD_ENV" = "true" ]; then
                    # Переменная реально присутствует в pod (100% проверка через kubectl exec)
                    echo -e "  ${GREEN}✅${NC} $var verified in pod (runtime check via kubectl exec)"
                    continue
                else
                    # Переменная в secret, но не найдена в pod - возможно pod не запущен или переменная не используется
                    echo -e "  ${YELLOW}⚠️${NC} $var in secret but not verified in pod (pod may not be running or var not used)"
                fi
            elif [ "$IN_DEPLOYMENT" = "true" ]; then
                # Переменная задана явно в deployment - OK (не из secret)
                continue
            else
                # Переменная НЕ в secret И НЕ в deployment - ОШИБКА
                MISSING_VARS="${MISSING_VARS}${var}\n"
                echo -e "  ${RED}❌${NC} $var MISSING: not in jarvis-secrets AND not in deployment env"
                MISSING_COUNT=$((MISSING_COUNT + 1))
                FAILED=$((FAILED + 1))
            fi
        done
        
        if [ "$MISSING_COUNT" -eq 0 ]; then
            echo -e "  ${GREEN}✅${NC} All env vars from application*.yml are set (in secret or deployment)"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} $MISSING_COUNT variable(s) missing (not in secret and not in deployment)"
        fi
    fi
    
    # Дополнительная проверка: все переменные из secret должны быть использованы или доступны через envFrom
    echo "   Verifying deployments use envFrom: secretRef: jarvis-secrets..."
    DEPLOYMENTS_WITH_ENVFROM=0
    DEPLOYMENTS_WITHOUT_ENVFROM=0
    find k8s/base -name "deployment.yaml" -type f | while read deploy_file; do
        if grep -q "secretRef:" "$deploy_file" && grep -q "jarvis-secrets" "$deploy_file"; then
            DEPLOYMENTS_WITH_ENVFROM=$((DEPLOYMENTS_WITH_ENVFROM + 1))
        else
            DEPLOYMENTS_WITHOUT_ENVFROM=$((DEPLOYMENTS_WITHOUT_ENVFROM + 1))
            DEPLOY_NAME=$(basename $(dirname "$deploy_file"))
            echo -e "  ${YELLOW}⚠️${NC} $DEPLOY_NAME deployment missing envFrom: secretRef: jarvis-secrets"
        fi
    done
    
    if [ "$DEPLOYMENTS_WITHOUT_ENVFROM" -eq 0 ]; then
        echo -e "  ${GREEN}✅${NC} All deployments use envFrom: secretRef: jarvis-secrets"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC} $DEPLOYMENTS_WITHOUT_ENVFROM deployment(s) without envFrom"
    fi
else
    echo -e "${RED}❌ jarvis-secrets not found${NC}"
    FAILED=$((FAILED + 1))
fi

# 3. envFrom in deployments
echo ""
echo "3. Checking envFrom in deployments..."
if kubectl -n jarvis describe deploy/security-service 2>/dev/null | grep -q "Environment Variables from:"; then
    echo -e "${GREEN}✅ security-service has envFrom${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${RED}❌ security-service missing envFrom${NC}"
    FAILED=$((FAILED + 1))
fi

if kubectl -n jarvis describe deploy/api-gateway 2>/dev/null | grep -q "Environment Variables from:"; then
    echo -e "${GREEN}✅ api-gateway has envFrom${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${RED}❌ api-gateway missing envFrom${NC}"
    FAILED=$((FAILED + 1))
fi

# 4. Pods status
echo ""
echo "4. Checking Pods Status..."
CRASH_LOOPS_RAW=$(kubectl -n jarvis get pods 2>/dev/null | grep -c "CrashLoopBackOff" 2>/dev/null || echo "0")
# Убрать возможные пробелы и переводы строк, оставить только число
CRASH_LOOPS=$(echo "$CRASH_LOOPS_RAW" | tr -d '[:space:]' | head -1)
# Если пусто или не число - считать 0
if [ -z "$CRASH_LOOPS" ] || ! [[ "$CRASH_LOOPS" =~ ^[0-9]+$ ]]; then
    CRASH_LOOPS=0
fi
if [ "$CRASH_LOOPS" -eq 0 ]; then
    echo -e "${GREEN}✅ No CrashLoopBackOff pods${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${RED}❌ Found $CRASH_LOOPS CrashLoopBackOff pods${NC}"
    kubectl -n jarvis get pods 2>/dev/null | grep "CrashLoopBackOff" || true
    FAILED=$((FAILED + 1))
fi

# 4.1. LLM/Memory optional - проверка Init:0/1 (должно быть WARN, не FAIL)
echo "   4.1. LLM/Memory services (optional)..."
LLM_INIT_RAW=$(kubectl -n jarvis get pods 2>/dev/null | grep -E "llm-service|memory-service" | grep -c "Init:0/1" || echo "0")
LLM_INIT=$(echo "$LLM_INIT_RAW" | tr -d '[:space:]' | head -1)
if [ -z "$LLM_INIT" ] || ! [[ "$LLM_INIT" =~ ^[0-9]+$ ]]; then
    LLM_INIT=0
fi
if [ "$LLM_INIT" -gt 0 ]; then
    echo -e "  ${YELLOW}⚠️  Found $LLM_INIT LLM/Memory services in Init:0/1 (optional, not blocking)${NC}"
    kubectl -n jarvis get pods 2>/dev/null | grep -E "llm-service|memory-service" | grep "Init:0/1" || true
    # Это WARN, не FAIL - LLM optional
else
    echo -e "  ${GREEN}✅ LLM/Memory services not stuck in init${NC}"
fi

# 5. Auth endpoints (HTTP fallback for now, HTTPS will be in Iteration 7)
echo ""
echo "5. Checking Auth Endpoints..."
MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "127.0.0.1")
NODE_PORT=$(kubectl -n jarvis get svc api-gateway -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "")

# Try HTTPS first (if Iteration 7 completed)
if grep -q "api.jarvis.local" /etc/hosts 2>/dev/null && \
   [ -f "/usr/local/share/ca-certificates/jarvis-ca.crt" ]; then
    API_URL="https://api.jarvis.local"
    echo "   Using HTTPS: $API_URL"
    
    # Check if HTTPS works (without -k)
    if curl -sf --cacert /usr/local/share/ca-certificates/jarvis-ca.crt \
        "$API_URL/actuator/health" > /dev/null 2>&1; then
        echo -e "${GREEN}✅ API Gateway HTTPS is accessible${NC}"
        PASSED=$((PASSED + 1))
        USE_HTTPS=true
    else
        echo -e "${YELLOW}⚠️  HTTPS not working, falling back to HTTP${NC}"
        USE_HTTPS=false
    fi
else
    USE_HTTPS=false
fi

# Fallback to HTTP (current state)
if [ "$USE_HTTPS" = "false" ]; then
    if [ -z "$NODE_PORT" ]; then
        echo -e "${YELLOW}⚠️  Cannot determine API Gateway URL (NodePort not found)${NC}"
        echo "   Trying port-forward method..."
        API_URL="http://localhost:8080"
    else
        API_URL="http://${MINIKUBE_IP}:${NODE_PORT}"
    fi
    echo "   Using HTTP: $API_URL"
fi

# Check if gateway is accessible
CURL_OPTS=""
if [ "$USE_HTTPS" = "true" ]; then
    CURL_OPTS="--cacert /usr/local/share/ca-certificates/jarvis-ca.crt"
fi

if curl -sf $CURL_OPTS "$API_URL/actuator/health" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ API Gateway is accessible${NC}"
    PASSED=$((PASSED + 1))
    
    # Test login
    LOGIN_STATUS=$(curl -s $CURL_OPTS -o /dev/null -w "%{http_code}" -X POST "$API_URL/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"username":"test","password":"test"}' 2>/dev/null || echo "000")
    
    if [ "$LOGIN_STATUS" = "500" ]; then
        echo -e "${RED}❌ Login endpoint returns 500${NC}"
        echo "   Checking logs..."
        kubectl -n jarvis logs -l app=api-gateway --tail=10 2>/dev/null | tail -5
        kubectl -n jarvis logs -l app=security-service --tail=10 2>/dev/null | tail -5
        FAILED=$((FAILED + 1))
    elif [ "$LOGIN_STATUS" = "200" ] || [ "$LOGIN_STATUS" = "401" ]; then
        echo -e "${GREEN}✅ Login endpoint returns $LOGIN_STATUS (OK)${NC}"
        PASSED=$((PASSED + 1))
    else
        echo -e "${YELLOW}⚠️  Login endpoint returns $LOGIN_STATUS (unexpected but not 500)${NC}"
    fi
    
    # Test register
    REGISTER_STATUS=$(curl -s $CURL_OPTS -o /dev/null -w "%{http_code}" -X POST "$API_URL/auth/register" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"testuser$(date +%s)\",\"password\":\"testpass\",\"role\":\"USER\"}" 2>/dev/null || echo "000")
    
    if [ "$REGISTER_STATUS" = "500" ]; then
        echo -e "${RED}❌ Register endpoint returns 500${NC}"
        FAILED=$((FAILED + 1))
    elif [ "$REGISTER_STATUS" = "200" ] || [ "$REGISTER_STATUS" = "201" ] || [ "$REGISTER_STATUS" = "409" ]; then
        echo -e "${GREEN}✅ Register endpoint returns $REGISTER_STATUS (OK)${NC}"
        PASSED=$((PASSED + 1))
    else
        echo -e "${YELLOW}⚠️  Register endpoint returns $REGISTER_STATUS (unexpected but not 500)${NC}"
    fi
else
    echo -e "${RED}❌ API Gateway is not accessible${NC}"
    echo "   Make sure minikube is running and api-gateway service is deployed"
    FAILED=$((FAILED + 1))
fi

# 6. HTTPS checks (Iteration 7 requirements) - CONDITIONAL
echo ""
echo "6. Checking HTTPS/TLS (Iteration 7 requirements)..."

# Определить, реализована ли Iteration 7
ITERATION_7_COMPLETE=false
if [ -f "/usr/local/share/ca-certificates/jarvis-ca.crt" ] && \
   [ -f "/etc/hosts" ] && \
   grep -q "api.jarvis.local" /etc/hosts 2>/dev/null && \
   kubectl -n jarvis get secret jarvis-tls > /dev/null 2>&1; then
    ITERATION_7_COMPLETE=true
fi

if [ "$ITERATION_7_COMPLETE" = "false" ] && [ "$REQUIRE_HTTPS" = "false" ]; then
    echo -e "  ${YELLOW}⏭️  SKIP: Iteration 7 not completed, HTTPS checks skipped${NC}"
    echo "   Use --require-https to enforce HTTPS checks"
elif [ "$ITERATION_7_COMPLETE" = "false" ] && [ "$REQUIRE_HTTPS" = "true" ]; then
    echo -e "  ${RED}❌ HTTPS required (--require-https) but Iteration 7 not completed${NC}"
    echo "   Missing: CA certificate, domains in /etc/hosts, or jarvis-tls secret"
    FAILED=$((FAILED + 1))
else
    # Iteration 7 реализована - выполняем проверки
    HTTPS_PASSED=0
    HTTPS_FAILED=0
    
    # 6.1. CA certificate in trust store
    if [ -f "/usr/local/share/ca-certificates/jarvis-ca.crt" ]; then
        echo -e "  ${GREEN}✅${NC} CA certificate found in trust store"
        HTTPS_PASSED=$((HTTPS_PASSED + 1))
        
        # Check CA certificate validity
        if openssl x509 -in /usr/local/share/ca-certificates/jarvis-ca.crt -text -noout > /dev/null 2>&1; then
            echo -e "  ${GREEN}✅${NC} CA certificate is valid"
            HTTPS_PASSED=$((HTTPS_PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} CA certificate is invalid"
            HTTPS_FAILED=$((HTTPS_FAILED + 1))
        fi
    else
        echo -e "  ${RED}❌${NC} CA certificate not found"
        HTTPS_FAILED=$((HTTPS_FAILED + 1))
    fi
    
    # 6.2. Domains in /etc/hosts
    API_DOMAIN=$(grep "api.jarvis.local" /etc/hosts 2>/dev/null | wc -l)
    VOICE_DOMAIN=$(grep "voice.jarvis.local" /etc/hosts 2>/dev/null | wc -l)
    if [ "$API_DOMAIN" -gt 0 ] && [ "$VOICE_DOMAIN" -gt 0 ]; then
        echo -e "  ${GREEN}✅${NC} api.jarvis.local and voice.jarvis.local found in /etc/hosts"
        HTTPS_PASSED=$((HTTPS_PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} Missing domains in /etc/hosts"
        HTTPS_FAILED=$((HTTPS_FAILED + 1))
    fi
    
    # 6.3. TLS secret in K8s
    if kubectl -n jarvis get secret jarvis-tls > /dev/null 2>&1; then
        TLS_KEYS=$(kubectl -n jarvis get secret jarvis-tls -o jsonpath='{.data}' 2>/dev/null | \
            jq -r 'keys[]' 2>/dev/null | grep -E "tls\.(crt|key)" | wc -l)
        if [ "$TLS_KEYS" -ge 2 ]; then
            echo -e "  ${GREEN}✅${NC} jarvis-tls secret exists with tls.crt and tls.key"
            HTTPS_PASSED=$((HTTPS_PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} jarvis-tls secret missing tls.crt or tls.key"
            HTTPS_FAILED=$((HTTPS_FAILED + 1))
        fi
    else
        echo -e "  ${RED}❌${NC} jarvis-tls secret not found"
        HTTPS_FAILED=$((HTTPS_FAILED + 1))
    fi
    
    # 6.4. HTTPS endpoint works (режим зависит от REQUIRE_HTTPS)
    if [ "$API_DOMAIN" -gt 0 ]; then
        if [ "$REQUIRE_HTTPS" = "true" ]; then
            # Strict mode: curl БЕЗ --cacert (использует системный trust store)
            if curl -sf https://api.jarvis.local/actuator/health > /dev/null 2>&1; then
                echo -e "  ${GREEN}✅${NC} HTTPS endpoint works (strict mode: without --cacert)"
                HTTPS_PASSED=$((HTTPS_PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} HTTPS endpoint fails (strict mode: requires system trust store)"
                HTTPS_FAILED=$((HTTPS_FAILED + 1))
            fi
        else
            # Default mode: curl с --cacert (если CA ещё не установлена системно)
            if curl -sf --cacert /usr/local/share/ca-certificates/jarvis-ca.crt \
                https://api.jarvis.local/actuator/health > /dev/null 2>&1; then
                echo -e "  ${GREEN}✅${NC} HTTPS endpoint works (default mode: with --cacert)"
                HTTPS_PASSED=$((HTTPS_PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} HTTPS endpoint fails"
                HTTPS_FAILED=$((HTTPS_FAILED + 1))
            fi
        fi
        
        # 6.5. openssl verification (всегда требует Verify return code: 0)
        if [ -f "/usr/local/share/ca-certificates/jarvis-ca.crt" ]; then
            SSL_VERIFY=$(echo | openssl s_client -connect api.jarvis.local:443 \
                -CAfile /usr/local/share/ca-certificates/jarvis-ca.crt 2>&1 | \
                grep "Verify return code" | grep -o "0 (ok)" | wc -l)
            if [ "$SSL_VERIFY" -gt 0 ]; then
                echo -e "  ${GREEN}✅${NC} openssl verification passes (Verify return code: 0)"
                HTTPS_PASSED=$((HTTPS_PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} openssl verification fails (Verify return code != 0)"
                HTTPS_FAILED=$((HTTPS_FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} Cannot verify SSL (CA file missing)"
            HTTPS_FAILED=$((HTTPS_FAILED + 1))
        fi
    fi
    
    # 6.6. HTTP → HTTPS redirect
    if [ "$API_DOMAIN" -gt 0 ]; then
        REDIRECT=$(curl -sI http://api.jarvis.local/actuator/health 2>&1 | \
            grep -E "HTTP.*30[1|8]|Location.*https" | wc -l)
        if [ "$REDIRECT" -gt 0 ]; then
            echo -e "  ${GREEN}✅${NC} HTTP → HTTPS redirect works"
            HTTPS_PASSED=$((HTTPS_PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} HTTP → HTTPS redirect not configured"
            HTTPS_FAILED=$((HTTPS_FAILED + 1))
        fi
    fi
    
    # 6.7. UI doesn't disable SSL verification
    SSL_DISABLE=$(grep -r "trust.*all\|disable.*ssl\|-k\|insecure\|setDefaultHostnameVerifier" \
        apps/desktop-client-javafx/src/ 2>/dev/null | \
        grep -v "xmlns=" | \
        grep -v "xsi:schemaLocation" | \
        wc -l)
    if [ "$SSL_DISABLE" -eq 0 ]; then
        echo -e "  ${GREEN}✅${NC} UI doesn't disable SSL verification"
        HTTPS_PASSED=$((HTTPS_PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} UI disables SSL verification ($SSL_DISABLE occurrences)"
        grep -r "trust.*all\|disable.*ssl\|-k\|insecure\|setDefaultHostnameVerifier" \
            apps/desktop-client-javafx/src/ 2>/dev/null | \
            grep -v "xmlns=" | grep -v "xsi:schemaLocation" | head -3
        HTTPS_FAILED=$((HTTPS_FAILED + 1))
    fi
    
    if [ "$HTTPS_FAILED" -eq 0 ]; then
        echo -e "${GREEN}✅ HTTPS/TLS fully configured (Iteration 7 complete)${NC}"
        PASSED=$((PASSED + HTTPS_PASSED))
    else
        echo -e "${RED}❌ HTTPS/TLS configuration incomplete ($HTTPS_PASSED passed, $HTTPS_FAILED failed)${NC}"
        FAILED=$((FAILED + HTTPS_FAILED))
    fi
fi

# Summary
echo ""
echo "=== Verification Summary ==="
echo -e "${GREEN}Passed: $PASSED${NC}"
echo -e "${RED}Failed: $FAILED${NC}"

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✅ All checks passed!${NC}"
    exit 0
else
    echo -e "${RED}❌ Some checks failed${NC}"
    exit 1
fi

