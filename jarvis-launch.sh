#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Unified Kubernetes Launch Script
# =============================================================================
# Запускает весь стек: minikube + Kubernetes + микросервисы + desktop-клиент
# Использование: ./jarvis-launch.sh
# =============================================================================

# Отключаем set -e, используем явную обработку ошибок
set -o pipefail

# === Настройки ===
PROJECT_DIR="/home/kwaqa/IdeaProjects/Jarvis2.0"
DESKTOP_JAR="$PROJECT_DIR/apps/desktop-client-javafx/target/desktop-client-javafx-0.1.0-SNAPSHOT.jar"
K8S_DIR="$PROJECT_DIR/k8s"
NAMESPACE="jarvis"

# Порты для доступа
API_GATEWAY_PORT=8080
VOICE_GATEWAY_PORT=8081
RABBITMQ_UI_PORT=15672

# Feature flags (можно переопределить через env: ENABLE_KAFKA=true ./jarvis-launch.sh)
ENABLE_KAFKA=${ENABLE_KAFKA:-false}  # Kafka отключена по умолчанию
ENABLE_LLM=${ENABLE_LLM:-false}      # LLM stack отключен по умолчанию (optional)
ENABLE_MEMORY=${ENABLE_MEMORY:-false} # Memory stack отключен по умолчанию (optional, requires ENABLE_LLM)
ENABLE_GPU=${ENABLE_GPU:-true}       # GPU для LLM включен по умолчанию (если LLM включен)
ENABLE_PORT_FORWARD=${ENABLE_PORT_FORWARD:-false}  # Port-forward отключен по умолчанию (Stage 8: Ingress is default)

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

# Флаг для отслеживания ошибок
HAD_ERROR=false
ERROR_MESSAGE=""

# === Функция обработки ошибок ===
on_error() {
    local line=$1
    local cmd=$2
    HAD_ERROR=true
    ERROR_MESSAGE="Ошибка на строке $line: $cmd"
    echo ""
    echo -e "${RED}╔════════════════════════════════════════╗${NC}"
    echo -e "${RED}║           ❌ ОШИБКА ЗАПУСКА            ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${RED}$ERROR_MESSAGE${NC}"
    echo ""
    echo -e "${YELLOW}💡 Для диагностики:${NC}"
    echo "   ./jarvis-logs.sh          - просмотр логов"
    echo "   kubectl get pods -n jarvis - состояние подов"
    echo "   kubectl describe pod <name> -n jarvis - детали пода"
    echo ""
}

# Устанавливаем trap для перехвата ошибок
trap 'on_error $LINENO "$BASH_COMMAND"' ERR

# === Функции ===
print_header() {
    echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║   🤖 JARVIS 2.0 (Kubernetes) 🤖       ║${NC}"
    echo -e "${BLUE}║         Starting up...                 ║${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
    echo ""
}

check_port() {
    local port=$1
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        return 0  # Port is in use
    fi
    return 1  # Port is free
}

check_dependencies() {
    local missing=()
    
    if ! command -v kubectl &> /dev/null; then
        missing+=("kubectl")
    fi
    if ! command -v docker &> /dev/null; then
        missing+=("docker")
    fi
    if ! command -v java &> /dev/null; then
        missing+=("java")
    fi
    
    if [ ${#missing[@]} -ne 0 ]; then
        echo -e "${RED}❌ Отсутствуют зависимости: ${missing[*]}${NC}"
        echo ""
        echo "Установка:"
        for dep in "${missing[@]}"; do
            case $dep in
                kubectl) echo "  kubectl: https://kubernetes.io/docs/tasks/tools/" ;;
                docker)  echo "  docker: https://docs.docker.com/engine/install/" ;;
                java)    echo "  java: sudo apt install openjdk-21-jdk" ;;
            esac
        done
        return 1
    fi
    return 0
}

check_cluster() {
    if command -v minikube &> /dev/null && minikube status 2>/dev/null | grep -q "Running"; then
        echo "minikube"
    elif command -v k3s &> /dev/null || systemctl is-active --quiet k3s 2>/dev/null; then
        echo "k3s"
    elif command -v kind &> /dev/null && kind get clusters 2>/dev/null | grep -q .; then
        echo "kind"
    elif kubectl cluster-info &> /dev/null 2>&1; then
        echo "other"
    else
        echo "none"
    fi
}

wait_for_pod() {
    local label=$1
    local timeout=${2:-180}
    echo -n "  ⏳ Waiting for $label..."
    if kubectl wait --for=condition=ready pod -l "app=$label" -n $NAMESPACE --timeout="${timeout}s" >/dev/null 2>&1; then
        echo -e " ${GREEN}✓${NC}"
        return 0
    else
        echo -e " ${YELLOW}⚠️ (таймаут, продолжаю)${NC}"
        return 0  # Не фейлим скрипт, продолжаем
    fi
}

cleanup_port_forwards() {
    echo -e "${YELLOW}⏳${NC} Останавливаю предыдущие port-forward..."
    pkill -f "kubectl port-forward.*jarvis" 2>/dev/null || true
    sleep 1
}

wait_for_gateway_health() {
    local url=$1
    local max_attempts=${2:-30}
    echo -n "  ⏳ Проверка доступности API Gateway..."
    for i in $(seq 1 $max_attempts); do
        if curl -sf "$url/actuator/health" >/dev/null 2>&1; then
            echo -e " ${GREEN}✓${NC}"
            return 0
        fi
        sleep 2
    done
    echo -e " ${YELLOW}⚠️ (недоступен, но продолжаю)${NC}"
    return 0
}

