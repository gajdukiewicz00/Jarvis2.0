#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Kubernetes Logs Viewer
# =============================================================================
# Просмотр логов всех сервисов Jarvis в Kubernetes
# =============================================================================

NAMESPACE="jarvis"

# Colors
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║    📋 JARVIS K8S LOGS VIEWER 📋      ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""
echo -e "${YELLOW}💡${NC} Нажми Ctrl+C чтобы остановить просмотр"
echo ""

# Показываем список подов
echo -e "${GREEN}📊 Текущие поды:${NC}"
kubectl get pods -n $NAMESPACE --no-headers | while read line; do
    name=$(echo $line | awk '{print $1}')
    status=$(echo $line | awk '{print $3}')
    echo "  • $name ($status)"
done
echo ""

# Меню выбора
echo -e "${BLUE}Выбери вариант просмотра логов:${NC}"
echo "  1) Все поды (stern)"
echo "  2) api-gateway"
echo "  3) security-service"
echo "  4) life-tracker"
echo "  5) voice-gateway"
echo "  6) Все ошибки (grep ERROR)"
echo "  7) Ввести имя пода вручную"
echo ""
read -p "Выбор [1-7]: " choice

case $choice in
    1)
        if command -v stern &> /dev/null; then
            stern ".*" -n $NAMESPACE --tail 50
        else
            echo -e "${YELLOW}⚠️ stern не установлен. Используй: brew install stern или go install github.com/stern/stern@latest${NC}"
            echo "Показываю логи через kubectl..."
            kubectl logs -f -l app.kubernetes.io/part-of=jarvis -n $NAMESPACE --all-containers --prefix --tail=100
        fi
        ;;
    2)
        kubectl logs -f deployment/api-gateway -n $NAMESPACE --tail=100
        ;;
    3)
        kubectl logs -f deployment/security-service -n $NAMESPACE --tail=100
        ;;
    4)
        kubectl logs -f deployment/life-tracker -n $NAMESPACE --tail=100
        ;;
    5)
        kubectl logs -f deployment/voice-gateway -n $NAMESPACE --tail=100
        ;;
    6)
        echo -e "${YELLOW}🔍 Фильтрация: показываю только ошибки${NC}"
        echo ""
        kubectl logs -f -l app.kubernetes.io/part-of=jarvis -n $NAMESPACE --all-containers --tail=100 2>&1 | grep -i -E "(error|exception|failed|warn)" --line-buffered --color=always
        ;;
    7)
        read -p "Введи имя пода: " pod_name
        kubectl logs -f "$pod_name" -n $NAMESPACE --tail=100
        ;;
    *)
        echo "Неверный выбор"
        exit 1
        ;;
esac

