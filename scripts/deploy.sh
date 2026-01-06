#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Kubernetes Deployment Script
# One-command deployment to Minikube
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
K8S_DIR="$PROJECT_ROOT/k8s"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# =============================================================================
# Check Prerequisites
# =============================================================================
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    # Check Minikube
    if ! command -v minikube &> /dev/null; then
        log_error "Minikube is not installed. Please install it first."
        exit 1
    fi
    
    # Check kubectl
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed. Please install it first."
        exit 1
    fi
    
    # Check if Minikube is running
    if ! minikube status | grep -q "Running"; then
        log_warn "Minikube is not running. Starting..."
        minikube start --driver=docker --cpus=4 --memory=8192 --disk-size=50g
    fi
    
    log_success "Prerequisites OK"
}

# =============================================================================
# Enable Minikube Addons
# =============================================================================
enable_addons() {
    log_info "Enabling Minikube addons..."
    
    minikube addons enable ingress || true
    minikube addons enable ingress-dns || true
    minikube addons enable metrics-server || true
    
    # Enable NVIDIA GPU support if available
    if nvidia-smi &> /dev/null; then
        log_info "NVIDIA GPU detected, enabling GPU addon..."
        minikube addons enable nvidia-gpu-device-plugin || true
    fi
    
    log_success "Addons enabled"
}

# =============================================================================
# Build Docker Images
# =============================================================================
build_images() {
    log_info "Building Docker images in Minikube context..."
    
    # Use Minikube's Docker daemon
    eval $(minikube docker-env)
    
    cd "$PROJECT_ROOT"
    
    # Build Java services
    log_info "Building Java services..."
    mvn clean package -DskipTests -q
    
    # Build Docker images
    services=(
        "api-gateway"
        "voice-gateway"
        "nlp-service"
        "orchestrator"
        "pc-control"
        "life-tracker"
        "analytics-service"
        "planner-service"
        "user-profile"
        "security-service"
        "smart-home-service"
        "llm-service"
        "memory-service"
    )
    
    for service in "${services[@]}"; do
        if [ -d "apps/$service" ]; then
            log_info "Building jarvis/$service..."
            docker build -t "jarvis/$service:latest" "apps/$service" -q
        fi
    done
    
    # Build Python services
    log_info "Building Python services..."
    docker build -t jarvis/llm-server:latest docker/llm-server -q
    docker build -t jarvis/embedding-service:latest docker/embedding-service -q
    
    log_success "All images built"
}

# =============================================================================
# Deploy to Kubernetes
# =============================================================================
deploy() {
    log_info "Deploying to Kubernetes..."
    
    cd "$K8S_DIR"
    
    # Apply with kustomize
    kubectl apply -k overlays/dev/
    
    log_success "Deployment initiated"
}

# =============================================================================
# Wait for Services
# =============================================================================
wait_for_services() {
    log_info "Waiting for services to be ready..."
    
    # Wait for PostgreSQL
    kubectl wait --for=condition=ready pod -l app=postgres -n jarvis-data --timeout=300s || true
    
    # Wait for core services
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/part-of=jarvis -n jarvis-core --timeout=300s || true
    
    # Wait for LLM services (longer timeout for model loading)
    kubectl wait --for=condition=ready pod -l app=llm-server -n jarvis-llm --timeout=600s || true
    
    log_success "Services are ready"
}

# =============================================================================
# Show Status
# =============================================================================
show_status() {
    log_info "Deployment status:"
    echo ""
    
    echo "=== Namespaces ==="
    kubectl get namespaces -l app.kubernetes.io/part-of=jarvis
    echo ""
    
    echo "=== Pods ==="
    kubectl get pods -A -l app.kubernetes.io/part-of=jarvis
    echo ""
    
    echo "=== Services ==="
    kubectl get svc -A -l app.kubernetes.io/part-of=jarvis
    echo ""
    
    # Get API Gateway URL
    MINIKUBE_IP=$(minikube ip)
    NODE_PORT=$(kubectl get svc api-gateway -n jarvis-core -o jsonpath='{.spec.ports[0].nodePort}')
    
    echo "=== Access URLs ==="
    echo -e "${GREEN}API Gateway:${NC} http://$MINIKUBE_IP:$NODE_PORT"
    echo ""
    
    log_success "Jarvis 2.0 is deployed!"
}

# =============================================================================
# Main
# =============================================================================
main() {
    echo ""
    echo "=========================================="
    echo "   🤖 Jarvis 2.0 Kubernetes Deployment"
    echo "=========================================="
    echo ""
    
    check_prerequisites
    enable_addons
    build_images
    deploy
    wait_for_services
    show_status
}

main "$@"

