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
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

# Проверяем kubectl
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}❌ kubectl не найден${NC}"
    echo ""
    read -p "Нажми Enter для выхода..."
    exit 1
fi

clear
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║    📋 JARVIS K8S LOGS VIEWER 📋      ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""

# Показываем статус подов
echo -e "${GREEN}📊 Текущее состояние подов:${NC}"
echo ""

PODS_STATUS=$(kubectl get pods -n $NAMESPACE --no-headers 2>/dev/null || echo "")
if [ -n "$PODS_STATUS" ]; then
    while IFS= read -r line; do
        if [ -n "$line" ]; then
            name=$(echo "$line" | awk '{print $1}')
            ready=$(echo "$line" | awk '{print $2}')
            status=$(echo "$line" | awk '{print $3}')
            restarts=$(echo "$line" | awk '{print $4}')
            age=$(echo "$line" | awk '{print $5}')
            
            if [ "$status" = "Running" ] && [ "$ready" = "1/1" ]; then
                echo -e "  ${GREEN}✓${NC} $name ${CYAN}($age)${NC}"
            elif [ "$status" = "Running" ]; then
                echo -e "  ${YELLOW}⏳${NC} $name ($ready, $restarts restarts)"
            elif [ "$status" = "Pending" ]; then
                echo -e "  ${YELLOW}⏳${NC} $name (starting...)"
            elif [ "$status" = "CrashLoopBackOff" ] || [ "$status" = "Error" ]; then
                echo -e "  ${RED}✗${NC} $name (${RED}$status${NC}, $restarts restarts)"
            else
                echo -e "  ${YELLOW}⚠️${NC} $name ($status)"
            fi
        fi
    done <<< "$PODS_STATUS"
else
    echo -e "  ${YELLOW}⚠️  Поды не найдены в namespace $NAMESPACE${NC}"
    echo ""
    echo -e "  Проверь:"
    echo -e "    kubectl get namespaces"
    echo -e "    ./jarvis-launch.sh"
    echo ""
    read -p "Нажми Enter для выхода..."
    exit 0
fi

echo ""
echo -e "${YELLOW}💡${NC} Нажми Ctrl+C чтобы остановить просмотр логов"
echo ""

