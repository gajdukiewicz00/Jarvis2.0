#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Build Docker Images
# Builds all Docker images for Minikube
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors
BLUE='\033[0;34m'
GREEN='\033[0;32m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }

echo ""
echo "=========================================="
echo "   🔨 Building Jarvis 2.0 Images"
echo "=========================================="
echo ""

# Use Minikube's Docker daemon
eval $(minikube docker-env)

cd "$PROJECT_ROOT"

# Build Java services
log_info "Building Maven project..."
mvn clean package -DskipTests -q

# Build Docker images for Java services
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
    if [ -d "apps/$service" ] && [ -f "apps/$service/Dockerfile" ]; then
        log_info "Building jarvis/$service..."
        docker build -t "jarvis/$service:latest" "apps/$service"
    fi
done

# Build Python services
log_info "Building jarvis/llm-server..."
docker build -t jarvis/llm-server:latest docker/llm-server

log_info "Building jarvis/embedding-service..."
docker build -t jarvis/embedding-service:latest docker/embedding-service

echo ""
log_success "All images built successfully!"
echo ""
echo "Images available in Minikube:"
docker images | grep jarvis

