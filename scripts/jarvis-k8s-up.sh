#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Kubernetes LLM + Memory Stack Startup
# =============================================================================
# Deploys the full LLM + Memory stack using kustomize:
#   - PostgreSQL with pgvector
#   - Embedding service (CPU)
#   - Memory service (Java)
#   - LLM server (GPU)
#   - LLM service (Java)
#
# Usage:
#   ./scripts/jarvis-k8s-up.sh              # Deploy everything
#   ./scripts/jarvis-k8s-up.sh --no-gpu     # Deploy without GPU (CPU fallback)
#   ./scripts/jarvis-k8s-up.sh --build      # Rebuild Docker images first
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
K8S_OVERLAY="$PROJECT_ROOT/k8s/overlays/local"
NAMESPACE="jarvis"

# Parse arguments
BUILD_IMAGES=false
NO_GPU=false

for arg in "$@"; do
    case $arg in
        --build)
            BUILD_IMAGES=true
            ;;
        --no-gpu)
            NO_GPU=true
            ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: $0 [--build] [--no-gpu]"
            exit 1
            ;;
    esac
done

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║        Jarvis 2.0 - K8s LLM + Memory Stack                 ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# =============================================================================
# Pre-flight checks
# =============================================================================
echo -e "${YELLOW}[1/6] Pre-flight checks...${NC}"

# Check kubectl
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}✗ kubectl not found${NC}"
    exit 1
fi
echo -e "  ${GREEN}✓${NC} kubectl available"

# Check cluster connectivity
if ! kubectl cluster-info &> /dev/null; then
    echo -e "${RED}✗ Cannot connect to Kubernetes cluster${NC}"
    echo "  Make sure your cluster is running (minikube start, etc.)"
    exit 1
fi
echo -e "  ${GREEN}✓${NC} Kubernetes cluster connected"

# Check GPU availability
if [ "$NO_GPU" = "false" ]; then
    if kubectl get nodes -o jsonpath='{.items[*].status.allocatable}' 2>/dev/null | grep -q 'nvidia.com/gpu'; then
        echo -e "  ${GREEN}✓${NC} GPU resources available in cluster"
    else
        echo -e "  ${YELLOW}⚠${NC} No GPU detected in cluster (will try anyway)"
        echo "    If you don't have GPU, run with --no-gpu flag"
    fi
fi

# Check models directory (for hostPath PV)
MODELS_DIR="/home/kwaqa/models"
if [ -d "$MODELS_DIR" ]; then
    echo -e "  ${GREEN}✓${NC} Models directory exists: $MODELS_DIR"
    
    # Check for GGUF model
    if [ -f "$MODELS_DIR/h2ogpt-7b-chat-q4_k_m.gguf" ]; then
        echo -e "  ${GREEN}✓${NC} GGUF model found"
    else
        echo -e "  ${YELLOW}⚠${NC} GGUF model not found at $MODELS_DIR/h2ogpt-7b-chat-q4_k_m.gguf"
        echo "    LLM will try HuggingFace model instead"
    fi
else
    echo -e "  ${RED}✗${NC} Models directory not found: $MODELS_DIR"
    echo "    Create it and add your models, or update pv-models.yaml"
    exit 1
fi

echo ""

# =============================================================================
# Build Docker images (optional)
# =============================================================================
if [ "$BUILD_IMAGES" = "true" ]; then
    echo -e "${YELLOW}[2/6] Building Docker images...${NC}"
    
    # For minikube, use its Docker daemon
    if command -v minikube &> /dev/null && minikube status &> /dev/null; then
        echo "  Using minikube Docker daemon..."
        eval $(minikube docker-env)
    fi
    
    # Build images
    SERVICES=("llm-server:docker/llm-server" "embedding-service:docker/embedding-service" "memory-service:apps/memory-service" "llm-service:apps/llm-service")
    
    for item in "${SERVICES[@]}"; do
        name="${item%%:*}"
        path="${item##*:}"
        
        echo -n "  Building jarvis/$name... "
        if docker build -t "jarvis/$name:latest" -f "$PROJECT_ROOT/$path/Dockerfile" "$PROJECT_ROOT/$path" -q &> /dev/null; then
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${RED}✗${NC}"
            echo "    Build failed for $name. Run manually:"
            echo "    docker build -t jarvis/$name:latest -f $PROJECT_ROOT/$path/Dockerfile $PROJECT_ROOT/$path"
        fi
    done
    echo ""
else
    echo -e "${YELLOW}[2/6] Skipping Docker build (use --build to rebuild)${NC}"
    echo ""
fi

# =============================================================================
# Apply namespace
# =============================================================================
echo -e "${YELLOW}[3/6] Creating namespace...${NC}"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
echo -e "  ${GREEN}✓${NC} Namespace $NAMESPACE ready"
echo ""

