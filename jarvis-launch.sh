#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Kubernetes Launch Script (prod-only)
# =============================================================================
# Brings up: k3s -> ingress-nginx -> HTTPS -> services
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}"
K8S_DIR="${PROJECT_DIR}/k8s"
NAMESPACE="jarvis"

JARVIS_HOME="${HOME}/.jarvis"
TLS_DIR="${JARVIS_HOME}/tls"
SECRETS_FILE="${JARVIS_HOME}/secrets/secrets.env"
LOG_DIR="${JARVIS_LOG_DIR:-${JARVIS_HOME}/logs}"
BACKEND_LOG="${LOG_DIR}/backend-launch.log"
RUN_SUMMARY="${JARVIS_HOME}/run/last-run.json"

# Feature flags
ENABLE_LLM="${ENABLE_LLM:-false}"
ENABLE_MEMORY="${ENABLE_MEMORY:-false}"
ENABLE_GPU="${ENABLE_GPU:-true}"
ENABLE_BUILD="${ENABLE_BUILD:-true}"
ENABLE_PORT_FORWARD="${ENABLE_PORT_FORWARD:-false}"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

RUN_STATUS="starting"
RUN_ERROR=""
RUN_WARNINGS=()
RUN_API_URL="https://api.jarvis.local"
RUN_VOICE_URL="wss://voice.jarvis.local"
OPTIONAL_FAILED="false"

write_run_summary() {
    mkdir -p "${JARVIS_HOME}/run" "${LOG_DIR}"
    local warnings_json="[]"
    if [[ "${#RUN_WARNINGS[@]}" -gt 0 ]]; then
        warnings_json="["
        local first="true"
        for w in "${RUN_WARNINGS[@]}"; do
            if [[ "${first}" == "true" ]]; then
                first="false"
            else
                warnings_json+=","
            fi
            warnings_json+="\"${w//\"/\\\"}\""
        done
        warnings_json+="]"
    fi
    cat >"${RUN_SUMMARY}" <<EOF
{
  "timestamp": "$(date -Is)",
  "status": "${RUN_STATUS}",
  "error": "${RUN_ERROR//\"/\\\"}",
  "warnings": ${warnings_json},
  "apiUrl": "${RUN_API_URL}",
  "voiceUrl": "${RUN_VOICE_URL}",
  "enableLlm": "${ENABLE_LLM}",
  "enableMemory": "${ENABLE_MEMORY}",
  "enableGpu": "${ENABLE_GPU}"
}
EOF
}

fail() {
    RUN_STATUS="error"
    RUN_ERROR="$*"
    write_run_summary
    echo -e "${RED}❌ $*${NC}"
    exit 1
}

warn() {
    RUN_WARNINGS+=("$*")
    echo -e "${YELLOW}⚠️  $*${NC}"
}

info() {
    echo -e "${CYAN}[INFO]${NC} $*"
}

rotate_backend_log() {
    mkdir -p "${LOG_DIR}"
    if [[ -f "${BACKEND_LOG}" ]]; then
        local ts
        ts="$(date +%Y%m%d-%H%M%S)"
        cp "${BACKEND_LOG}" "${BACKEND_LOG}.${ts}" 2>/dev/null || true
    fi
    : > "${BACKEND_LOG}" 2>/dev/null || true
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "Missing dependency: $1"
}

run_privileged() {
    if [[ "${EUID}" -eq 0 ]]; then
        "$@"
        return $?
    fi
    if command -v sudo >/dev/null 2>&1; then
        if sudo -n true >/dev/null 2>&1; then
            sudo -n "$@"
            return $?
        fi
        if [[ -t 0 ]]; then
            sudo "$@"
            return $?
        fi
    fi
    if [[ "${JARVIS_NONINTERACTIVE:-}" == "true" || "${JARVIS_NONINTERACTIVE:-}" == "1" ]]; then
        fail "Privileged operation requires sudo or pkexec. Run in an interactive terminal or unset JARVIS_NONINTERACTIVE: $*"
    fi
    if command -v pkexec >/dev/null 2>&1; then
        pkexec /usr/bin/env "$@"
        return $?
    fi
    fail "Privileged operation requires sudo or pkexec: $*"
}

is_k3s_context() {
    local ctx
    ctx=$(kubectl config current-context 2>/dev/null || true)
    if [[ -n "${KUBECONFIG:-}" && "${KUBECONFIG}" == "/etc/rancher/k3s/k3s.yaml" ]]; then
        return 0
    fi
    [[ "${ctx}" == *"k3s"* ]]
}

