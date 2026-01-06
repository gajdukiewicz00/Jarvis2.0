#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Kubernetes Launch Script
# =============================================================================
# Запускает весь стек в Kubernetes + desktop-клиент
# =============================================================================

set -e

# === Настройки ===
PROJECT_DIR="/home/kwaqa/IdeaProjects/Jarvis2.0"
DESKTOP_JAR="$PROJECT_DIR/apps/desktop-client-javafx/target/desktop-client-javafx-0.1.0-SNAPSHOT.jar"
K8S_DIR="$PROJECT_DIR/k8s"
NAMESPACE="jarvis"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   🤖 JARVIS AI (Kubernetes) 🤖       ║${NC}"
echo -e "${BLUE}║         Starting up...                 ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""

cd "$PROJECT_DIR" || {
    echo -e "${RED}❌ Не могу перейти в $PROJECT_DIR${NC}"
    exit 1
}

# === Проверка kubectl ===
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}❌ kubectl не найден. Установи kubectl:${NC}"
    echo "   https://kubernetes.io/docs/tasks/tools/"
    exit 1
fi

# === Проверка кластера (minikube, k3s, kind) ===
check_cluster() {
    if command -v minikube &> /dev/null && minikube status | grep -q "Running"; then
        echo "minikube"
    elif command -v k3s &> /dev/null || systemctl is-active --quiet k3s 2>/dev/null; then
        echo "k3s"
    elif command -v kind &> /dev/null && kind get clusters 2>/dev/null | grep -q .; then
        echo "kind"
    elif kubectl cluster-info &> /dev/null; then
        echo "other"
    else
        echo "none"
    fi
}

CLUSTER_TYPE=$(check_cluster)

if [ "$CLUSTER_TYPE" = "none" ]; then
    echo -e "${YELLOW}⚠️  Kubernetes кластер не найден. Запускаю minikube...${NC}"
    
    if command -v minikube &> /dev/null; then
        minikube start --memory=8192 --cpus=4 --driver=docker
        CLUSTER_TYPE="minikube"
    else
        echo -e "${RED}❌ minikube не установлен. Установи:${NC}"
        echo "   curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64"
        echo "   sudo install minikube-linux-amd64 /usr/local/bin/minikube"
        exit 1
    fi
fi

echo -e "${GREEN}✓${NC} Кластер: $CLUSTER_TYPE"

# === Проверка/генерация сертификатов ===
if [ ! -f "$PROJECT_DIR/docker/certs/jarvis.crt" ]; then
    echo -e "${YELLOW}[1/6]${NC} 🔐 Генерирую TLS сертификаты..."
    if [ -x "$PROJECT_DIR/scripts/generate-certs.sh" ]; then
        bash "$PROJECT_DIR/scripts/generate-certs.sh"
    else
        mkdir -p "$PROJECT_DIR/docker/certs"
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
            -keyout "$PROJECT_DIR/docker/certs/jarvis.key" \
            -out "$PROJECT_DIR/docker/certs/jarvis.crt" \
            -subj "/CN=jarvis.local/O=Jarvis" \
            -addext "subjectAltName=DNS:jarvis.local,DNS:*.jarvis.local,IP:127.0.0.1"
    fi
    echo -e "${GREEN}✓${NC} Сертификаты сгенерированы"
else
    echo -e "${GREEN}✓${NC} Сертификаты уже существуют"
fi

# === Сборка Docker-образов ===
echo -e "${YELLOW}[2/6]${NC} 🐳 Собираю Docker-образы..."

# Для minikube используем встроенный Docker
if [ "$CLUSTER_TYPE" = "minikube" ]; then
    eval $(minikube docker-env)
fi

# Собираем образы
SERVICES=(
    "api-gateway"
    "security-service"
    "life-tracker"
    "analytics-service"
    "voice-gateway"
    "pc-control"
    "smart-home-service"
    "assistant-core"
    "nlp-service"
    "orchestrator"
    "user-profile"
    "llm-service"
    "planner-service"
)

