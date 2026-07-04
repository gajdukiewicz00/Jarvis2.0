#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - /etc/hosts Setup Script
# =============================================================================
# Iteration 1.5 (Stage 7): Add api.jarvis.local, voice.jarvis.local, and grafana.jarvis.local to /etc/hosts
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/scripts/lib/k8s-common.sh"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Resolve host IP for ingress (override with JARVIS_HOST_IP)
HOST_IP="${JARVIS_HOST_IP:-}"

if [ -z "$HOST_IP" ] && jarvis_require_kubectl >/dev/null 2>&1; then
    # Try the common ingress controller Services first.
    HOST_IP=$(kubectl get svc -n ingress-nginx ingress-nginx-controller \
        -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    if [ -z "$HOST_IP" ]; then
        HOST_IP=$(kubectl get svc -n ingress nginx-ingress-microk8s-controller \
            -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    fi
    if [ -z "$HOST_IP" ]; then
        # Fallback: node internal IP
        HOST_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "")
    fi
fi

if [ -z "$HOST_IP" ]; then
    # Fallback: first host IP
    HOST_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "")
fi

if [ -z "$HOST_IP" ]; then
    HOST_IP="127.0.0.1"
fi

echo "=========================================="
echo "Jarvis 2.0 - /etc/hosts Setup"
echo "=========================================="
echo ""
echo "Target IP: $HOST_IP"
echo "Domains: api.jarvis.local, voice.jarvis.local, grafana.jarvis.local"
echo ""

# Check if running as root (required for /etc/hosts modification)
if [ "$EUID" -ne 0 ]; then
    echo -e "${YELLOW}⚠️  This script requires sudo privileges to modify /etc/hosts.${NC}"
    echo "Please run: sudo $0"
    exit 1
fi

# Check if entries already exist
if grep -q "api.jarvis.local" /etc/hosts && grep -q "voice.jarvis.local" /etc/hosts && grep -q "grafana.jarvis.local" /etc/hosts; then
    # Check if IP matches
    EXISTING_IP=$(grep "api.jarvis.local" /etc/hosts | awk '{print $1}' | head -1)
    if [ "$EXISTING_IP" = "$HOST_IP" ]; then
        echo -e "${GREEN}✅ Entries already exist with correct IP${NC}"
        exit 0
    else
        echo -e "${YELLOW}⚠️  Entries exist but IP differs ($EXISTING_IP vs $HOST_IP)${NC}"
        echo "Removing old entries..."
        sed -i '/api\.jarvis\.local/d' /etc/hosts
        sed -i '/voice\.jarvis\.local/d' /etc/hosts
        sed -i '/grafana\.jarvis\.local/d' /etc/hosts
    fi
fi

# Add entries
echo "Adding entries to /etc/hosts..."
sed -i '/api\.jarvis\.local/d' /etc/hosts
sed -i '/voice\.jarvis\.local/d' /etc/hosts
sed -i '/grafana\.jarvis\.local/d' /etc/hosts
echo "$HOST_IP api.jarvis.local voice.jarvis.local grafana.jarvis.local" >> /etc/hosts

echo ""
echo -e "${GREEN}✅ Entries added successfully${NC}"
echo ""
echo "Verification:"
echo "  grep jarvis.local /etc/hosts"
echo ""

