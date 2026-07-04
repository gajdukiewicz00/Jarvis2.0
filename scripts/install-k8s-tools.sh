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

# === k3s ===
if command -v k3s &> /dev/null; then
    echo -e "${GREEN}✓${NC} k3s уже установлен: $(k3s --version | head -1)"
else
    echo ""
    read -p "Установить k3s (рекомендуется)? [y/N] " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}⏳${NC} Устанавливаю k3s..."
        curl -sfL https://get.k3s.io | sh -
        echo -e "${GREEN}✓${NC} k3s установлен"
        echo -e "${YELLOW}⚠️${NC} Добавь пользователя в группу 'k3s' или используй sudo для kubectl"
    fi
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

# === podman (daemonless image build / preflight fallback) ===
if command -v podman &> /dev/null; then
    echo -e "${GREEN}✓${NC} podman установлен: $(podman --version)"
else
    echo ""
    read -p "Установить podman для локальной сборки образов? [y/N] " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        sudo apt-get update
        sudo apt-get install -y podman
        echo -e "${GREEN}✓${NC} podman установлен"
    else
        echo -e "${YELLOW}⚠️${NC} podman не установлен; локальная сборка Python образов и containerized preflight будут недоступны"
    fi
fi

echo ""
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         📋 QUICK START GUIDE          ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""
echo -e "${GREEN}1.${NC} Убедись, что k3s запущен:"
echo "   sudo systemctl status k3s"
echo ""
echo -e "${GREEN}2.${NC} Запусти Jarvis:"
echo "   ./jarvis-launch.sh"
echo ""
echo -e "${GREEN}3.${NC} Или кликни на иконку 'Jarvis 2.0' в меню приложений"
echo ""
echo -e "${YELLOW}💡${NC} Для добавления в /etc/hosts:"
echo "   sudo ./scripts/product/jarvis-setup-hosts.sh"
echo ""
