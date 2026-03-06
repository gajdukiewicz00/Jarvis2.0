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
ENABLE_IMPORT="${ENABLE_IMPORT:-auto}"
ENABLE_PORT_FORWARD="${ENABLE_PORT_FORWARD:-false}"

# Image configuration (single source of truth for build + deploy)
IMAGE_REGISTRY="${IMAGE_REGISTRY:-}"
IMAGE_REPO="${IMAGE_REPO:-jarvis}"
IMAGE_TAG="${IMAGE_TAG:-local}"

CORE_SERVICES=(
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

LLM_SERVICES=(
    "llm-server"
    "llm-service"
)

MEMORY_SERVICES=(
    "embedding-service"
    "memory-service"
)

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

IMAGE_CONFIG_READY="false"
IMAGE_BASE=""

trim_slashes() {
    local value="$1"
    value="${value#/}"
    value="${value%/}"
    printf '%s' "${value}"
}

init_image_config() {
    if [[ "${IMAGE_CONFIG_READY}" == "true" ]]; then
        return 0
    fi

    local registry
    local repo
    registry="$(trim_slashes "${IMAGE_REGISTRY}")"
    repo="$(trim_slashes "${IMAGE_REPO}")"

    if [[ -z "${repo}" ]]; then
        fail "IMAGE_REPO must not be empty"
    fi
    if [[ -z "${IMAGE_TAG}" ]]; then
        fail "IMAGE_TAG must not be empty"
    fi

    if [[ -n "${registry}" ]]; then
        IMAGE_BASE="${registry}/${repo}"
    else
        IMAGE_BASE="${repo}"
    fi

    IMAGE_CONFIG_READY="true"
    info "Using image base '${IMAGE_BASE}' with tag '${IMAGE_TAG}'"
}

image_name() {
    local service="$1"
    init_image_config
    printf '%s/%s' "${IMAGE_BASE}" "${service}"
}

image_ref() {
    local service="$1"
    printf '%s:%s' "$(image_name "${service}")" "${IMAGE_TAG}"
}

prepare_prod_overlay() {
    local source_overlay="${K8S_DIR}/overlays/prod"
    local rendered_overlay
    rendered_overlay="$(mktemp -d -t jarvis-prod-overlay-XXXXXX)"
    cp -R "${source_overlay}/." "${rendered_overlay}/"
    cp -R "${K8S_DIR}/base" "${rendered_overlay}/_base"

    local escaped_tag
    local escaped_base
    escaped_tag="$(printf '%s' "${IMAGE_TAG}" | sed 's/[\\/&]/\\&/g')"
    escaped_base="$(printf '%s' "${IMAGE_BASE}" | sed 's/[\\/&]/\\&/g')"

    # We render overlay to a temp dir, so relative "../../base" must be rewritten.
    sed -i -E "s#(^[[:space:]]*-[[:space:]]+)../../base\$#\\1./_base#g" "${rendered_overlay}/kustomization.yaml"
    sed -i -E "s#(^[[:space:]]*newName:[[:space:]]+)jarvis/#\\1${escaped_base}/#g" "${rendered_overlay}/kustomization.yaml"
    sed -i -E "s#(^[[:space:]]*newTag:[[:space:]]+).*\$#\\1${escaped_tag}#g" "${rendered_overlay}/kustomization.yaml"

    printf '%s' "${rendered_overlay}"
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
    local kubelet_version
    local server
    ctx=$(kubectl config current-context 2>/dev/null || true)
    if [[ -n "${KUBECONFIG:-}" && "${KUBECONFIG}" == "/etc/rancher/k3s/k3s.yaml" ]]; then
        return 0
    fi
    if [[ -n "${KUBECONFIG:-}" && "${KUBECONFIG}" == "${JARVIS_HOME}/kubeconfig" ]]; then
        return 0
    fi
    if [[ "${ctx}" == *"k3s"* ]]; then
        return 0
    fi

    # Fallback heuristic for local single-node k3s installs.
    server="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null || true)"
    if [[ "${server}" == "https://127.0.0.1:6443" ]]; then
        kubelet_version="$(kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.kubeletVersion}' 2>/dev/null || true)"
        [[ "${kubelet_version}" == *"+k3s"* ]] && return 0
    fi

    return 1
}

ensure_kubeconfig() {
    if [[ -n "${KUBECONFIG:-}" ]]; then
        return 0
    fi

    if [[ -r "${JARVIS_HOME}/kubeconfig" ]]; then
        export KUBECONFIG="${JARVIS_HOME}/kubeconfig"
        info "Using user kubeconfig: ${KUBECONFIG}"
        return 0
    fi

    if [[ -r /etc/rancher/k3s/k3s.yaml ]]; then
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

ensure_ingress_routing() {
    # k3s ships with Traefik LoadBalancer enabled by default.
    # If both Traefik and ingress-nginx expose 80/443, TLS requests to api.jarvis.local
    # may terminate on Traefik and return TRAEFIK DEFAULT CERT.
    if ! is_k3s_context; then
        return 0
    fi

    local disable_traefik
    disable_traefik="${JARVIS_DISABLE_K3S_TRAEFIK:-true}"
    if [[ "${disable_traefik,,}" != "true" && "${disable_traefik,,}" != "1" && "${disable_traefik,,}" != "yes" ]]; then
        info "Keeping k3s Traefik enabled (JARVIS_DISABLE_K3S_TRAEFIK=${disable_traefik})"
        return 0
    fi

    if kubectl -n kube-system get svc traefik >/dev/null 2>&1; then
        local traefik_type
        traefik_type="$(kubectl -n kube-system get svc traefik -o jsonpath='{.spec.type}' 2>/dev/null || true)"
        if [[ "${traefik_type}" == "LoadBalancer" ]]; then
            info "Disabling Traefik external LoadBalancer (avoid TLS cert conflicts on :443)..."
            kubectl -n kube-system patch svc traefik --type=merge -p '{"spec":{"type":"ClusterIP"}}' >/dev/null 2>&1 || \
                warn "Failed to patch kube-system/traefik service to ClusterIP"
        fi
    fi

    if kubectl -n kube-system get deployment traefik >/dev/null 2>&1; then
        kubectl -n kube-system scale deployment traefik --replicas=0 >/dev/null 2>&1 || \
            warn "Failed to scale kube-system/traefik deployment to 0"
    fi

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

    init_image_config

    local build_script="${PROJECT_DIR}/scripts/build-images.sh"
    if [[ ! -x "${build_script}" ]]; then
        fail "Build script not found or not executable: ${build_script}"
    fi

    local import_mode
    import_mode="${ENABLE_IMPORT,,}"
    if [[ -z "${import_mode}" ]]; then
        import_mode="auto"
    fi

    local build_args=()
    local require_k3s_context="true"

    case "${import_mode}" in
        auto)
            ;;
        true|1|yes|on)
            require_k3s_context="false"
            ;;
        false|0|no|off)
            build_args+=(--no-import)
            require_k3s_context="false"
            ;;
        *)
            warn "Unknown ENABLE_IMPORT='${ENABLE_IMPORT}', using auto"
            ;;
    esac

    info "Delegating image build/import to scripts/build-images.sh"
    ENABLE_LLM="${ENABLE_LLM}" \
    ENABLE_MEMORY="${ENABLE_MEMORY}" \
    IMAGE_REGISTRY="${IMAGE_REGISTRY}" \
    IMAGE_REPO="${IMAGE_REPO}" \
    IMAGE_TAG="${IMAGE_TAG}" \
    BUILD_MVN_CLEAN=false \
    JARVIS_IMPORT_REQUIRE_K3S_CONTEXT="${require_k3s_context}" \
    JARVIS_K3S_IMPORT_MODE=auto \
    JARVIS_NONINTERACTIVE="${JARVIS_NONINTERACTIVE:-}" \
        "${build_script}" "${build_args[@]}"
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
ensure_ingress_routing

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
    init_image_config
    OVERLAY_PATH="$(prepare_prod_overlay)"
    trap '[[ -n "${OVERLAY_PATH:-}" && -d "${OVERLAY_PATH}" ]] && rm -rf "${OVERLAY_PATH}"' EXIT
    MODELS_PATH="${JARVIS_HOME}/models"
    MODELS_PATH_ENV_FILE="${OVERLAY_PATH}/models-path.env"
    printf "JARVIS_MODELS_PATH=%s\n" "${MODELS_PATH}" > "${MODELS_PATH_ENV_FILE}"
    kubectl kustomize --load-restrictor=LoadRestrictionsNone "${OVERLAY_PATH}" | \
        kubectl apply -f - >/dev/null
