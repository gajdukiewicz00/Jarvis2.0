#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Kubernetes Stop Script (prod-only)
# =============================================================================
# Stops all Jarvis resources in the jarvis namespace.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}"
K8S_DIR="${PROJECT_DIR}/k8s"
NAMESPACE="jarvis"
JARVIS_HOME="${HOME}/.jarvis"

YES=false
PURGE=false

for arg in "$@"; do
    case "$arg" in
        --yes|-y)
            YES=true
            ;;
        --purge)
            PURGE=true
            ;;
        --help|-h)
            echo "Usage: $0 [--yes] [--purge]"
            exit 0
            ;;
    esac
done

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

# Останавливаем port-forward процессы (debug)
echo -e "${YELLOW}⏳${NC} Останавливаю port-forward..."
pkill -f "kubectl port-forward.*jarvis" 2>/dev/null || true
echo -e "${GREEN}✓${NC} Port-forward остановлен"

echo ""
if [[ "$YES" != "true" ]]; then
    read -p "Удалить все ресурсы Jarvis в namespace '${NAMESPACE}'? [y/N] " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${BLUE}ℹ️${NC}  Отменено. Ресурсы не удалены."
        exit 0
    fi
fi

# Delete overlay resources
if [ -d "${K8S_DIR}/overlays/prod" ]; then
    echo -e "${YELLOW}⏳${NC} Удаляю ресурсы (kustomize overlay)..."
    MODELS_PATH="${JARVIS_HOME}/models"
    MODELS_PATH_ENV_FILE="${K8S_DIR}/overlays/prod/models-path.env"
    printf "JARVIS_MODELS_PATH=%s\n" "${MODELS_PATH}" > "${MODELS_PATH_ENV_FILE}"
    kubectl kustomize --load-restrictor=LoadRestrictionsNone "${K8S_DIR}/overlays/prod" | \
        awk '
            function flush_doc() {
                if (doc == "") {
                    return
                }
                if (!is_namespace) {
                    if (printed > 0) {
                        printf("---\n")
                    }
                    printf("%s", doc)
                    printed++
                }
                doc = ""
                is_namespace = 0
            }
            /^---[[:space:]]*$/ {
                flush_doc()
                next
            }
            {
                doc = doc $0 "\n"
                if ($0 ~ /^kind:[[:space:]]*Namespace([[:space:]]*#.*)?$/) {
                    is_namespace = 1
                }
            }
            END {
                flush_doc()
            }
        ' | kubectl delete -f - --ignore-not-found=true >/dev/null 2>&1 || true
else
    echo -e "${YELLOW}⚠️${NC} Overlay not found: ${K8S_DIR}/overlays/prod"
fi

# Scale down any remaining deployments/statefulsets
kubectl scale deployment --all --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
kubectl scale statefulset --all --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true

echo -e "${GREEN}✓${NC} Jarvis workloads stopped"

if [[ "$PURGE" == "true" ]]; then
    echo -e "${YELLOW}⏳${NC} Purge: удаляю namespace ${NAMESPACE}..."
    kubectl delete namespace "${NAMESPACE}" --ignore-not-found=true >/dev/null 2>&1 || true
    echo -e "${GREEN}✓${NC} Namespace удален"
else
    echo -e "${BLUE}ℹ️${NC} Secrets и namespace сохранены"
fi

echo ""
echo -e "${YELLOW}💡${NC} Для запуска снова: ./jarvis-launch.sh"