ensure_kubeconfig() {
    if [[ -z "${KUBECONFIG:-}" ]] && [[ -r /etc/rancher/k3s/k3s.yaml ]]; then
        export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
        info "Using k3s kubeconfig: ${KUBECONFIG}"
    fi
}

ensure_cluster() {
    local timeout="5s"
    if kubectl cluster-info --request-timeout="${timeout}" >/dev/null 2>&1; then
        return 0
    fi

    if is_k3s_context && command -v systemctl >/dev/null 2>&1; then
        if systemctl list-unit-files | grep -q '^k3s.service'; then
            info "Starting k3s service..."
            run_privileged systemctl start k3s || true
            sleep 2
        fi
    fi

    if ! kubectl cluster-info --request-timeout="${timeout}" >/dev/null 2>&1; then
        local ctx
        ctx=$(kubectl config current-context 2>/dev/null || true)
        if [[ -z "${ctx}" ]]; then
            ctx="(none)"
        fi
        if is_k3s_context; then
            fail "Kubernetes cluster not available for k3s context. Install/start k3s: curl -sfL https://get.k3s.io | sh -"
        fi
        fail "Kubernetes cluster not available for context '${ctx}'. Start your cluster or set KUBECONFIG."
    fi
}

ensure_ingress_nginx() {
    if kubectl get ingressclass nginx >/dev/null 2>&1; then
        return 0
    fi

    info "Installing ingress-nginx (baremetal)..."
    kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.1/deploy/static/provider/baremetal/deploy.yaml

    # Wait for controller
    kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx --timeout=180s || true

    # Prefer LoadBalancer for k3s (klipper-lb will expose 80/443)
    kubectl patch svc ingress-nginx-controller -n ingress-nginx -p '{"spec":{"type":"LoadBalancer"}}' >/dev/null 2>&1 || true
}

ensure_namespace() {
    if ! kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1; then
        kubectl create namespace "${NAMESPACE}" >/dev/null
    fi
}

ensure_secrets() {
    if kubectl get secret jarvis-secrets -n "${NAMESPACE}" >/dev/null 2>&1; then
        return 0
    fi

    if [[ -f "${SECRETS_FILE}" ]]; then
        info "Applying local secrets..."
        "${PROJECT_DIR}/scripts/product/jarvis-secrets-apply.sh" >/dev/null
        return 0
    fi

    echo -e "${RED}❌ Required secrets not found${NC}"
    echo "Create secrets file:"
    echo "  cp ${PROJECT_DIR}/secrets/secrets.example.env ${SECRETS_FILE}"
    echo "  chmod 600 ${SECRETS_FILE}"
    echo "  ${PROJECT_DIR}/scripts/product/jarvis-secrets-apply.sh"
    exit 1
}

ensure_tls() {
    if [[ -f "${TLS_DIR}/jarvis.crt" && -f "${TLS_DIR}/jarvis.key" ]]; then
        return 0
    fi

    info "Generating TLS certificates..."
    "${PROJECT_DIR}/scripts/product/jarvis-generate-certs.sh" >/dev/null
}

