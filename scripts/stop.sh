#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Stop Kubernetes Deployment
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
K8S_DIR="$PROJECT_ROOT/k8s"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo ""
echo "=========================================="
echo "   🛑 Stopping Jarvis 2.0"
echo "=========================================="
echo ""

echo -e "${YELLOW}[INFO]${NC} Deleting Kubernetes resources..."

cd "$K8S_DIR"
kubectl delete -k overlays/dev/ --ignore-not-found=true

echo ""
echo -e "${GREEN}[SUCCESS]${NC} Jarvis 2.0 stopped"
echo ""
echo "To completely clean up, run: minikube delete"

