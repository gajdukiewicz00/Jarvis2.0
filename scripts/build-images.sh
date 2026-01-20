#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Build Docker Images
# Builds all Docker images for local k3s
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

IMPORT_TO_K3S=true
for arg in "$@"; do
    case "$arg" in
        --no-import)
            IMPORT_TO_K3S=false
            ;;
        --help|-h)
            echo "Usage: $0 [--no-import]"
            exit 0
            ;;
    esac
done

# Colors
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

ensure_buildx() {
    if docker buildx version >/dev/null 2>&1; then
        return 0
    fi
    if [[ "${JARVIS_INSTALL_BUILDX:-true}" != "true" ]]; then
        return 1
    fi
    if ! command -v curl >/dev/null 2>&1; then
        return 1
    fi
    local arch
    case "$(uname -m)" in
        x86_64) arch="amd64" ;;
        aarch64|arm64) arch="arm64" ;;
        *) return 1 ;;
    esac
    local version="${JARVIS_BUILDX_VERSION:-v0.14.1}"
    local url="https://github.com/docker/buildx/releases/download/${version}/buildx-${version}.linux-${arch}"
    local dest="${HOME}/.docker/cli-plugins/docker-buildx"
    mkdir -p "$(dirname "${dest}")"
    curl -fsSL "${url}" -o "${dest}"
    chmod +x "${dest}"
    docker buildx version >/dev/null 2>&1
}

USE_BUILDX=false
if ensure_buildx; then
    USE_BUILDX=true
else
    log_warn "buildx not available; using legacy docker builder"
fi

docker_build() {
    local image="$1"
    local context="$2"
    shift 2
    local extra_args=("$@")
    local log_file
    log_file="$(mktemp -t jarvis-docker-build-XXXX.log)"
    if [[ "${USE_BUILDX}" == "true" ]]; then
        if ! docker buildx build --load -t "${image}" "${extra_args[@]}" "${context}" >"${log_file}" 2>&1; then
            if grep -q "invalid output path" "${log_file}"; then
                cat "${log_file}" >&2
                rm -f "${log_file}"
                echo "[ERROR] Docker storage error. Run: sudo ${PROJECT_ROOT}/scripts/product/jarvis-fix-docker-root.sh --reset" >&2
                exit 1
            fi
            cat "${log_file}" >&2
            rm -f "${log_file}"
            echo "[ERROR] Docker build failed. Check Docker Root Dir and daemon health (docker info)." >&2
            exit 1
        fi
        rm -f "${log_file}"
        return 0
    fi
    if ! docker build -t "${image}" "${extra_args[@]}" "${context}" >"${log_file}" 2>&1; then
        if grep -q "invalid output path" "${log_file}"; then
            cat "${log_file}" >&2
            rm -f "${log_file}"
            echo "[ERROR] Docker storage error. Run: sudo ${PROJECT_ROOT}/scripts/product/jarvis-fix-docker-root.sh --reset" >&2
            exit 1
        fi
        cat "${log_file}" >&2
        rm -f "${log_file}"
        echo "[ERROR] Docker build failed. Check Docker Root Dir and daemon health (docker info)." >&2
        exit 1
    fi
    rm -f "${log_file}"
}

echo ""
echo "=========================================="
echo "   🔨 Building Jarvis 2.0 Images"
echo "=========================================="
echo ""

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
        docker_build "jarvis/$service:latest" "apps/$service"
    fi
done

# Build Python services
log_info "Building jarvis/llm-server..."
docker_build "jarvis/llm-server:latest" "docker/llm-server" --build-arg INSTALL_LLAMACPP=true

log_info "Building jarvis/embedding-service..."
docker_build "jarvis/embedding-service:latest" "docker/embedding-service"

echo ""
log_success "All images built successfully!"

# Import into k3s containerd (optional)
if [ "$IMPORT_TO_K3S" = "true" ] && command -v k3s >/dev/null 2>&1; then
    log_info "Importing images into k3s containerd..."
    IMAGE_LIST=(
        "jarvis/api-gateway:latest"
        "jarvis/voice-gateway:latest"
        "jarvis/nlp-service:latest"
        "jarvis/orchestrator:latest"
        "jarvis/pc-control:latest"
        "jarvis/life-tracker:latest"
        "jarvis/analytics-service:latest"
        "jarvis/planner-service:latest"
        "jarvis/user-profile:latest"
        "jarvis/security-service:latest"
        "jarvis/smart-home-service:latest"
        "jarvis/llm-service:latest"
        "jarvis/memory-service:latest"
        "jarvis/llm-server:latest"
        "jarvis/embedding-service:latest"
    )

    TAR_PATH=$(mktemp -t jarvis-images-XXXXXX.tar)
    docker save -o "$TAR_PATH" "${IMAGE_LIST[@]}" >/dev/null
    sudo k3s ctr images import "$TAR_PATH" >/dev/null
    rm -f "$TAR_PATH"
    log_success "Images imported into k3s"
else
    log_warn "Skipping k3s import (use --no-import or install k3s)"
fi