# Меню выбора
while true; do
    echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║         ВЫБЕРИ СЕРВИС ДЛЯ ЛОГОВ        ║${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "  ${CYAN}Основные сервисы:${NC}"
    echo "    1) api-gateway"
    echo "    2) security-service"
    echo "    3) life-tracker"
    echo "    4) analytics-service"
    echo ""
    echo -e "  ${CYAN}Вспомогательные:${NC}"
    echo "    5) voice-gateway"
    echo "    6) pc-control"
    echo "    7) planner-service"
    echo "    8) user-profile"
    echo "    9) orchestrator"
    echo ""
    echo -e "  ${CYAN}Инфраструктура:${NC}"
    echo "    p) postgres"
    echo "    r) rabbitmq"
    echo ""
    echo -e "  ${CYAN}Специальное:${NC}"
    echo "    a) Все ошибки (grep ERROR/WARN/Exception)"
    echo "    s) Все поды (stern, если установлен)"
    echo "    c) Ввести имя пода вручную"
    echo ""
    echo "    0) Обновить статус"
    echo "    q) Выход"
    echo ""
    read -p "Выбор: " choice
    
    case $choice in
        1)
            echo -e "${CYAN}📜 Логи api-gateway:${NC}"
            kubectl logs -f deployment/api-gateway -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        2)
            echo -e "${CYAN}📜 Логи security-service:${NC}"
            kubectl logs -f deployment/security-service -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        3)
            echo -e "${CYAN}📜 Логи life-tracker:${NC}"
            kubectl logs -f deployment/life-tracker -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        4)
            echo -e "${CYAN}📜 Логи analytics-service:${NC}"
            kubectl logs -f deployment/analytics-service -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        5)
            echo -e "${CYAN}📜 Логи voice-gateway:${NC}"
            kubectl logs -f deployment/voice-gateway -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        6)
            echo -e "${CYAN}📜 Логи pc-control:${NC}"
            kubectl logs -f deployment/pc-control -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        7)
            echo -e "${CYAN}📜 Логи planner-service:${NC}"
            kubectl logs -f deployment/planner-service -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        8)
            echo -e "${CYAN}📜 Логи user-profile:${NC}"
            kubectl logs -f deployment/user-profile -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        9)
            echo -e "${CYAN}📜 Логи orchestrator:${NC}"
            kubectl logs -f deployment/orchestrator -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        p|P)
            echo -e "${CYAN}📜 Логи postgres:${NC}"
            kubectl logs -f deployment/postgres -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        r|R)
            echo -e "${CYAN}📜 Логи rabbitmq:${NC}"
            kubectl logs -f deployment/rabbitmq -n $NAMESPACE --tail=100 2>/dev/null || {
                echo -e "${RED}Не удалось получить логи${NC}"
            }
            ;;
        a|A)
            echo -e "${RED}🔍 Фильтрация: ошибки и предупреждения${NC}"
            echo ""
            # Получаем логи всех подов и фильтруем
            for pod in $(kubectl get pods -n $NAMESPACE --no-headers -o custom-columns=":metadata.name" 2>/dev/null); do
                echo -e "${CYAN}=== $pod ===${NC}"
                kubectl logs "$pod" -n $NAMESPACE --tail=50 2>/dev/null | \
                    grep -i -E "(error|exception|failed|warn|fatal)" --color=always || true
                echo ""
            done
            echo -e "${YELLOW}Нажми Enter для продолжения...${NC}"
            read
            ;;
        s|S)
            if command -v stern &> /dev/null; then
                echo -e "${CYAN}📜 Все поды (stern):${NC}"
                stern ".*" -n $NAMESPACE --tail 50
            else
                echo -e "${YELLOW}⚠️ stern не установлен.${NC}"
                echo "Установка: go install github.com/stern/stern@latest"
                echo ""
                echo "Показываю логи через kubectl..."
                kubectl logs -f -l app.kubernetes.io/part-of=jarvis -n $NAMESPACE --all-containers --prefix --tail=50 2>/dev/null || {
                    # Fallback если label не найден
                    echo -e "${YELLOW}Показываю логи всех подов...${NC}"
                    for pod in $(kubectl get pods -n $NAMESPACE --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -5); do
                        echo -e "${CYAN}=== $pod ===${NC}"
                        kubectl logs "$pod" -n $NAMESPACE --tail=20 2>/dev/null || true
                    done
                }
            fi
            ;;
        c|C)
            echo ""
            echo "Доступные поды:"
            kubectl get pods -n $NAMESPACE --no-headers -o custom-columns=":metadata.name" 2>/dev/null
            echo ""
            read -p "Введи имя пода: " pod_name
            if [ -n "$pod_name" ]; then
                kubectl logs -f "$pod_name" -n $NAMESPACE --tail=100 2>/dev/null || {
                    echo -e "${RED}Не удалось получить логи для $pod_name${NC}"
                }
            fi
            ;;
        0)
            clear
            echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
            echo -e "${BLUE}║    📋 JARVIS K8S LOGS VIEWER 📋      ║${NC}"
            echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
            echo ""
            echo -e "${GREEN}📊 Текущее состояние подов:${NC}"
            echo ""
            PODS_STATUS=$(kubectl get pods -n $NAMESPACE --no-headers 2>/dev/null || echo "")
            if [ -n "$PODS_STATUS" ]; then
                while IFS= read -r line; do
                    if [ -n "$line" ]; then
                        name=$(echo "$line" | awk '{print $1}')
                        ready=$(echo "$line" | awk '{print $2}')
                        status=$(echo "$line" | awk '{print $3}')
                        restarts=$(echo "$line" | awk '{print $4}')
                        
                        if [ "$status" = "Running" ] && [ "$ready" = "1/1" ]; then
                            echo -e "  ${GREEN}✓${NC} $name"
                        elif [ "$status" = "Running" ]; then
                            echo -e "  ${YELLOW}⏳${NC} $name ($ready, $restarts restarts)"
                        elif [ "$status" = "CrashLoopBackOff" ] || [ "$status" = "Error" ]; then
                            echo -e "  ${RED}✗${NC} $name (${RED}$status${NC})"
                        else
                            echo -e "  ${YELLOW}⚠️${NC} $name ($status)"
                        fi
                    fi
                done <<< "$PODS_STATUS"
            fi
            echo ""
            ;;
        q|Q)
            echo -e "${GREEN}👋 До свидания!${NC}"
            exit 0
            ;;
        *)
            echo -e "${YELLOW}Неверный выбор. Попробуй снова.${NC}"
            ;;
    esac
    echo ""
done