# === Начало ===
print_header

cd "$PROJECT_DIR" || {
    echo -e "${RED}❌ Не могу перейти в $PROJECT_DIR${NC}"
    read -p "Нажми Enter для выхода..."
    exit 1
}

# === Проверка зависимостей ===
echo -e "${CYAN}[Preflight]${NC} Проверка зависимостей..."
if ! check_dependencies; then
    read -p "Нажми Enter для выхода..."
    exit 1
fi
echo -e "${GREEN}✓${NC} Все зависимости установлены"
echo ""

# === Проверка кластера ===
CLUSTER_TYPE=$(check_cluster)

if [ "$CLUSTER_TYPE" = "none" ]; then
    echo -e "${YELLOW}⚠️  Kubernetes кластер не найден. Запускаю minikube...${NC}"
    
    if command -v minikube &> /dev/null; then
        if ! minikube start --memory=8192 --cpus=4 --driver=docker; then
            echo -e "${RED}❌ Не удалось запустить minikube${NC}"
            read -p "Нажми Enter для выхода..."
            exit 1
        fi
        CLUSTER_TYPE="minikube"
    else
        echo -e "${RED}❌ minikube не установлен. Установи:${NC}"
        echo "   curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64"
        echo "   sudo install minikube-linux-amd64 /usr/local/bin/minikube"
        read -p "Нажми Enter для выхода..."
        exit 1
    fi
fi

echo -e "${GREEN}✓${NC} Кластер: $CLUSTER_TYPE"
echo ""

# === Проверка/генерация сертификатов ===
# Iteration 1.5 (Stage 7): Generate certs for api.jarvis.local and voice.jarvis.local
CERTS_DIR="$PROJECT_DIR/docker/certs"
if [ ! -f "$CERTS_DIR/jarvis.crt" ] || [ ! -f "$CERTS_DIR/jarvis.key" ]; then
    echo -e "${YELLOW}[1/7]${NC} 🔐 Генерирую TLS сертификаты..."
    mkdir -p "$CERTS_DIR"
    # Generate cert with SAN for api.jarvis.local and voice.jarvis.local
    if ! openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout "$CERTS_DIR/jarvis.key" \
        -out "$CERTS_DIR/jarvis.crt" \
        -subj "/CN=api.jarvis.local/O=Jarvis" \
        -addext "subjectAltName=DNS:api.jarvis.local,DNS:voice.jarvis.local,DNS:*.jarvis.local,DNS:localhost,IP:127.0.0.1" 2>/dev/null; then
        echo -e "${YELLOW}⚠️  Не удалось сгенерировать сертификаты, продолжаю...${NC}"
    else
        echo -e "${GREEN}✓${NC} Сертификаты сгенерированы (api.jarvis.local, voice.jarvis.local)"
    fi
else
    echo -e "${GREEN}✓${NC} TLS сертификаты уже существуют"
    # Check if cert needs regeneration for new domains
    if ! openssl x509 -in "$CERTS_DIR/jarvis.crt" -text -noout 2>/dev/null | grep -q "api.jarvis.local\|voice.jarvis.local"; then
        echo -e "${YELLOW}⚠️  Сертификат не содержит api.jarvis.local/voice.jarvis.local, регенерирую...${NC}"
        if openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
            -keyout "$CERTS_DIR/jarvis.key" \
            -out "$CERTS_DIR/jarvis.crt" \
            -subj "/CN=api.jarvis.local/O=Jarvis" \
            -addext "subjectAltName=DNS:api.jarvis.local,DNS:voice.jarvis.local,DNS:*.jarvis.local,DNS:localhost,IP:127.0.0.1" 2>/dev/null; then
            echo -e "${GREEN}✓${NC} Сертификаты обновлены"
        fi
    fi
fi

# Создаём/обновляем TLS secret в Kubernetes
kubectl create namespace $NAMESPACE --dry-run=client -o yaml 2>/dev/null | kubectl apply -f - 2>/dev/null || true
kubectl delete secret jarvis-tls -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
kubectl create secret tls jarvis-tls \
    --cert="$CERTS_DIR/jarvis.crt" \
    --key="$CERTS_DIR/jarvis.key" \
    -n $NAMESPACE 2>/dev/null || true
echo ""

# === Сборка Docker-образов ===
echo -e "${YELLOW}[2/7]${NC} 🐳 Собираю Docker-образы..."

# Для minikube используем встроенный Docker
if [ "$CLUSTER_TYPE" = "minikube" ]; then
    eval $(minikube docker-env) || {
        echo -e "${RED}❌ Не удалось настроить Docker для minikube${NC}"
        read -p "Нажми Enter для выхода..."
        exit 1
    }
fi

# Сервисы для сборки (Java микросервисы)
SERVICES=(
    "api-gateway"
    "security-service"
    "life-tracker"
    "analytics-service"
    "voice-gateway"
    "pc-control"
    "smart-home-service"
    "nlp-service"
    "orchestrator"
    "user-profile"
    "planner-service"
)

# LLM+Memory сервисы (собираются отдельно)
LLM_SERVICES=(
    "memory-service"
    "llm-service"
)

