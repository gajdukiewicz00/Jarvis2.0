#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Build Docker Images
# Builds Docker images for local runtime and optional k3s import.
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
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

# Runtime/build knobs (defaults preserve previous standalone behavior)
ENABLE_LLM="${ENABLE_LLM:-true}"
ENABLE_MEMORY="${ENABLE_MEMORY:-true}"
IMAGE_REGISTRY="${IMAGE_REGISTRY:-}"
IMAGE_REPO="${IMAGE_REPO:-jarvis}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
BUILD_MVN_CLEAN="${BUILD_MVN_CLEAN:-true}"
SKIP_MVN="${SKIP_MVN:-false}"
JARVIS_IMPORT_REQUIRE_K3S_CONTEXT="${JARVIS_IMPORT_REQUIRE_K3S_CONTEXT:-false}"
JARVIS_K3S_IMPORT_MODE="${JARVIS_K3S_IMPORT_MODE:-sudo-n}"
JARVIS_K3S_NAMESPACE="${JARVIS_K3S_NAMESPACE:-k8s.io}"

CORE_SERVICES=(
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
)

# Colors
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

is_truthy() {
    local value="${1:-}"
    case "${value,,}" in
        1|true|yes|on)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

trim_slashes() {
    local value="$1"
    value="${value#/}"
    value="${value%/}"
    printf '%s' "${value}"
}

init_image_base() {
    local registry
    local repo
    registry="$(trim_slashes "${IMAGE_REGISTRY}")"
    repo="$(trim_slashes "${IMAGE_REPO}")"

    if [[ -z "${repo}" ]]; then
        log_error "IMAGE_REPO must not be empty"
        exit 1
    fi
    if [[ -z "${IMAGE_TAG}" ]]; then
        log_error "IMAGE_TAG must not be empty"
        exit 1
    fi

    if [[ -n "${registry}" ]]; then
        IMAGE_BASE="${registry}/${repo}"
    else
        IMAGE_BASE="${repo}"
    fi
}

image_ref() {
    local service="$1"
    printf '%s/%s:%s' "${IMAGE_BASE}" "${service}" "${IMAGE_TAG}"
}

is_k3s_context() {
    local ctx
    ctx="$(kubectl config current-context 2>/dev/null || true)"
    if [[ -n "${KUBECONFIG:-}" && "${KUBECONFIG}" == "/etc/rancher/k3s/k3s.yaml" ]]; then
        return 0
    fi
    [[ "${ctx}" == *"k3s"* ]]
}

can_sudo_n_k3s_ctr() {
    command -v sudo >/dev/null 2>&1 && sudo -n k3s ctr version >/dev/null 2>&1
}

resolve_k3s_prefix() {
    local mode="${JARVIS_K3S_IMPORT_MODE,,}"
    case "${mode}" in
        sudo-n)
            if ! can_sudo_n_k3s_ctr; then
                log_warn "k3s import requires passwordless sudo for: $(command -v k3s 2>/dev/null || echo k3s)"
                log_warn "Configure NOPASSWD for 'k3s ctr *' in /etc/sudoers.d/jarvis-automation or run with --no-import"
                return 1
            fi
            K3S_PREFIX=(sudo -n k3s)
            ;;
        auto)
            if [[ "${EUID}" -eq 0 ]]; then
                K3S_PREFIX=(k3s)
            elif can_sudo_n_k3s_ctr; then
                K3S_PREFIX=(sudo -n k3s)
            elif command -v sudo >/dev/null 2>&1 && [[ -t 0 ]]; then
                K3S_PREFIX=(sudo k3s)
            elif [[ "${JARVIS_NONINTERACTIVE:-}" == "true" || "${JARVIS_NONINTERACTIVE:-}" == "1" ]]; then
                log_error "k3s import requires sudo/pkexec in non-interactive mode"
                return 1
            elif command -v pkexec >/dev/null 2>&1; then
                K3S_PREFIX=(pkexec /usr/bin/env k3s)
            else
                log_error "k3s import requires sudo or pkexec"
                return 1
            fi
            ;;
        sudo)
            if ! command -v sudo >/dev/null 2>&1; then
                log_error "k3s import mode 'sudo' requires sudo in PATH"
                return 1
            fi
            K3S_PREFIX=(sudo k3s)
            ;;
        direct)
            K3S_PREFIX=(k3s)
            ;;
        *)
            log_error "Unsupported JARVIS_K3S_IMPORT_MODE: ${JARVIS_K3S_IMPORT_MODE}"
            return 1
            ;;
    esac
}

