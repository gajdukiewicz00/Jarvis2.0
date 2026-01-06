#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - /etc/hosts Setup Script
# =============================================================================
# Iteration 1.5 (Stage 7): Add api.jarvis.local and voice.jarvis.local to /etc/hosts
# =============================================================================

set -euo pipefail

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Get minikube IP or use localhost
MINIKUBE_IP=""
if command -v minikube >/dev/null 2>&1; then
    MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "")
fi

if [ -z "$MINIKUBE_IP" ]; then
    # Fallback: try to get ingress IP from kubectl
    if command -v kubectl >/dev/null 2>&1; then
        INGRESS_IP=$(kubectl get ingress -n jarvis jarvis-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
        if [ -n "$INGRESS_IP" ]; then
            MINIKUBE_IP="$INGRESS_IP"
        else
            # Last resort: use localhost (for port-forward scenarios)
            MINIKUBE_IP="127.0.0.1"
        fi
    else
        MINIKUBE_IP="127.0.0.1"
    fi
fi

echo "=========================================="
echo "Jarvis 2.0 - /etc/hosts Setup"
echo "=========================================="
echo ""
echo "Target IP: $MINIKUBE_IP"
echo "Domains: api.jarvis.local, voice.jarvis.local"
echo ""

# Check if running as root (required for /etc/hosts modification)
if [ "$EUID" -ne 0 ]; then
    echo -e "${YELLOW}⚠️  This script requires sudo privileges to modify /etc/hosts.${NC}"
    echo "Please run: sudo $0"
    exit 1
fi

# Check if entries already exist
if grep -q "api.jarvis.local" /etc/hosts && grep -q "voice.jarvis.local" /etc/hosts; then
    # Check if IP matches
    EXISTING_IP=$(grep "api.jarvis.local" /etc/hosts | awk '{print $1}' | head -1)
    if [ "$EXISTING_IP" = "$MINIKUBE_IP" ]; then
        echo -e "${GREEN}✅ Entries already exist with correct IP${NC}"
        exit 0
    else
        echo -e "${YELLOW}⚠️  Entries exist but IP differs ($EXISTING_IP vs $MINIKUBE_IP)${NC}"
        echo "Removing old entries..."
        sed -i '/api\.jarvis\.local/d' /etc/hosts
        sed -i '/voice\.jarvis\.local/d' /etc/hosts
    fi
fi

# Add entries
echo "Adding entries to /etc/hosts..."
echo "$MINIKUBE_IP api.jarvis.local" >> /etc/hosts
echo "$MINIKUBE_IP voice.jarvis.local" >> /etc/hosts

echo ""
echo -e "${GREEN}✅ Entries added successfully${NC}"
echo ""
echo "Verification:"
echo "  grep jarvis.local /etc/hosts"
echo ""