# Собираем JAR'ы, если их нет (иначе docker build упадёт на COPY target/*.jar)
echo -e "${YELLOW}[2a/7]${NC} 📦 Собираю JAR'ы (mvn -DskipTests, если нужно)..."
MAVEN_ERRORS=0
for svc in "${SERVICES[@]}"; do
    if ! ls "$PROJECT_DIR/apps/$svc/target/"*.jar >/dev/null 2>&1; then
        echo "  Packaging $svc..."
        if mvn -pl "apps/$svc" -am package -DskipTests -q; then
            echo -e "  ${GREEN}✓${NC} $svc packaged"
        else
            echo -e "  ${RED}✗${NC} Maven build failed for $svc"
            MAVEN_ERRORS=$((MAVEN_ERRORS + 1))
        fi
    else
        echo -e "  ${GREEN}✓${NC} $svc JAR найден"
    fi
done
if [ $MAVEN_ERRORS -gt 0 ]; then
    echo -e "${YELLOW}⚠️  $MAVEN_ERRORS сервис(ов) не собраны Maven, docker build может упасть${NC}"
fi

BUILD_ERRORS=0
for svc in "${SERVICES[@]}"; do
    if [ -f "$PROJECT_DIR/apps/$svc/Dockerfile" ]; then
        echo -n "  Building jarvis/$svc..."
        # Dockerfiles expect service dir as build context (COPY target/*.jar)
        if docker build -t "jarvis/$svc:latest" -f "$PROJECT_DIR/apps/$svc/Dockerfile" "$PROJECT_DIR/apps/$svc" -q >/dev/null 2>&1; then
            echo -e " ${GREEN}✓${NC}"
        else
            echo -e " ${RED}✗${NC}"
            BUILD_ERRORS=$((BUILD_ERRORS + 1))
        fi
    fi
done

if [ $BUILD_ERRORS -gt 0 ]; then
    echo -e "${YELLOW}⚠️  $BUILD_ERRORS образ(ов) не собралось, продолжаю...${NC}"
else
    echo -e "${GREEN}✓${NC} Docker-образы собраны"
fi