run_k3s_ctr() {
    "${K3S_PREFIX[@]}" ctr -n "${JARVIS_K3S_NAMESPACE}" "$@"
}

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
                log_error "Docker storage error. Run: sudo ${PROJECT_ROOT}/scripts/product/jarvis-fix-docker-root.sh --reset"
                exit 1
            fi
            cat "${log_file}" >&2
            rm -f "${log_file}"
            log_error "Docker build failed. Check Docker Root Dir and daemon health (docker info)."
            exit 1
        fi
        rm -f "${log_file}"
        return 0
    fi
    if ! docker build -t "${image}" "${extra_args[@]}" "${context}" >"${log_file}" 2>&1; then
        if grep -q "invalid output path" "${log_file}"; then
            cat "${log_file}" >&2
            rm -f "${log_file}"
            log_error "Docker storage error. Run: sudo ${PROJECT_ROOT}/scripts/product/jarvis-fix-docker-root.sh --reset"
            exit 1
        fi
        cat "${log_file}" >&2
        rm -f "${log_file}"
        log_error "Docker build failed. Check Docker Root Dir and daemon health (docker info)."
        exit 1
    fi
    rm -f "${log_file}"
}

build_maven() {
    if is_truthy "${SKIP_MVN}"; then
        log_info "Skipping Maven build (SKIP_MVN=true)"
        return 0
    fi

    log_info "Building Maven project..."
    if is_truthy "${BUILD_MVN_CLEAN}"; then
        mvn clean package -DskipTests -q
    else
        mvn -q -DskipTests package
    fi
}

build_images() {
    local images=()

    for service in "${CORE_SERVICES[@]}"; do
        if [[ -d "apps/${service}" && -f "apps/${service}/Dockerfile" ]]; then
            local image
            image="$(image_ref "${service}")"
            log_info "Building ${image}..."
            docker_build "${image}" "apps/${service}"
            images+=("${image}")
        fi
    done

    if is_truthy "${ENABLE_LLM}"; then
        local llm_server_image
        llm_server_image="$(image_ref "llm-server")"
        log_info "Building ${llm_server_image}..."
        docker_build "${llm_server_image}" "docker/llm-server" --build-arg INSTALL_LLAMACPP=true
        images+=("${llm_server_image}")

        if [[ -d "apps/llm-service" && -f "apps/llm-service/Dockerfile" ]]; then
            local llm_service_image
            llm_service_image="$(image_ref "llm-service")"
            log_info "Building ${llm_service_image}..."
            docker_build "${llm_service_image}" "apps/llm-service"
            images+=("${llm_service_image}")
        fi
    fi

    if is_truthy "${ENABLE_MEMORY}"; then
        local embedding_image
        embedding_image="$(image_ref "embedding-service")"
        log_info "Building ${embedding_image}..."
        docker_build "${embedding_image}" "docker/embedding-service"
        images+=("${embedding_image}")

        if [[ -d "apps/memory-service" && -f "apps/memory-service/Dockerfile" ]]; then
            local memory_image
            memory_image="$(image_ref "memory-service")"
            log_info "Building ${memory_image}..."
            docker_build "${memory_image}" "apps/memory-service"
            images+=("${memory_image}")
        fi
    fi

    BUILT_IMAGES=("${images[@]}")
}

import_images() {
    if ! is_truthy "${IMPORT_TO_K3S}"; then
        log_warn "Skipping k3s import (explicit --no-import)"
        return 0
    fi

    if ! command -v k3s >/dev/null 2>&1; then
        log_warn "Skipping k3s import (k3s not found in PATH). Use --no-import to silence."
        return 0
    fi

    if is_truthy "${JARVIS_IMPORT_REQUIRE_K3S_CONTEXT}" && ! is_k3s_context; then
        log_info "Skipping k3s import (current kubectl context is not k3s)"
        return 0
    fi

    resolve_k3s_prefix || exit 1

    if [[ "${#BUILT_IMAGES[@]}" -eq 0 ]]; then
        log_warn "No built images to import"
        return 0
    fi

    log_info "Importing images into k3s containerd..."
    local tar_path
    tar_path="$(mktemp -t jarvis-images-XXXXXX.tar)"
    docker save -o "${tar_path}" "${BUILT_IMAGES[@]}" >/dev/null

    if ! run_k3s_ctr images import "${tar_path}" >/dev/null; then
        rm -f "${tar_path}"
        log_error "k3s image import failed"
        exit 1
    fi

    if ! run_k3s_ctr images list | grep -q "${IMAGE_BASE}/"; then
        rm -f "${tar_path}"
        log_error "k3s import finished but ${IMAGE_BASE}/ images not found in containerd"
        exit 1
    fi

    rm -f "${tar_path}"
    log_success "Images imported into k3s"

    if [[ "${JARVIS_ROLLOUT_RESTART_AFTER_IMPORT:-false}" == "true" ]]; then
        KUBECONFIG="${KUBECONFIG:-${HOME}/.kube/k3s.yaml}" \
            kubectl -n jarvis rollout restart deploy >/dev/null 2>&1 || true
        log_info "Rollout restart triggered for jarvis deployments"
    else
        log_info "Tip: set JARVIS_ROLLOUT_RESTART_AFTER_IMPORT=true to restart deployments"
    fi
}

echo ""
echo "=========================================="
echo "   🔨 Building Jarvis 2.0 Images"
echo "=========================================="
echo ""

cd "${PROJECT_ROOT}"

init_image_base
log_info "Using image base '${IMAGE_BASE}' with tag '${IMAGE_TAG}'"

build_maven
build_images

echo ""
log_success "All images built successfully!"

import_images
