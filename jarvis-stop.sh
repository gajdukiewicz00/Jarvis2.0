#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Kubernetes Stop Script
# =============================================================================
# Останавливает все ресурсы Jarvis в Kubernetes
# =============================================================================

PROJECT_DIR="/home/kwaqa/IdeaProjects/Jarvis2.0"
NAMESPACE="jarvis"

# Colors
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${RED}╔════════════════════════════════════════╗${NC}"
echo -e "${RED}║   🛑 JARVIS KUBERNETES SHUTDOWN 🛑   ║${NC}"
echo -e "${RED}╚════════════════════════════════════════╝${NC}"
echo ""

# Проверяем kubectl
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}❌ kubectl не найден${NC}"
    exit 1
fi

# Останавливаем port-forward процессы
echo -e "${YELLOW}⏳${NC} Останавливаю port-forward..."
pkill -f "kubectl port-forward.*jarvis" 2>/dev/null || true
echo -e "${GREEN}✓${NC} Port-forward остановлен"

# Показываем текущее состояние
echo ""
echo -e "${BLUE}📊 Текущие ресурсы в namespace $NAMESPACE:${NC}"
kubectl get all -n $NAMESPACE --no-headers 2>/dev/null | head -20

echo ""
read -p "Удалить все ресурсы Jarvis? [y/N] " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo ""
    echo -e "${YELLOW}⏳${NC} Удаляю микросервисы..."
    kubectl delete -f "$PROJECT_DIR/k8s/dev/services/" --ignore-not-found 2>/dev/null || true
    kubectl delete -f "$PROJECT_DIR/k8s/dev/ingress.yaml" --ignore-not-found 2>/dev/null || true

    echo -e "${YELLOW}⏳${NC} Удаляю Kafka..."
    kubectl delete -f "$PROJECT_DIR/k8s/dev/kafka/" --ignore-not-found 2>/dev/null || true

    echo -e "${YELLOW}⏳${NC} Удаляю RabbitMQ..."
    kubectl delete -f "$PROJECT_DIR/k8s/dev/rabbitmq/" --ignore-not-found 2>/dev/null || true

    echo -e "${YELLOW}⏳${NC} Удаляю PostgreSQL..."
    kubectl delete -f "$PROJECT_DIR/k8s/dev/postgres/" --ignore-not-found 2>/dev/null || true

    echo ""
    read -p "Удалить также базовые ресурсы (secrets, configmaps)? [y/N] " -n 1 -r
    echo ""

    if [[ $REPLY =~ ^[Yy]$ ]]; then
        kubectl delete -f "$PROJECT_DIR/k8s/base/" --ignore-not-found 2>/dev/null || true
        echo -e "${GREEN}✓${NC} Базовые ресурсы удалены"
    fi

    echo ""
    echo -e "${GREEN}✓${NC} Все ресурсы Jarvis удалены"
else
    echo -e "${BLUE}ℹ️${NC}  Отменено. Ресурсы не удалены."
fi

echo ""
echo -e "${YELLOW}💡${NC} Для запуска снова: ./jarvis-launch.sh"
echo -e "${YELLOW}💡${NC} Или кликни на иконку 'Jarvis 2.0' в меню приложений"

