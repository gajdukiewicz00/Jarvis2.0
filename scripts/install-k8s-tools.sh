#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Kubernetes Tools Installer
# =============================================================================
# Устанавливает необходимые инструменты для запуска Jarvis в Kubernetes
# =============================================================================

set -e

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   🛠️  KUBERNETES TOOLS INSTALLER      ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""

# === kubectl ===
if command -v kubectl &> /dev/null; then
    echo -e "${GREEN}✓${NC} kubectl уже установлен: $(kubectl version --client --short 2>/dev/null || kubectl version --client 2>/dev/null | head -1)"
else
    echo -e "${YELLOW}⏳${NC} Устанавливаю kubectl..."
    curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
    chmod +x kubectl
    sudo mv kubectl /usr/local/bin/
    echo -e "${GREEN}✓${NC} kubectl установлен"
fi

# === minikube ===
if command -v minikube &> /dev/null; then
    echo -e "${GREEN}✓${NC} minikube уже установлен: $(minikube version --short 2>/dev/null || minikube version | head -1)"
else
    echo -e "${YELLOW}⏳${NC} Устанавливаю minikube..."
    curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
    chmod +x minikube-linux-amd64
    sudo mv minikube-linux-amd64 /usr/local/bin/minikube
    echo -e "${GREEN}✓${NC} minikube установлен"
fi

# === helm (опционально) ===
if command -v helm &> /dev/null; then
    echo -e "${GREEN}✓${NC} helm уже установлен: $(helm version --short 2>/dev/null)"
else
    echo ""
    read -p "Установить Helm? (рекомендуется для будущего) [y/N] " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
        echo -e "${GREEN}✓${NC} helm установлен"
    fi
fi

# === stern (просмотр логов) ===
if command -v stern &> /dev/null; then
    echo -e "${GREEN}✓${NC} stern уже установлен"
else
    echo ""
    read -p "Установить Stern (удобный просмотр логов K8s)? [y/N] " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        if command -v go &> /dev/null; then
            go install github.com/stern/stern@latest
            echo -e "${GREEN}✓${NC} stern установлен"
        else
            echo -e "${YELLOW}⚠️${NC} Go не установлен. Устанавливаю через GitHub releases..."
            STERN_VERSION=$(curl -s https://api.github.com/repos/stern/stern/releases/latest | grep tag_name | cut -d '"' -f 4)
            curl -LO "https://github.com/stern/stern/releases/download/${STERN_VERSION}/stern_${STERN_VERSION#v}_linux_amd64.tar.gz"
            tar xzf "stern_${STERN_VERSION#v}_linux_amd64.tar.gz"
            sudo mv stern /usr/local/bin/
            rm -f "stern_${STERN_VERSION#v}_linux_amd64.tar.gz" LICENSE
            echo -e "${GREEN}✓${NC} stern установлен"
        fi
    fi
fi

# === Проверка Docker ===
if command -v docker &> /dev/null; then
    echo -e "${GREEN}✓${NC} docker установлен: $(docker --version)"
    
    # Проверяем, можно ли запускать docker без sudo
    if ! docker ps &> /dev/null; then
        echo -e "${YELLOW}⚠️${NC} Docker требует sudo. Добавляю пользователя в группу docker..."
        sudo usermod -aG docker $USER
        echo -e "${YELLOW}⚠️${NC} Перезайди в систему для применения изменений"
    fi
else
    echo -e "${RED}❌${NC} Docker не установлен!"
    echo "   Установи: https://docs.docker.com/engine/install/"
    exit 1
fi

echo ""
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         📋 QUICK START GUIDE          ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""
echo -e "${GREEN}1.${NC} Запусти minikube:"
echo "   minikube start --memory=8192 --cpus=4"
echo ""
echo -e "${GREEN}2.${NC} Запусти Jarvis:"
echo "   ./jarvis-k8s-launch.sh"
echo ""
echo -e "${GREEN}3.${NC} Или кликни на иконку 'Jarvis K8s' в меню приложений"
echo ""
echo -e "${YELLOW}💡${NC} Для добавления в /etc/hosts:"
echo "   echo '127.0.0.1 jarvis.local' | sudo tee -a /etc/hosts"
echo ""