for svc in "${SERVICES[@]}"; do
    if [ -f "$PROJECT_DIR/apps/$svc/Dockerfile" ]; then
        echo "  Building jarvis/$svc..."
        docker build -t "jarvis/$svc:latest" "$PROJECT_DIR/apps/$svc" -q
    fi
done


# LLM Server (Python)
if [ -f "$PROJECT_DIR/docker/llm-server/Dockerfile" ]; then
    echo "  Building jarvis/llm-server..."
    docker build -t "jarvis/llm-server:latest" "$PROJECT_DIR/docker/llm-server" -q
fi

echo -e "${GREEN}✓${NC} Docker-образы собраны"

# === Сборка desktop-client ===
echo -e "${BLUE}ℹ️  Пересборка desktop client...${NC}"
cd "$PROJECT_DIR"
mvn clean package -pl apps/desktop-client-javafx -am -DskipTests -q 2>/dev/null || {
    echo -e "${YELLOW}⚠️  Desktop client уже собран или произошла ошибка${NC}"
}


# === Загрузка образов в кластер (для kind) ===
if [ "$CLUSTER_TYPE" = "kind" ]; then
    echo -e "${YELLOW}[2.5/6]${NC} 📦 Загружаю образы в kind..."
    for svc in "${SERVICES[@]}"; do
        kind load docker-image "jarvis/$svc:latest" --name $(kind get clusters | head -1)
    done
    kind load docker-image "jarvis/llm-server:latest" --name $(kind get clusters | head -1)
fi

# === Применяем Kubernetes манифесты ===
echo -e "${YELLOW}[3/6]${NC} ☸️  Применяю Kubernetes манифесты..."

# Namespace и базовые ресурсы
kubectl apply -f "$K8S_DIR/base/namespace.yaml"
kubectl apply -f "$K8S_DIR/base/configmap.yaml"
kubectl apply -f "$K8S_DIR/base/secrets.yaml"

# TLS Secret
if [ -f "$K8S_DIR/base/tls-secret-generated.yaml" ]; then
    kubectl apply -f "$K8S_DIR/base/tls-secret-generated.yaml"
fi

# Инфраструктура (dev профиль - 1 нода)
echo "  Applying PostgreSQL..."
kubectl apply -f "$K8S_DIR/dev/postgres/"

echo "  Applying RabbitMQ..."
kubectl apply -f "$K8S_DIR/dev/rabbitmq/"

echo "  Applying Kafka..."
kubectl apply -f "$K8S_DIR/dev/kafka/"

echo -e "${GREEN}✓${NC} Инфраструктура применена"

# === Ждём готовности инфраструктуры ===
echo -e "${YELLOW}[4/6]${NC} ⏳ Жду готовности инфраструктуры..."

wait_for_pod() {
    local label=$1
    local timeout=${2:-180}
    echo "  Waiting for $label..."
    kubectl wait --for=condition=ready pod -l "app=$label" -n $NAMESPACE --timeout="${timeout}s" 2>/dev/null || {
        echo -e "${YELLOW}  ⚠️ $label ещё не готов, продолжаю...${NC}"
    }
}

wait_for_pod "postgres" 120
wait_for_pod "rabbitmq" 120
wait_for_pod "zookeeper" 60
wait_for_pod "kafka" 120

echo -e "${GREEN}✓${NC} Инфраструктура готова"

# === Применяем микросервисы ===
echo -e "${YELLOW}[5/6]${NC} 🚀 Запускаю микросервисы..."
kubectl apply -f "$K8S_DIR/dev/services/"

# Ingress
kubectl apply -f "$K8S_DIR/dev/ingress.yaml" 2>/dev/null || true

# Kafka topics
kubectl apply -f "$K8S_DIR/dev/kafka/topics-init-job.yaml" 2>/dev/null || true

echo -e "${GREEN}✓${NC} Микросервисы запущены"

# === Port-forward для доступа ===
echo -e "${YELLOW}[6/6]${NC} 🔌 Настраиваю доступ..."

# Останавливаем предыдущие port-forward
pkill -f "kubectl port-forward.*jarvis" 2>/dev/null || true