apply_tls_secret() {
    kubectl create secret tls jarvis-tls \
        --cert="${TLS_DIR}/jarvis.crt" \
        --key="${TLS_DIR}/jarvis.key" \
        -n "${NAMESPACE}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

build_images() {
    if [[ "${ENABLE_BUILD}" != "true" ]]; then
        info "Skipping build (ENABLE_BUILD=false)"
        return 0
    fi

    require_cmd docker
    require_cmd mvn

    ensure_buildx() {
        if docker buildx version >/dev/null 2>&1; then
            return 0
        fi
        if [[ "${JARVIS_INSTALL_BUILDX:-true}" != "true" ]]; then
            return 1
        fi
        require_cmd curl
        local arch
        case "$(uname -m)" in
            x86_64) arch="amd64" ;;
            aarch64|arm64) arch="arm64" ;;
            *)
                warn "Unsupported arch for buildx auto-install: $(uname -m)"
                return 1
                ;;
        esac
        local version="${JARVIS_BUILDX_VERSION:-v0.14.1}"
        local url="https://github.com/docker/buildx/releases/download/${version}/buildx-${version}.linux-${arch}"
        local dest="${HOME}/.docker/cli-plugins/docker-buildx"
        mkdir -p "$(dirname "${dest}")"
        curl -fsSL "${url}" -o "${dest}"
        chmod +x "${dest}"
        docker buildx version >/dev/null 2>&1
    }

    local use_buildx="false"
    if ensure_buildx; then
        use_buildx="true"
    else
        warn "buildx not available; using legacy docker builder"
    fi

    docker_build() {
        local image="$1"
        local dockerfile="$2"
        local context="$3"
        shift 3
        local extra_args=("$@")
        local log_file
        log_file="$(mktemp -t jarvis-docker-build-XXXX.log)"
        if [[ "${use_buildx}" == "true" ]]; then
            if ! docker buildx build --load -t "${image}" -f "${dockerfile}" "${extra_args[@]}" "${context}" >"${log_file}" 2>&1; then
                if grep -q "invalid output path" "${log_file}"; then
                    cat "${log_file}" >&2
                    rm -f "${log_file}"
                    fail "Docker storage error. Run: sudo ${PROJECT_DIR}/scripts/product/jarvis-fix-docker-root.sh --reset"
                fi
                cat "${log_file}" >&2
                rm -f "${log_file}"
                fail "Docker build failed. Check Docker Root Dir and daemon health (docker info)."
            fi
            rm -f "${log_file}"
            return 0
        fi
        if ! docker build -t "${image}" -f "${dockerfile}" "${extra_args[@]}" "${context}" >"${log_file}" 2>&1; then
            if grep -q "invalid output path" "${log_file}"; then
                cat "${log_file}" >&2
                rm -f "${log_file}"
                fail "Docker storage error. Run: sudo ${PROJECT_DIR}/scripts/product/jarvis-fix-docker-root.sh --reset"
            fi
            cat "${log_file}" >&2
            rm -f "${log_file}"
            fail "Docker build failed. Check Docker Root Dir and daemon health (docker info)."
        fi
        rm -f "${log_file}"
    }

    info "Building Maven modules (skip tests)..."
    mvn -q -DskipTests package

    local services=(
        "api-gateway"
        "security-service"
        "life-tracker"
        "analytics-service"
        "voice-gateway"
        "pc-control"
        "smart-home-service"
        "nlp-service"
        "orchestrator"
        "user-profile"
        "planner-service"
    )

    for svc in "${services[@]}"; do
        if [[ -f "${PROJECT_DIR}/apps/${svc}/Dockerfile" ]]; then
            info "Building jarvis/${svc}..."
            docker_build "jarvis/${svc}:latest" "${PROJECT_DIR}/apps/${svc}/Dockerfile" "${PROJECT_DIR}/apps/${svc}"
        fi
    done

    if [[ "${ENABLE_LLM}" == "true" ]]; then
        info "Building jarvis/llm-server..."
        docker_build "jarvis/llm-server:latest" "${PROJECT_DIR}/docker/llm-server/Dockerfile" "${PROJECT_DIR}/docker/llm-server" \
            --build-arg INSTALL_LLAMACPP=true
        info "Building jarvis/llm-service..."
        docker_build "jarvis/llm-service:latest" "${PROJECT_DIR}/apps/llm-service/Dockerfile" "${PROJECT_DIR}/apps/llm-service"
    fi

    if [[ "${ENABLE_MEMORY}" == "true" ]]; then
        info "Building jarvis/embedding-service..."
        docker_build "jarvis/embedding-service:latest" "${PROJECT_DIR}/docker/embedding-service/Dockerfile" "${PROJECT_DIR}/docker/embedding-service"
        info "Building jarvis/memory-service..."
        docker_build "jarvis/memory-service:latest" "${PROJECT_DIR}/apps/memory-service/Dockerfile" "${PROJECT_DIR}/apps/memory-service"
    fi

    if command -v k3s >/dev/null 2>&1 && is_k3s_context; then
        info "Importing images into k3s containerd..."
        local images=(
            "jarvis/api-gateway:latest"
            "jarvis/security-service:latest"
            "jarvis/life-tracker:latest"
            "jarvis/analytics-service:latest"
            "jarvis/voice-gateway:latest"
            "jarvis/pc-control:latest"
            "jarvis/smart-home-service:latest"
            "jarvis/nlp-service:latest"
            "jarvis/orchestrator:latest"
            "jarvis/user-profile:latest"
            "jarvis/planner-service:latest"
        )

        if [[ "${ENABLE_LLM}" == "true" ]]; then
            images+=(
                "jarvis/llm-server:latest"
                "jarvis/llm-service:latest"
            )
        fi

        if [[ "${ENABLE_MEMORY}" == "true" ]]; then
            images+=(
                "jarvis/embedding-service:latest"
                "jarvis/memory-service:latest"
            )
        fi

        local tar_path
        tar_path=$(mktemp -t jarvis-images-XXXXXX.tar)
        docker save -o "${tar_path}" "${images[@]}" >/dev/null
        run_privileged k3s ctr images import "${tar_path}" >/dev/null
        rm -f "${tar_path}"
    fi
}