else
    fail "Overlay not found: ${K8S_DIR}/overlays/prod"
fi

# Optional LLM/Memory runtime scaling
if [[ "${ENABLE_LLM}" == "true" ]]; then
    kubectl scale deployment llm-server llm-service --replicas=1 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    kubectl scale deployment embedding-service --replicas=1 -n "${NAMESPACE}" >/dev/null 2>&1 || true

    if [[ "${ENABLE_GPU}" != "true" ]]; then
        kubectl set env deployment/llm-server -n "${NAMESPACE}" \
            LLM_DEVICE=cpu ENABLE_GPU=false DEVICE=cpu >/dev/null 2>&1 || true
        kubectl patch deploy llm-server -n "${NAMESPACE}" --type='json' \
            -p='[{"op":"remove","path":"/spec/template/spec/containers/0/resources/limits/nvidia.com~1gpu"},{"op":"remove","path":"/spec/template/spec/containers/0/resources/requests/nvidia.com~1gpu"}]' >/dev/null 2>&1 || true
    fi
else
    kubectl scale deployment llm-server llm-service --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
fi

if [[ "${ENABLE_MEMORY}" == "true" ]]; then
    kubectl scale statefulset postgres-pgvector --replicas=1 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    kubectl scale deployment memory-service --replicas=1 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    # Memory requires embedding-service even when LLM is disabled.
    kubectl scale deployment embedding-service --replicas=1 -n "${NAMESPACE}" >/dev/null 2>&1 || true
else
    kubectl scale statefulset postgres-pgvector --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    kubectl scale deployment memory-service --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true

    if [[ "${ENABLE_LLM}" != "true" ]]; then
        kubectl scale deployment embedding-service --replicas=0 -n "${NAMESPACE}" >/dev/null 2>&1 || true
    fi
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