# Сборка LLM+Memory сервисов если включено
if [ "$ENABLE_LLM" = "true" ]; then
    echo ""
    echo -e "${YELLOW}[2b/7]${NC} 🧠 Собираю LLM+Memory сервисы..."
    
    # JAR'ы для LLM сервисов
    for svc in "${LLM_SERVICES[@]}"; do
        if ! ls "$PROJECT_DIR/apps/$svc/target/"*.jar >/dev/null 2>&1; then
            echo "  Packaging $svc..."
            if mvn -pl "apps/$svc" -am package -DskipTests -q 2>/dev/null; then
                echo -e "  ${GREEN}✓${NC} $svc packaged"
            else
                echo -e "  ${YELLOW}⚠${NC} Maven build failed for $svc"
            fi
        else
            echo -e "  ${GREEN}✓${NC} $svc JAR найден"
        fi
    done
    
    # Docker образы для LLM сервисов
    # ВАЖНО: для minikube образы собираются напрямую в minikube docker-env
    LLM_BUILD_ITEMS=(
        "llm-server:docker/llm-server"
        "embedding-service:docker/embedding-service"
        "memory-service:apps/memory-service"
        "llm-service:apps/llm-service"
    )
    
    # Гарантируем что мы в minikube docker-env перед сборкой LLM образов
    if [ "$CLUSTER_TYPE" = "minikube" ]; then
        eval $(minikube docker-env) || {
            echo -e "  ${RED}✗${NC} Failed to set minikube docker-env for LLM services"
        }
    fi
    
    if [ ${#LLM_BUILD_ITEMS[@]} -eq 0 ]; then
        echo -e "  ${CYAN}⏭️  SKIPPED (ENABLE_LLM=false, ENABLE_MEMORY=false)${NC}"
    else
        for item in "${LLM_BUILD_ITEMS[@]}"; do
            name="${item%%:*}"
            path="${item##*:}"
            echo -n "  Building jarvis/$name..."
            # Сборка в minikube docker-env (если minikube) или обычный docker
            # Для embedding-service и llm-server путь: docker/<name>
            # Для memory-service и llm-service путь: apps/<name>
            if docker build -t "jarvis/$name:latest" -f "$PROJECT_DIR/$path/Dockerfile" "$PROJECT_DIR/$path" -q >/dev/null 2>&1; then
                echo -e " ${GREEN}✓${NC}"
                # Для minikube образ уже в registry (через docker-env), дополнительный load не нужен
                # Для других кластеров может потребоваться push в registry
            else
                # Не показываем "Failed" если LLM optional и GPU недоступен
                if [ "$name" = "llm-server" ] && [ "$ENABLE_GPU" = "false" ]; then
                    echo -e " ${YELLOW}⏭️  SKIPPED (no GPU/runtime)${NC}"
                else
                    echo -e "  ${RED}✗${NC} Failed to build jarvis/$name"
                    BUILD_ERRORS=$((BUILD_ERRORS + 1))
                fi
            fi
        done
        echo -e "${GREEN}✓${NC} LLM+Memory образы собраны"
    fi
fi
echo ""

# === Загрузка образов в кластер (для kind) ===
if [ "$CLUSTER_TYPE" = "kind" ]; then
    echo -e "${YELLOW}[2.5/7]${NC} 📦 Загружаю образы в kind..."
    CLUSTER_NAME=$(kind get clusters | head -1)
    for svc in "${SERVICES[@]}"; do
        kind load docker-image "jarvis/$svc:latest" --name "$CLUSTER_NAME" 2>/dev/null || true
    done
fi

# === Применяем Kubernetes манифесты ===
echo -e "${YELLOW}[3/7]${NC} ☸️  Применяю Kubernetes манифесты..."

# Namespace и базовые ресурсы
kubectl apply -f "$K8S_DIR/base/namespace.yaml" 2>/dev/null || true
kubectl apply -f "$K8S_DIR/base/configmap.yaml" 2>/dev/null || true
kubectl apply -f "$K8S_DIR/base/secrets.yaml" 2>/dev/null || true

# TLS Secret
if [ -f "$K8S_DIR/base/tls-secret-generated.yaml" ]; then
    kubectl apply -f "$K8S_DIR/base/tls-secret-generated.yaml" 2>/dev/null || true
fi

# Iteration 1.5 (Stage 7): Apply Ingress
if [ -f "$K8S_DIR/base/ingress.yaml" ]; then
    kubectl apply -f "$K8S_DIR/base/ingress.yaml" 2>/dev/null || true
    echo -e "${GREEN}✓${NC} Ingress applied"
fi

# Инфраструктура (из base)
echo "  Applying PostgreSQL..."
kubectl apply -f "$K8S_DIR/base/postgres/" 2>/dev/null || true

echo "  Applying RabbitMQ..."
# RabbitMQ будет добавлен позже, если нужен
# kubectl apply -f "$K8S_DIR/base/rabbitmq/" 2>/dev/null || true

echo -e "${GREEN}✓${NC} Инфраструктура применена"
echo ""

# === Ждём готовности инфраструктуры ===
echo -e "${YELLOW}[4/7]${NC} ⏳ Жду готовности инфраструктуры..."
wait_for_pod "postgres" 120

echo -e "${GREEN}✓${NC} Инфраструктура готова"
echo ""

# === Применяем микросервисы (из base) ===
echo -e "${YELLOW}[5/7]${NC} 🚀 Запускаю микросервисы..."
kubectl apply -k "$K8S_DIR/base/" 2>/dev/null || true

echo -e "${GREEN}✓${NC} Микросервисы запущены"
echo ""

# === Деплой LLM+Memory стека ===
if [ "$ENABLE_LLM" = "true" ] || [ "$ENABLE_MEMORY" = "true" ]; then
    echo -e "${YELLOW}[5a/7]${NC} 🧠 Запускаю LLM+Memory стек..."
    
    # Применяем LLM/Memory манифесты условно
    if [ -d "$K8S_DIR/overlays/local" ]; then
        # Применяем базовые ресурсы (secrets, pv-models)
        kubectl apply -f "$K8S_DIR/overlays/local/pv-models.yaml" 2>/dev/null || true
        kubectl apply -f "$K8S_DIR/overlays/local/secrets-local.yaml" 2>/dev/null || true
        
        # LLM stack (если включен)
        if [ "$ENABLE_LLM" = "true" ]; then
            # GPU Prerequisites Detection (Stage: perf/hardware)
            if [ "$ENABLE_GPU" = "true" ]; then
                echo -e "  ${CYAN}🔍 Checking GPU prerequisites...${NC}"
                GPU_CHECK_FAILED=false
                
                # 1. Check NVIDIA driver on host
                if ! command -v nvidia-smi >/dev/null 2>&1; then
                    echo -e "  ${RED}❌${NC} NVIDIA driver not found (nvidia-smi not available)"
                    echo -e "    ${GRAY}Install NVIDIA driver: https://www.nvidia.com/Download/index.aspx${NC}"
                    GPU_CHECK_FAILED=true
                else
                    echo -e "  ${GREEN}✓${NC} NVIDIA driver found"
                    # Show GPU info
                    nvidia-smi --query-gpu=name,driver_version --format=csv,noheader 2>/dev/null | head -1 | while read -r gpu_info; do
                        echo -e "    ${GRAY}GPU: $gpu_info${NC}"
                    done || true
                fi
                
                # 2. Check nvidia-container-toolkit (for containerd/docker)
                if ! command -v nvidia-container-runtime >/dev/null 2>&1 && ! grep -q "nvidia" /etc/docker/daemon.json 2>/dev/null; then
                    echo -e "  ${YELLOW}⚠️${NC}  nvidia-container-toolkit may not be configured"
                    echo -e "    ${GRAY}Install: https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html${NC}"
                    # Don't fail here, minikube may handle it differently
                else
                    echo -e "  ${GREEN}✓${NC} nvidia-container-toolkit configured"
                fi
                
                # 3. Check Kubernetes device plugin (nvidia-device-plugin)
                if ! kubectl get daemonset -n kube-system nvidia-device-plugin-daemonset >/dev/null 2>&1 && \
                   ! kubectl get daemonset -A | grep -q nvidia-device-plugin; then
                    echo -e "  ${YELLOW}⚠️${NC}  NVIDIA device plugin not found in cluster"
                    echo -e "    ${GRAY}For minikube: minikube addons enable gpu${NC}"
                    echo -e "    ${GRAY}For k3s/k8s: install nvidia-device-plugin daemonset${NC}"
                    # Don't fail here, may be handled by minikube addon
                else
                    echo -e "  ${GREEN}✓${NC} NVIDIA device plugin found"
                fi
                
                # 4. Check if GPU is allocatable in cluster
                GPU_ALLOCATABLE=$(kubectl get nodes -o jsonpath='{.items[0].status.allocatable.nvidia\.com/gpu}' 2>/dev/null || echo "")
                if [ -z "$GPU_ALLOCATABLE" ] || [ "$GPU_ALLOCATABLE" = "0" ]; then
                    echo -e "  ${RED}❌${NC} No GPU allocatable in cluster (nvidia.com/gpu: $GPU_ALLOCATABLE)"
                    echo -e "    ${GRAY}Action: Enable GPU support in cluster (minikube addons enable gpu)${NC}"
                    GPU_CHECK_FAILED=true
                else
                    echo -e "  ${GREEN}✓${NC} GPU allocatable in cluster: $GPU_ALLOCATABLE"
                fi
                
                # Fail-fast if prerequisites not met
                if [ "$GPU_CHECK_FAILED" = "true" ]; then
                    echo ""
                    echo -e "${RED}❌ GPU prerequisites check FAILED${NC}"
                    echo -e "${RED}❌ LLM stack will not be deployed (ENABLE_GPU=true but GPU unavailable)${NC}"
                    echo ""
                    echo -e "${YELLOW}Options:${NC}"
                    echo -e "  1. Fix GPU prerequisites and retry"
                    echo -e "  2. Set ENABLE_GPU=false for CPU fallback (not recommended for production)"
                    echo -e "  3. Set ENABLE_LLM=false to skip LLM stack entirely"
                    echo ""
                    # Don't deploy LLM if GPU check failed
                    ENABLE_LLM=false
                    echo -e "${YELLOW}⚠️  ENABLE_LLM set to false due to GPU prerequisites failure${NC}"
                else
                    echo -e "  ${GREEN}✓${NC} GPU prerequisites OK, proceeding with LLM deployment"
                fi
            fi
            
            # Deploy LLM stack only if checks passed
            if [ "$ENABLE_LLM" = "true" ]; then
                kubectl apply -f "$K8S_DIR/overlays/local/embedding-service.yaml" 2>/dev/null || true
                kubectl apply -f "$K8S_DIR/overlays/local/llm-server.yaml" 2>/dev/null || true
                kubectl apply -f "$K8S_DIR/overlays/local/llm-service.yaml" 2>/dev/null || true
                
                # Set explicit env flags for LLM (Stage: perf/hardware)
                if [ "$ENABLE_GPU" = "true" ]; then
                    kubectl set env deployment/llm-server -n $NAMESPACE \
                        LLM_DEVICE=gpu \
                        ENABLE_GPU=true 2>/dev/null || true
                else
                    kubectl set env deployment/llm-server -n $NAMESPACE \
                        LLM_DEVICE=cpu \
                        ENABLE_GPU=false 2>/dev/null || true
                fi
            fi
        fi
        
        # Memory stack (если включен, требует ENABLE_LLM для embedding-service)
        if [ "$ENABLE_MEMORY" = "true" ]; then
            echo -e "  ${CYAN}📦 Deploying memory stack...${NC}"
            # Memory requires embedding-service for vector embeddings
            if [ "$ENABLE_LLM" != "true" ]; then
                echo -e "  ${YELLOW}⚠️  ENABLE_MEMORY=true requires embedding-service (auto-enabling)...${NC}"
                kubectl apply -f "$K8S_DIR/overlays/local/embedding-service.yaml" 2>/dev/null || true
            fi
            # Deploy storage first (postgres-pgvector)
            echo -e "  ${CYAN}  → postgres-pgvector${NC}"
            kubectl apply -f "$K8S_DIR/overlays/local/postgres-pgvector.yaml" 2>/dev/null || true
            # Scale to 1 replica (was 0 by default)
            kubectl scale statefulset postgres-pgvector --replicas=1 -n $NAMESPACE 2>/dev/null || true
            # Deploy memory-service after storage
            echo -e "  ${CYAN}  → memory-service${NC}"
            kubectl apply -f "$K8S_DIR/overlays/local/memory-service.yaml" 2>/dev/null || true
            # Scale to 1 replica (was 0 by default)
            kubectl scale deployment memory-service --replicas=1 -n $NAMESPACE 2>/dev/null || true
        else
            # Ensure memory stack is scaled down when disabled
            echo -e "  ${GRAY}⏭️  Memory stack disabled (ENABLE_MEMORY=false)${NC}"
            kubectl scale statefulset postgres-pgvector --replicas=0 -n $NAMESPACE 2>/dev/null || true
            kubectl scale deployment memory-service --replicas=0 -n $NAMESPACE 2>/dev/null || true
        fi
        
        # Если GPU отключен, патчим llm-server для CPU fallback
        if [ "$ENABLE_LLM" = "true" ] && [ "$ENABLE_GPU" = "false" ]; then
            echo -e "  ${YELLOW}⚠️  Disabling GPU (CPU fallback mode)...${NC}"
            kubectl set env deployment/llm-server -n $NAMESPACE \
                DEVICE=cpu \
                LLM_DEVICE=cpu \
                ENABLE_GPU=false 2>/dev/null || true
            # Remove GPU resource requests/limits
            kubectl patch deploy llm-server -n $NAMESPACE --type='json' \
                -p='[{"op":"remove","path":"/spec/template/spec/containers/0/resources/limits/nvidia.com~1gpu"},{"op":"remove","path":"/spec/template/spec/containers/0/resources/requests/nvidia.com~1gpu"}]' 2>/dev/null || true
            echo -e "  ${GREEN}✓${NC} LLM configured for CPU fallback"
        fi
        
        echo -e "${GREEN}✓${NC} LLM+Memory стек запущен"
    else
        echo -e "${YELLOW}⚠️  LLM overlay not found at $K8S_DIR/overlays/local${NC}"
    fi
    echo ""
fi

# === Ждём готовности ключевых сервисов ===
echo -e "${YELLOW}[5.5/7]${NC} ⏳ Жду готовности ключевых сервисов..."
wait_for_pod "security-service" 120
wait_for_pod "api-gateway" 120
wait_for_pod "life-tracker" 180
wait_for_pod "analytics-service" 180
wait_for_pod "voice-gateway" 120

# Ждём LLM+Memory если включено
if [ "$ENABLE_LLM" = "true" ] || [ "$ENABLE_MEMORY" = "true" ]; then
    echo ""
    echo -e "${YELLOW}[5.6/7]${NC} ⏳ Жду готовности LLM+Memory сервисов..."
    if [ "$ENABLE_LLM" = "true" ]; then
        wait_for_pod "embedding-service" 180
        wait_for_pod "llm-server" 600  # LLM загружает модель - долго
        wait_for_pod "llm-service" 120
    fi
    if [ "$ENABLE_MEMORY" = "true" ]; then
        wait_for_pod "postgres-pgvector" 180
        wait_for_pod "memory-service" 120
    fi
    
    # Проверка GPU если включен
    if [ "$ENABLE_GPU" = "true" ]; then
        echo ""
        echo -e "${CYAN}🔍 Проверка GPU в llm-server...${NC}"
        LLM_POD=$(kubectl get pod -n $NAMESPACE -l app=llm-server -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
        if [ -n "$LLM_POD" ]; then
            CUDA_OK=$(kubectl exec -n $NAMESPACE "$LLM_POD" -- python3 -c "import torch; print(torch.cuda.is_available())" 2>/dev/null || echo "Error")
            if [ "$CUDA_OK" = "True" ]; then
                echo -e "  ${GREEN}✓${NC} torch.cuda.is_available() = True"
            else
                echo -e "  ${YELLOW}⚠${NC} GPU недоступен (CUDA: $CUDA_OK)"
            fi
        fi
    fi
fi
echo ""

# === Настройка доступа ===
echo -e "${YELLOW}[6/7]${NC} 🔌 Настраиваю сетевой доступ..."

cleanup_port_forwards

# Stage 8: Определяем схему доступа (Ingress/TLS как default, NodePort/port-forward как fallback)
# Проверяем, активен ли TLS/Ingress
TLS_ACTIVE=false
if [ "${JARVIS_USE_TLS:-false}" = "true" ]; then
    TLS_ACTIVE=true
elif kubectl get ingress jarvis-ingress -n $NAMESPACE &>/dev/null; then
    # Проверяем, что ingress имеет правильные hosts
    INGRESS_HOSTS=$(kubectl get ingress jarvis-ingress -n $NAMESPACE -o jsonpath='{.spec.rules[*].host}' 2>/dev/null || echo "")
    if echo "$INGRESS_HOSTS" | grep -q "api.jarvis.local"; then
        TLS_ACTIVE=true
    fi
fi

# Проверяем порты (только если port-forward будет использоваться)
PORTS_OK=true
if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
    if check_port $API_GATEWAY_PORT; then
        echo -e "${YELLOW}⚠️  Порт $API_GATEWAY_PORT уже занят${NC}"
        PORTS_OK=false
    fi
    if check_port $VOICE_GATEWAY_PORT; then
        echo -e "${YELLOW}⚠️  Порт $VOICE_GATEWAY_PORT уже занят${NC}"
        PORTS_OK=false
    fi
fi

# Stage 8: Единый стандарт внешнего доступа
if [ "$TLS_ACTIVE" = "true" ]; then
    # TLS/Ingress активен - используем HTTPS как primary
    API_URL="https://api.jarvis.local"
    VOICE_WS_URL="wss://voice.jarvis.local"
    echo -e "${GREEN}✓${NC} API Gateway (HTTPS): $API_URL"
    echo -e "${GREEN}✓${NC} Voice Gateway (WSS): $VOICE_WS_URL"
    
    # NodePort только как DEBUG fallback
    if [ "$CLUSTER_TYPE" = "minikube" ]; then
        MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "127.0.0.1")
        NODE_PORT=$(kubectl get svc api-gateway -n $NAMESPACE -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "")
        if [ -n "$NODE_PORT" ]; then
            echo -e "${BLUE}  [DEBUG] NodePort fallback: http://$MINIKUBE_IP:$NODE_PORT${NC}"
        fi
    fi
    
    # Port-forward только если явно включен
    if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
        kubectl port-forward svc/api-gateway $API_GATEWAY_PORT:8080 -n $NAMESPACE &>/dev/null &
        echo -e "${BLUE}  [DEBUG] Port-forward: http://localhost:$API_GATEWAY_PORT${NC}"
    fi
else
    # TLS не активен - старое поведение (NodePort/port-forward)
    if [ "$CLUSTER_TYPE" = "minikube" ]; then
        MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "127.0.0.1")
        echo -e "${BLUE}ℹ️  Minikube IP: $MINIKUBE_IP${NC}"
        
        # API Gateway NodePort
        kubectl patch svc api-gateway -n $NAMESPACE -p '{"spec":{"type":"NodePort"}}' 2>/dev/null || true
        sleep 2
        NODE_PORT=$(kubectl get svc api-gateway -n $NAMESPACE -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "")
        
        if [ -n "$NODE_PORT" ]; then
            API_URL="http://$MINIKUBE_IP:$NODE_PORT"
            echo -e "${GREEN}✓${NC} API Gateway: $API_URL"
        else
            API_URL="http://localhost:$API_GATEWAY_PORT"
            if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
                kubectl port-forward svc/api-gateway $API_GATEWAY_PORT:8080 -n $NAMESPACE &>/dev/null &
                echo -e "${GREEN}✓${NC} API Gateway: $API_URL (port-forward)"
            fi
        fi
    else
        # Для других кластеров - port-forward (если включен)
        API_URL="http://localhost:$API_GATEWAY_PORT"
        if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
            kubectl port-forward svc/api-gateway $API_GATEWAY_PORT:8080 -n $NAMESPACE &>/dev/null &
            echo -e "${GREEN}✓${NC} API Gateway: $API_URL"
        fi
    fi
fi

# Voice Gateway и RabbitMQ - port-forward только если включен
if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
    kubectl port-forward svc/voice-gateway $VOICE_GATEWAY_PORT:8081 -n $NAMESPACE &>/dev/null &
    echo -e "${GREEN}✓${NC} Voice Gateway: http://localhost:$VOICE_GATEWAY_PORT (port-forward)"
    
    kubectl port-forward svc/rabbitmq $RABBITMQ_UI_PORT:15672 -n $NAMESPACE &>/dev/null &
    echo -e "${GREEN}✓${NC} RabbitMQ UI: http://localhost:$RABBITMQ_UI_PORT (port-forward)"
fi

sleep 3

# Проверяем доступность API Gateway
wait_for_gateway_health "$API_URL" 30
echo ""

# Stage 8: Дополнительная проверка TLS/Ingress после rollout
if [ "$TLS_ACTIVE" = "true" ]; then
    echo "  Проверяю TLS/Ingress..."
    # Проверка HTTPS endpoint (без -k)
    if curl -s --max-time 5 --cacert "$HOME/.jarvis/ca/jarvis-ca.crt" "$API_URL/actuator/health" &>/dev/null || \
       curl -s --max-time 5 "$API_URL/actuator/health" &>/dev/null; then
        echo -e "  ${GREEN}✓${NC} HTTPS endpoint доступен"
    else
        echo -e "  ${YELLOW}⚠️  HTTPS endpoint недоступен (возможно, CA не установлен)${NC}"
        echo -e "  ${BLUE}ℹ️  Выполни: sudo ./scripts/product/jarvis-install-tls.sh${NC}"
    fi
    
    # Проверка HTTP → HTTPS redirect
    HTTP_REDIRECT=$(curl -sI --max-time 5 "http://api.jarvis.local/actuator/health" 2>/dev/null | grep -i "location\|301\|308" || echo "")
    if [ -n "$HTTP_REDIRECT" ]; then
        echo -e "  ${GREEN}✓${NC} HTTP → HTTPS redirect работает"
    else
        echo -e "  ${YELLOW}⚠️  HTTP redirect не работает (проверь ingress)${NC}"
    fi
    echo ""
fi

# === Показываем статус ===
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║          📊 KUBERNETES STATUS          ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""

# Сохраняем статус в переменную и обрабатываем
PODS_STATUS=$(kubectl get pods -n $NAMESPACE --no-headers 2>/dev/null || echo "")
if [ -n "$PODS_STATUS" ]; then
    while IFS= read -r line; do
        if [ -n "$line" ]; then
            name=$(echo "$line" | awk '{print $1}')
            ready=$(echo "$line" | awk '{print $2}')
            status=$(echo "$line" | awk '{print $3}')
            if [ "$status" = "Running" ] && [ "$ready" = "1/1" ]; then
                echo -e "  ${GREEN}✓${NC} $name"
            elif [ "$status" = "Running" ]; then
                echo -e "  ${YELLOW}⏳${NC} $name ($ready)"
            elif [ "$status" = "Pending" ]; then
                echo -e "  ${YELLOW}⏳${NC} $name (starting...)"
            else
                echo -e "  ${RED}✗${NC} $name ($status)"
            fi
        fi
    done <<< "$PODS_STATUS"
else
    echo -e "  ${YELLOW}⚠️  Не удалось получить статус подов${NC}"
fi

echo ""
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║           🌐 ENDPOINTS                 ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""
# Stage 8: Показываем primary endpoints (Ingress/TLS если активен)
if [ "$TLS_ACTIVE" = "true" ]; then
    echo -e "  ${CYAN}API Gateway (HTTPS):${NC}    $API_URL"
    echo -e "  ${CYAN}Voice Gateway (WSS):${NC}    $VOICE_WS_URL"
    if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
        echo ""
        echo -e "  ${BLUE}[DEBUG] Port-forward endpoints:${NC}"
        echo -e "    Voice Gateway: http://localhost:$VOICE_GATEWAY_PORT"
        echo -e "    RabbitMQ UI:   http://localhost:$RABBITMQ_UI_PORT"
    fi
else
    echo -e "  ${CYAN}API Gateway:${NC}    $API_URL"
    if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
        echo -e "  ${CYAN}Voice Gateway:${NC}  http://localhost:$VOICE_GATEWAY_PORT"
        echo -e "  ${CYAN}RabbitMQ UI:${NC}    http://localhost:$RABBITMQ_UI_PORT"
    fi
fi

# LLM services port-forward только если включен
if [ "$ENABLE_LLM" = "true" ] && [ "$ENABLE_PORT_FORWARD" = "true" ]; then
    kubectl port-forward svc/llm-server 5000:5000 -n $NAMESPACE &>/dev/null &
    kubectl port-forward svc/llm-service 8091:8091 -n $NAMESPACE &>/dev/null &
    kubectl port-forward svc/embedding-service 5001:5001 -n $NAMESPACE &>/dev/null &
fi

# Memory service port-forward только если включен
if [ "$ENABLE_MEMORY" = "true" ] && [ "$ENABLE_PORT_FORWARD" = "true" ]; then
    kubectl port-forward svc/memory-service 8093:8093 -n $NAMESPACE &>/dev/null &
    sleep 2
    echo ""
    echo -e "  ${BLUE}[DEBUG] LLM services (port-forward):${NC}"
    echo -e "    LLM Server:     http://localhost:5000"
    echo -e "    LLM Service:    http://localhost:8091"
    echo -e "    Memory Service: http://localhost:8093"
    echo -e "    Embedding:      http://localhost:5001"
fi
echo ""

# === Сборка Desktop Client (если нужно) ===
echo -e "${YELLOW}[7/7]${NC} 🖥️  Подготовка Desktop Client..."

# Проверяем, нужна ли пересборка
REBUILD_NEEDED=false
if [ ! -f "$DESKTOP_JAR" ]; then
    REBUILD_NEEDED=true
    echo "  JAR не найден, требуется сборка..."
else
    # Проверяем, изменились ли исходники
    SRC_DIR="$PROJECT_DIR/apps/desktop-client-javafx/src"
    if [ -d "$SRC_DIR" ]; then
        NEWEST_SRC=$(find "$SRC_DIR" -name "*.kt" -newer "$DESKTOP_JAR" 2>/dev/null | head -1)
        if [ -n "$NEWEST_SRC" ]; then
            REBUILD_NEEDED=true
            echo "  Исходники изменились, пересобираю..."
        fi
    fi
fi

if [ "$REBUILD_NEEDED" = "true" ]; then
    cd "$PROJECT_DIR"
    echo "  Building desktop-client-javafx..."
    if mvn clean package -pl apps/desktop-client-javafx -am -DskipTests -q 2>/dev/null; then
        echo -e "  ${GREEN}✓${NC} Desktop client собран"
    else
        echo -e "  ${YELLOW}⚠️  Ошибка сборки. Попробуй: mvn clean package -pl apps/desktop-client-javafx -am${NC}"
    fi
else
    echo -e "  ${GREEN}✓${NC} Desktop client актуален"
fi
echo ""

# === Загружаем переменные окружения ===
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    source "$PROJECT_DIR/.env" 2>/dev/null || true
    set +a
fi

export API_URL
export JARVIS_API_BASE_URL="$API_URL"

# === Запускаем desktop-клиент ===
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║      🖥️  ЗАПУСК DESKTOP CLIENT        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""

if [ -f "$DESKTOP_JAR" ]; then
    echo -e "${GREEN}🚀 Запускаю Jarvis Desktop...${NC}"
    echo ""
    
    # Запускаем в фоне
    java -jar "$DESKTOP_JAR" &
    DESKTOP_PID=$!
    
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║   ✅ JARVIS 2.0 УСПЕШНО ЗАПУЩЕН!      ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "  ${CYAN}API Gateway:${NC}      $API_URL"
    echo -e "  ${CYAN}Desktop PID:${NC}      $DESKTOP_PID"
    echo -e "  ${CYAN}Для логов:${NC}        ./jarvis-logs.sh"
    echo ""
    echo -e "${YELLOW}💡 Кластер продолжит работать после закрытия этого окна.${NC}"
    echo -e "${YELLOW}   Для остановки: ./jarvis-stop.sh${NC}"
    echo ""
    
    # Ждём завершения desktop клиента
    wait $DESKTOP_PID 2>/dev/null || true
    
else
    echo -e "${RED}❌ JAR не найден: $DESKTOP_JAR${NC}"
    echo -e "${BLUE}ℹ️${NC}  Собери проект: mvn clean package -pl apps/desktop-client-javafx -am -DskipTests"
    echo ""
    echo -e "${YELLOW}💡${NC} Можешь открыть API в браузере:"
    echo "   $API_URL/actuator/health"
    echo ""
fi

# Финальное сообщение
if [ "$HAD_ERROR" = "true" ]; then
    echo ""
    echo -e "${YELLOW}⚠️  Во время запуска были ошибки. Проверь логи: ./jarvis-logs.sh${NC}"
fi

echo ""
read -p "Нажми Enter для выхода..."