wait_rollout() {
    local kind="$1"
    local name="$2"
    local timeout="$3"
    kubectl rollout status "${kind}/${name}" -n "${NAMESPACE}" --timeout="${timeout}" >/dev/null 2>&1 || warn "${kind}/${name} not ready"
}

print_header() {
    echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║   🤖 JARVIS 2.0 (Kubernetes) 🤖       ║${NC}"
    echo -e "${BLUE}║         Starting up...                 ║${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
    echo ""
}

# === Start ===
print_header

require_cmd kubectl
ensure_kubeconfig
ensure_cluster

info "Ensuring ingress-nginx..."
ensure_ingress_nginx

info "Ensuring namespace..."
ensure_namespace

mkdir -p "${JARVIS_HOME}/models"

info "Ensuring secrets..."
ensure_secrets

info "Ensuring TLS..."
ensure_tls
apply_tls_secret

rotate_backend_log

if [[ "${ENABLE_LLM}" == "true" && "${ENABLE_GPU}" == "true" ]]; then
    if ! kubectl get nodes -o jsonpath='{.items[*].status.allocatable}' 2>/dev/null | grep -q 'nvidia.com/gpu'; then
        info "GPU not detected in cluster, attempting GPU setup..."
        if [[ -x "${PROJECT_DIR}/scripts/product/jarvis-gpu-setup.sh" ]]; then
            run_privileged "${PROJECT_DIR}/scripts/product/jarvis-gpu-setup.sh"
        else
            warn "GPU setup script not found: ${PROJECT_DIR}/scripts/product/jarvis-gpu-setup.sh"
        fi
    fi
    if ! kubectl get nodes -o jsonpath='{.items[*].status.allocatable}' 2>/dev/null | grep -q 'nvidia.com/gpu'; then
        warn "GPU not detected in cluster; disabling LLM for this run."
        ENABLE_LLM="false"
        OPTIONAL_FAILED="true"
    fi
fi

ensure_disk_space() {
    local usage
    usage="$(df -P / | awk 'NR==2 {gsub(/%/, "", $5); print $5}')"
    if [[ -n "${usage}" && "${usage}" -ge 95 ]]; then
        warn "Root filesystem is ${usage}% full; attempting cleanup..."
        if [[ -x "${PROJECT_DIR}/scripts/product/jarvis-disk-cleanup.sh" ]]; then
            run_privileged "${PROJECT_DIR}/scripts/product/jarvis-disk-cleanup.sh"
        else
            warn "Cleanup script missing: ${PROJECT_DIR}/scripts/product/jarvis-disk-cleanup.sh"
        fi
        usage="$(df -P / | awk 'NR==2 {gsub(/%/, "", $5); print $5}')"
        if [[ -n "${usage}" && "${usage}" -ge 95 ]]; then
            fail "Disk still ${usage}% full. Free space on / and retry."
        fi
    fi
}

ensure_disk_space

build_images

info "Applying core manifests..."
if [ -d "${K8S_DIR}/overlays/prod" ]; then
    MODELS_PATH="${JARVIS_HOME}/models"
    kubectl kustomize --load-restrictor=LoadRestrictionsNone "${K8S_DIR}/overlays/prod" | \
        sed "s|__JARVIS_MODELS_PATH__|${MODELS_PATH}|g" | \
        kubectl apply -f - >/dev/null
else
    fail "Overlay not found: ${K8S_DIR}/overlays/prod"
fi