# =============================================================================
# Apply kustomize overlay
# =============================================================================
echo -e "${YELLOW}[4/6] Applying Kubernetes manifests...${NC}"

# Modify for no-GPU mode if needed
if [ "$NO_GPU" = "true" ]; then
    echo "  Running in CPU-only mode..."
    # Create a temporary patch to remove GPU requirements
    TMP_PATCH=$(mktemp)
    cat > "$TMP_PATCH" <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: llm-server
  namespace: jarvis
spec:
  template:
    spec:
      containers:
        - name: llm-server
          env:
            - name: DEVICE
              value: "cpu"
            - name: LLM_BACKEND
              value: "transformers"
          resources:
            requests:
              cpu: "2"
              memory: "8Gi"
            limits:
              cpu: "8"
              memory: "14Gi"
EOF
    kubectl apply -k "$K8S_OVERLAY"
    kubectl apply -f "$TMP_PATCH"
    rm -f "$TMP_PATCH"
else
    kubectl apply -k "$K8S_OVERLAY"
fi

echo -e "  ${GREEN}✓${NC} Manifests applied"
echo ""

# =============================================================================
# Wait for pods
# =============================================================================
echo -e "${YELLOW}[5/6] Waiting for pods to be ready...${NC}"

wait_for_pod() {
    local name="$1"
    local timeout="${2:-300}"
    echo -n "  Waiting for $name... "
    if kubectl wait --for=condition=ready pod -l "app=$name" -n "$NAMESPACE" --timeout="${timeout}s" &> /dev/null; then
        echo -e "${GREEN}✓${NC}"
        return 0
    else
        echo -e "${YELLOW}⚠ timeout${NC}"
        return 1
    fi
}

# Wait in dependency order
wait_for_pod "postgres-pgvector" 180
wait_for_pod "embedding-service" 180
wait_for_pod "memory-service" 120
wait_for_pod "llm-server" 600  # LLM server needs time to load model
wait_for_pod "llm-service" 120

echo ""

# =============================================================================
# Verify GPU (if not --no-gpu)
# =============================================================================
if [ "$NO_GPU" = "false" ]; then
    echo -e "${YELLOW}[6/6] Verifying GPU access...${NC}"
    
    LLM_POD=$(kubectl get pod -n "$NAMESPACE" -l app=llm-server -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
    
    if [ -n "$LLM_POD" ]; then
        echo "  LLM Server pod: $LLM_POD"
        
        # Check nvidia-smi
        echo -n "  Checking nvidia-smi... "
        if kubectl exec -n "$NAMESPACE" "$LLM_POD" -- nvidia-smi &> /dev/null; then
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${YELLOW}⚠ nvidia-smi not available${NC}"
        fi
        
        # Check torch.cuda
        echo -n "  Checking torch.cuda.is_available()... "
        CUDA_AVAILABLE=$(kubectl exec -n "$NAMESPACE" "$LLM_POD" -- python3 -c "import torch; print(torch.cuda.is_available())" 2>/dev/null || echo "Error")
        if [ "$CUDA_AVAILABLE" = "True" ]; then
            echo -e "${GREEN}✓ True${NC}"
        else
            echo -e "${YELLOW}⚠ $CUDA_AVAILABLE${NC}"
        fi
    else
        echo -e "  ${YELLOW}⚠ LLM Server pod not found${NC}"
    fi
    echo ""
else
    echo -e "${YELLOW}[6/6] Skipping GPU verification (--no-gpu mode)${NC}"
    echo ""
fi

# =============================================================================
# Status summary
# =============================================================================
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}                    Pod Status                              ${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
kubectl get pods -n "$NAMESPACE" -o wide

echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}                    Services                                ${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
kubectl get svc -n "$NAMESPACE"

echo ""
echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║                    Stack Deployed!                         ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "Next steps:"
echo ""
echo "  1. Port-forward services for local access:"
echo "     kubectl port-forward -n jarvis svc/llm-server 5000:5000 &"
echo "     kubectl port-forward -n jarvis svc/llm-service 8091:8091 &"
echo "     kubectl port-forward -n jarvis svc/memory-service 8093:8093 &"
echo "     kubectl port-forward -n jarvis svc/embedding-service 5001:5001 &"
echo ""
echo "  2. Run smoke tests:"
echo "     ./scripts/llm-smoke.sh"
echo "     ./scripts/memory-smoke.sh"
echo ""
echo "  3. Check logs if needed:"
echo "     kubectl logs -n jarvis deploy/llm-server --tail=100"
echo "     kubectl logs -n jarvis deploy/memory-service --tail=100"
echo ""