# Для minikube используем NodePort для стабильного доступа
if [ "$CLUSTER_TYPE" = "minikube" ]; then
    # Получаем IP minikube
    MINIKUBE_IP=$(minikube ip)
    echo -e "${BLUE}ℹ️  Minikube IP: $MINIKUBE_IP${NC}"
    
    # Изменяем тип сервиса API Gateway на NodePort
    kubectl patch svc api-gateway -n $NAMESPACE -p '{"spec":{"type":"NodePort"}}' 2>/dev/null || true
    
    # Ждём обновления сервиса
    sleep 2
    
    # Получаем NodePort
    NODE_PORT=$(kubectl get svc api-gateway -n $NAMESPACE -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)
    
    if [ -n "$NODE_PORT" ]; then
        API_URL="http://$MINIKUBE_IP:$NODE_PORT"
        echo -e "${GREEN}✓${NC} API Gateway: $API_URL"
        
        # Обновляем URL в desktop client
        echo -e "${BLUE}ℹ️  Обновляю конфигурацию desktop client...${NC}"
        
        # Обновляем LoginController.kt
        sed -i "s|private val baseUrl = \"http://[^\"]*\"|private val baseUrl = \"$API_URL\"|" \
            "$PROJECT_DIR/apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/controller/LoginController.kt" 2>/dev/null || true
        
        # Обновляем DesktopApplication.kt (два места)
        sed -i "s|private val baseUrl = \"http://[^\"]*/api/v1\"|private val baseUrl = \"$API_URL/api/v1\"|" \
            "$PROJECT_DIR/apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/DesktopApplication.kt" 2>/dev/null || true
        sed -i "s|AuthService(baseUrl = \"http://[^\"]*\")|AuthService(baseUrl = \"$API_URL\")|" \
            "$PROJECT_DIR/apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/DesktopApplication.kt" 2>/dev/null || true
        
        echo -e "${GREEN}✓${NC} Desktop client сконфигурирован для $API_URL"
    else
        echo -e "${RED}❌ Не удалось получить NodePort${NC}"
        API_URL="http://localhost:8080"
    fi
else
    # Для других кластеров - port-forward
    kubectl port-forward svc/api-gateway 8080:8080 -n $NAMESPACE &>/dev/null &
    API_URL="http://localhost:8080"
    echo -e "${GREEN}✓${NC} API Gateway: $API_URL"
fi

# RabbitMQ Management
kubectl port-forward svc/rabbitmq 15672:15672 -n $NAMESPACE &>/dev/null &
echo -e "${GREEN}✓${NC} RabbitMQ UI: http://localhost:15672"

sleep 3

# === Показываем статус ===
echo ""
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║          📊 KUBERNETES STATUS          ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""
kubectl get pods -n $NAMESPACE --no-headers 2>/dev/null | while read line; do
    name=$(echo $line | awk '{print $1}')
    status=$(echo $line | awk '{print $3}')
    if [ "$status" = "Running" ]; then
        echo -e "  ${GREEN}✓${NC} $name"
    elif [ "$status" = "Pending" ]; then
        echo -e "  ${YELLOW}⏳${NC} $name (starting...)"
    else
        echo -e "  ${RED}✗${NC} $name ($status)"
    fi
done

# === Загружаем переменные окружения ===
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    source "$PROJECT_DIR/.env"
    set +a
fi

# Устанавливаем API URL для desktop-клиента (уже установлен выше)
# export API_URL уже настроен в секции NodePort

echo ""
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║      🖥️  ЗАПУСК DESKTOP CLIENT        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""

# === Запускаем desktop-клиент ===
if [ -f "$DESKTOP_JAR" ]; then
    java -jar "$DESKTOP_JAR"
else
    echo -e "${RED}❌ JAR не найден: $DESKTOP_JAR${NC}"
    echo -e "${BLUE}ℹ️${NC}  Собери проект: mvn clean package -DskipTests"
    echo ""
    echo -e "${YELLOW}💡${NC} Можешь открыть API в браузере: http://localhost:8080/actuator/health"
    
    # Держим скрипт активным
    read -p "Нажми Enter для выхода..."
fi