# Optional LLM/Memory
if [[ "${ENABLE_LLM}" == "true" || "${ENABLE_MEMORY}" == "true" ]]; then
    if [[ "${ENABLE_LLM}" == "true" ]]; then
        if ! kubectl apply -f "${K8S_DIR}/overlays/prod/embedding-service.yaml" >/dev/null; then
            warn "Failed to apply embedding-service (LLM optional)"
            OPTIONAL_FAILED="true"
        fi
        if ! kubectl apply -f "${K8S_DIR}/overlays/prod/llm-server.yaml" >/dev/null; then
            warn "Failed to apply llm-server (LLM optional)"
            OPTIONAL_FAILED="true"
        fi
        if ! kubectl apply -f "${K8S_DIR}/overlays/prod/llm-service.yaml" >/dev/null; then
            warn "Failed to apply llm-service (LLM optional)"
            OPTIONAL_FAILED="true"
        fi

        if [[ "${ENABLE_GPU}" != "true" ]]; then
            kubectl set env deployment/llm-server -n "${NAMESPACE}" \
                LLM_DEVICE=cpu ENABLE_GPU=false DEVICE=cpu >/dev/null 2>&1 || true
            kubectl patch deploy llm-server -n "${NAMESPACE}" --type='json' \
                -p='[{"op":"remove","path":"/spec/template/spec/containers/0/resources/limits/nvidia.com~1gpu"},{"op":"remove","path":"/spec/template/spec/containers/0/resources/requests/nvidia.com~1gpu"}]' >/dev/null 2>&1 || true
        fi
    fi

    if [[ "${ENABLE_MEMORY}" == "true" ]]; then
        if ! kubectl apply -f "${K8S_DIR}/overlays/prod/postgres-init-scripts.yaml" >/dev/null; then
            warn "Failed to apply postgres-init scripts (Memory optional)"
            OPTIONAL_FAILED="true"
        fi
        if ! kubectl apply -f "${K8S_DIR}/overlays/prod/postgres-pgvector.yaml" >/dev/null; then
            warn "Failed to apply postgres-pgvector (Memory optional)"
            OPTIONAL_FAILED="true"
        fi
        kubectl scale statefulset postgres-pgvector --replicas=1 -n "${NAMESPACE}" >/dev/null 2>&1 || true
        if ! kubectl apply -f "${K8S_DIR}/overlays/prod/memory-service.yaml" >/dev/null; then
            warn "Failed to apply memory-service (Memory optional)"
            OPTIONAL_FAILED="true"
        fi
        kubectl scale deployment memory-service --replicas=1 -n "${NAMESPACE}" >/dev/null 2>&1 || true

        # Memory requires embedding-service
        if [[ "${ENABLE_LLM}" != "true" ]]; then
            if ! kubectl apply -f "${K8S_DIR}/overlays/prod/embedding-service.yaml" >/dev/null; then
                warn "Failed to apply embedding-service for Memory (optional)"
                OPTIONAL_FAILED="true"
            fi
        fi
    else
        kubectl scale statefulset postgres-pgvector --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
        kubectl scale deployment memory-service --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    fi
else
    # Ensure LLM/Memory stack is down when disabled
    kubectl scale deployment llm-server llm-service embedding-service --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    kubectl scale statefulset postgres-pgvector --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    kubectl scale deployment memory-service --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
fi

# Wait for core services
info "Waiting for core services..."
wait_rollout statefulset postgres 180s
wait_rollout deployment security-service 180s
wait_rollout deployment api-gateway 180s
wait_rollout deployment orchestrator 180s
wait_rollout deployment life-tracker 180s
wait_rollout deployment analytics-service 180s
wait_rollout deployment planner-service 180s
wait_rollout deployment user-profile 180s
wait_rollout deployment nlp-service 180s
wait_rollout deployment pc-control 180s
wait_rollout deployment smart-home-service 180s
wait_rollout deployment voice-gateway 180s

# Optional waits
if [[ "${ENABLE_LLM}" == "true" ]]; then
    wait_rollout deployment embedding-service 180s
    wait_rollout deployment llm-server 600s
    wait_rollout deployment llm-service 180s
fi
if [[ "${ENABLE_MEMORY}" == "true" ]]; then
    wait_rollout statefulset postgres-pgvector 180s
    wait_rollout deployment memory-service 180s
fi

# Endpoints
API_URL="https://api.jarvis.local"
VOICE_WS_URL="wss://voice.jarvis.local"
RUN_API_URL="${API_URL}"
RUN_VOICE_URL="${VOICE_WS_URL}"

echo ""
echo -e "${GREEN}✓${NC} Jarvis backend is up"
echo ""
echo -e "  ${CYAN}API Gateway (HTTPS):${NC} ${API_URL}"
echo -e "  ${CYAN}Voice Gateway (WSS):${NC} ${VOICE_WS_URL}"
echo ""
echo -e "${YELLOW}Next:${NC}"
echo "  - Trust CA: sudo ./scripts/product/jarvis-install-tls.sh"
echo "  - /etc/hosts: sudo ./scripts/product/jarvis-setup-hosts.sh"

if [[ "${OPTIONAL_FAILED}" == "true" ]]; then
    RUN_STATUS="degraded"
else
    RUN_STATUS="ready"
fi
write_run_summary

if [[ "${ENABLE_PORT_FORWARD}" == "true" ]]; then
    info "Starting port-forward (debug)..."
    kubectl port-forward svc/api-gateway 8080:8080 -n "${NAMESPACE}" >/dev/null 2>&1 &
    kubectl port-forward svc/voice-gateway 8081:8081 -n "${NAMESPACE}" >/dev/null 2>&1 &
    echo -e "  ${CYAN}[DEBUG]${NC} http://localhost:8080"
fi
