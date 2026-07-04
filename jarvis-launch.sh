#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Kubernetes Launch Script (prod-only)
# =============================================================================
# Brings up: k3s -> ingress-nginx -> HTTPS -> services
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}"
# Canonical source of truth for Kubernetes manifests. infra/k8s/ supersedes the
# legacy k8s/ tree (see infra/k8s/README.md). JARVIS_K8S_DIR override exists for
# migration smoke tests only and is not honored by production tooling.
K8S_DIR="${JARVIS_K8S_DIR:-${PROJECT_DIR}/infra/k8s}"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"

JARVIS_HOME="${HOME}/.jarvis"
JARVIS_MODELS_DIR="${JARVIS_MODELS_DIR:-${PROJECT_DIR}/models}"
TLS_DIR="${JARVIS_HOME}/tls"
SECRETS_FILE="${JARVIS_HOME}/secrets/secrets.env"
LOG_DIR="${JARVIS_LOG_DIR:-${JARVIS_HOME}/logs}"
BACKEND_LOG="${LOG_DIR}/backend-launch.log"
RUN_SUMMARY="${JARVIS_HOME}/run/last-run.json"
OBSERVABILITY_KUSTOMIZATION_DIR="${K8S_DIR}/base/observability"
OBSERVABILITY_VERIFY_SCRIPT="${PROJECT_DIR}/scripts/verify-observability.sh"
GRAFANA_DASHBOARD_PROVISION_SCRIPT="${PROJECT_DIR}/scripts/provision-grafana-dashboards.sh"
GRAFANA_ADMIN_SYNC_SCRIPT="${PROJECT_DIR}/scripts/sync-grafana-admin.sh"
GRAFANA_SECRET_NAME="jarvis-secrets"
GRAFANA_SECRET_USER_KEY="GRAFANA_ADMIN_USER"
GRAFANA_SECRET_PASSWORD_KEY="GRAFANA_ADMIN_PASSWORD"

# Feature flags
# A "full" launch (memory + llm + workstation-local vision-security) is the
# default so user-facing flows like /api/v1/memory/recent and
# /api/v1/llm/* surface real data instead of FEATURE_DISABLED. Operators can
# downgrade to core-only with --core-only (no memory / no LLM / no vision)
# or disable each independently.
ENABLE_LLM="${ENABLE_LLM:-true}"
ENABLE_MEMORY="${ENABLE_MEMORY:-true}"
ENABLE_GPU="${ENABLE_GPU:-true}"
# vision-security-service is workstation-local by design (camera + screen).
# When enabled, jarvis-launch.sh starts the host Java process on port 8094
# and patches the selectorless Endpoints object so api-gateway pods can
# proxy /api/v1/vision-security/** to the host.
ENABLE_VISION_SECURITY="${ENABLE_VISION_SECURITY:-true}"
ENABLE_BUILD="${ENABLE_BUILD:-true}"
ENABLE_IMPORT="${ENABLE_IMPORT:-auto}"
ENABLE_PORT_FORWARD="${ENABLE_PORT_FORWARD:-false}"
REQUIRE_VOICE_GATEWAY="${JARVIS_REQUIRE_VOICE_GATEWAY:-false}"
FORCE_REDEPLOY="${JARVIS_FORCE_REDEPLOY:-false}"
VISION_SECURITY_BRIDGE_WIRED="false"

# Image configuration (single source of truth for build + deploy)
IMAGE_REGISTRY="${IMAGE_REGISTRY:-localhost:5000}"
IMAGE_REPO="${IMAGE_REPO:-jarvis}"
IMAGE_TAG="${IMAGE_TAG:-local}"
RELEASE_OVERLAY_PATH="${JARVIS_RELEASE_OVERLAY_PATH:-${K8S_DIR}/overlays/prod-release}"
USE_RELEASE_OVERLAY="false"
DRY_RUN="false"

print_usage() {
    cat <<EOF
Usage: ./jarvis-launch.sh [options]

Options:
  --help, -h                 Show this help and exit without touching the cluster
  --dry-run                  Print the resolved deploy plan and exit without side effects
  --force-redeploy           Skip readiness short-circuit checks and re-apply the deploy path
  --release-overlay[=PATH]   Deploy from a digest-pinned prod-release overlay instead of mutable local tags
  --full                     Bring up memory + LLM + workstation-local
                             vision-security (the launcher default)
  --core-only                Skip memory + LLM + vision-security (only core
                             backend, observability, voice-gateway)
  --enable-llm               Request optional LLM workloads (on by default; pair with --core-only)
  --disable-llm              Skip LLM workloads even in full mode
  --enable-memory            Request optional memory workloads (on by default; pair with --core-only)
  --disable-memory           Skip memory workloads even in full mode
  --enable-vision-security   Start the workstation-local vision-security
                             service and wire the k8s host bridge (default)
  --disable-vision-security  Skip vision-security; api-gateway keeps the
                             /api/v1/vision-security/** route in FEATURE_DISABLED
  --disable-gpu              Disable GPU-dependent LLM setup for this run

Notes:
  - Default launcher mode is the mutable local k3s/dev path with memory + LLM enabled.
  - --core-only matches the "minimal smoke" pre-2026-05 default if you don't have a model PVC seeded.
  - Honest release deployments should use --release-overlay or ./scripts/product/jarvis-deploy-prod.sh.
EOF
}

for arg in "$@"; do
    case "${arg}" in
        --help|-h)
            print_usage
            exit 0
            ;;
        --dry-run)
            DRY_RUN="true"
            ;;
        --force-redeploy)
            FORCE_REDEPLOY="true"
            ;;
        --release-overlay)
            USE_RELEASE_OVERLAY="true"
            ;;
        --release-overlay=*)
            USE_RELEASE_OVERLAY="true"
            RELEASE_OVERLAY_PATH="${arg#*=}"
            ;;
        --enable-llm)
            ENABLE_LLM="true"
            ;;
        --disable-llm)
            ENABLE_LLM="false"
            ;;
        --enable-memory)
            ENABLE_MEMORY="true"
            ;;
        --disable-memory)
            ENABLE_MEMORY="false"
            ;;
        --enable-vision-security)
            ENABLE_VISION_SECURITY="true"
            ;;
        --disable-vision-security)
            ENABLE_VISION_SECURITY="false"
            ;;
        --full)
            ENABLE_LLM="true"
            ENABLE_MEMORY="true"
            ENABLE_VISION_SECURITY="true"
            ;;
        --core-only)
            ENABLE_LLM="false"
            ENABLE_MEMORY="false"
            ENABLE_VISION_SECURITY="false"
            ;;
        --disable-gpu)
            ENABLE_GPU="false"
            ;;
        *)
            echo "❌ Unknown argument: ${arg}" >&2
            print_usage >&2
            exit 1
            ;;
    esac
done

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

OBSERVABILITY_DEPLOYMENTS=(
    "grafana"
    "prometheus"
    "loki"
    "tempo"
    "alloy"
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
RUN_GRAFANA_URL="https://grafana.jarvis.local"
OPTIONAL_FAILED="false"
GRAFANA_RESTART_REQUIRED="false"

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
  "runtimeMode": "k8s",
  "apiUrl": "${RUN_API_URL}",
  "voiceUrl": "${RUN_VOICE_URL}",
  "grafanaUrl": "${RUN_GRAFANA_URL}",
  "enableLlm": "${ENABLE_LLM}",
  "enableMemory": "${ENABLE_MEMORY}",
  "enableGpu": "${ENABLE_GPU}",
  "enableVisionSecurity": "${ENABLE_VISION_SECURITY}",
  "visionSecurityBridgeWired": "${VISION_SECURITY_BRIDGE_WIRED}"
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

print_dry_run_summary() {
    init_image_config

    echo "Jarvis launch dry run"
    echo "  mode: $([[ "${USE_RELEASE_OVERLAY}" == "true" ]] && echo "digest-pinned release overlay" || echo "mutable local launcher")"
    if [[ "${USE_RELEASE_OVERLAY}" == "true" ]]; then
        echo "  release overlay: ${RELEASE_OVERLAY_PATH}"
        if [[ -f "${RELEASE_OVERLAY_PATH}/kustomization.yaml" ]]; then
            echo "  overlay status: present"
        else
            echo "  overlay status: missing"
        fi
    else
        echo "  image base: ${IMAGE_BASE}"
        echo "  image tag: ${IMAGE_TAG}"
        echo "  mutable skip-redeploy: disabled"
    fi
    echo "  optional llm: ${ENABLE_LLM}"
    echo "  optional memory: ${ENABLE_MEMORY}"
    echo "  optional vision-security host bridge: ${ENABLE_VISION_SECURITY}"
    echo "  gpu requested: ${ENABLE_GPU}"
    echo "  force redeploy: ${FORCE_REDEPLOY}"
    echo ""
    echo "No cluster or filesystem changes were made."
}

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

mutable_app_deployments() {
    local -a candidates=("${CORE_SERVICES[@]}" "sync-service")
    local deployment
    local -A seen=()

    if [[ "${ENABLE_LLM}" == "true" ]]; then
        candidates+=("${LLM_SERVICES[@]}")
    fi
    if [[ "${ENABLE_MEMORY}" == "true" ]]; then
        candidates+=("${MEMORY_SERVICES[@]}")
    fi

    for deployment in "${candidates[@]}"; do
        if [[ -n "${seen[${deployment}]:-}" ]]; then
            continue
        fi
        seen["${deployment}"]=1
        printf '%s\n' "${deployment}"
    done
}

force_mutable_image_refresh_policy() {
    if [[ "${USE_RELEASE_OVERLAY}" == "true" ]]; then
        return 0
    fi

    local deployment
    while IFS= read -r deployment; do
        if [[ -z "${deployment}" ]]; then
            continue
        fi
        if ! kubectl get deployment "${deployment}" -n "${NAMESPACE}" >/dev/null 2>&1; then
            continue
        fi
        if ! kubectl patch deployment "${deployment}" -n "${NAMESPACE}" --type='json' \
            -p='[{"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"Always"}]' \
            >/dev/null; then
            warn "Failed to set imagePullPolicy=Always on deployment/${deployment}"
            OPTIONAL_FAILED="true"
        fi
    done < <(mutable_app_deployments)
}

restart_mutable_app_workloads() {
    if [[ "${USE_RELEASE_OVERLAY}" == "true" ]]; then
        return 0
    fi

    local -a refs=()
    local deployment
    while IFS= read -r deployment; do
        if [[ -z "${deployment}" ]]; then
            continue
        fi
        if kubectl get deployment "${deployment}" -n "${NAMESPACE}" >/dev/null 2>&1; then
            refs+=("deployment/${deployment}")
        fi
    done < <(mutable_app_deployments)

    if [[ "${#refs[@]}" -eq 0 ]]; then
        return 0
    fi

    info "Restarting mutable app workloads so ${IMAGE_BASE}/*:${IMAGE_TAG} images are refreshed..."
    if ! kubectl rollout restart -n "${NAMESPACE}" "${refs[@]}" >/dev/null; then
        warn "Failed to restart one or more mutable app workloads"
        OPTIONAL_FAILED="true"
    fi
}

has_release_overlay() {
    [[ -f "${RELEASE_OVERLAY_PATH}/kustomization.yaml" ]]
}

release_overlay_includes_service() {
    local service="$1"
    has_release_overlay || return 1
    grep -Eq "^[[:space:]]*name:[[:space:]]*jarvis/${service}[[:space:]]*$" "${RELEASE_OVERLAY_PATH}/kustomization.yaml"
}

ensure_release_overlay_supports_requested_features() {
    if [[ "${USE_RELEASE_OVERLAY}" != "true" ]]; then
        return 0
    fi

    has_release_overlay || fail "Missing digest-pinned release overlay: ${RELEASE_OVERLAY_PATH}/kustomization.yaml. Generate it with ./scripts/product/jarvis-promote-images.sh first."

    if [[ "${ENABLE_LLM}" == "true" ]]; then
        release_overlay_includes_service "llm-server" || fail "Release overlay does not pin llm-server. Regenerate it with ./scripts/product/jarvis-promote-images.sh --include-llm."
        release_overlay_includes_service "llm-service" || fail "Release overlay does not pin llm-service. Regenerate it with ./scripts/product/jarvis-promote-images.sh --include-llm."
    fi

    if [[ "${ENABLE_MEMORY}" == "true" ]]; then
        release_overlay_includes_service "embedding-service" || fail "Release overlay does not pin embedding-service. Regenerate it with ./scripts/product/jarvis-promote-images.sh --include-data."
        release_overlay_includes_service "memory-service" || fail "Release overlay does not pin memory-service. Regenerate it with ./scripts/product/jarvis-promote-images.sh --include-data."
    fi
}

apply_release_overlay() {
    ensure_release_overlay_supports_requested_features
    "${PROJECT_DIR}/scripts/product/jarvis-deploy-prod.sh" --namespace="${NAMESPACE}" --overlay="${RELEASE_OVERLAY_PATH}"
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

host_global_ipv4s() {
    ip -4 -o addr show scope global 2>/dev/null | awk '{split($4, cidr, "/"); print cidr[1]}' || true
}

host_has_global_ipv4() {
    local target="$1"
    [[ -n "${target}" ]] || return 1
    host_global_ipv4s | grep -Fxq "${target}"
}

default_route_ipv4() {
    local src=""
    local iface=""

    src="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "src") {print $(i + 1); exit}}')"
    if [[ -n "${src}" ]]; then
        printf '%s' "${src}"
        return 0
    fi

    iface="$(ip -4 route show default 2>/dev/null | awk 'NR == 1 {print $5}')"
    if [[ -n "${iface}" ]]; then
        ip -4 -o addr show dev "${iface}" scope global 2>/dev/null | awk 'NR == 1 {split($4, cidr, "/"); print cidr[1]}'
        return 0
    fi

    host_global_ipv4s | awk '
        $0 !~ /^127\./ &&
        $0 !~ /^10\.42\./ &&
        $0 !~ /^10\.43\./ &&
        $0 !~ /^172\.(17|18|19)\./ {
            print
            exit
        }
    '
}

api_listener_reachable() {
    local target_ip="$1"
    [[ -n "${target_ip}" ]] || return 1
    curl -sk --connect-timeout 3 --max-time 5 "https://${target_ip}:6443/version" >/dev/null 2>&1
}

write_k3s_network_config() {
    local desired_ip="$1"
    local target_file="/etc/rancher/k3s/config.yaml"
    local tmp_file=""

    tmp_file="$(mktemp -t jarvis-k3s-config-XXXXXX)"
    if run_privileged test -f "${target_file}"; then
        run_privileged cat "${target_file}" > "${tmp_file}"
    fi

    if grep -Eq '^[[:space:]]*node-ip:' "${tmp_file}"; then
        sed -i -E "s#^[[:space:]]*node-ip:.*#node-ip: ${desired_ip}#" "${tmp_file}"
    else
        printf '\nnode-ip: %s\n' "${desired_ip}" >> "${tmp_file}"
    fi

    if grep -Eq '^[[:space:]]*advertise-address:' "${tmp_file}"; then
        sed -i -E "s#^[[:space:]]*advertise-address:.*#advertise-address: ${desired_ip}#" "${tmp_file}"
    else
        printf 'advertise-address: %s\n' "${desired_ip}" >> "${tmp_file}"
    fi

    run_privileged install -d -m 755 /etc/rancher/k3s

    if run_privileged test -f "${target_file}" && run_privileged cmp -s "${tmp_file}" "${target_file}"; then
        rm -f "${tmp_file}"
        return 1
    fi

    run_privileged install -m 600 "${tmp_file}" "${target_file}"
    rm -f "${tmp_file}"
    return 0
}

wait_for_k3s_network_alignment() {
    local expected_ip="$1"
    local node_name="$2"
    local deadline=$((SECONDS + 180))

    while (( SECONDS < deadline )); do
        local current_node_ip=""
        local current_endpoint_ip=""

        if ! kubectl cluster-info --request-timeout=5s >/dev/null 2>&1; then
            sleep 3
            continue
        fi

        current_node_ip="$(kubectl get node "${node_name}" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || true)"
        current_endpoint_ip="$(kubectl get endpoints kubernetes -n default -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)"

        if [[ "${current_node_ip}" == "${expected_ip}" && "${current_endpoint_ip}" == "${expected_ip}" ]]; then
            return 0
        fi

        sleep 3
    done

    return 1
}

# Single-node k3s can retain a dead InternalIP after a host network change.
# That breaks kubernetes.default.svc for in-cluster clients like Prometheus,
# Alloy, and Kyverno, so repair the advertised node IP before workload checks.
ensure_k3s_network_alignment() {
    local node_name=""
    local node_ip=""
    local endpoint_ip=""
    local desired_ip=""
    local needs_repair="false"
    local -a reasons=()

    if ! is_k3s_context; then
        return 0
    fi
    if ! command -v systemctl >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
        return 0
    fi
    if [[ "$(systemctl show -p LoadState --value k3s 2>/dev/null || true)" != "loaded" ]]; then
        return 0
    fi

    node_name="$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    [[ -n "${node_name}" ]] || return 0

    desired_ip="$(default_route_ipv4)"
    [[ -n "${desired_ip}" ]] || {
        warn "Unable to determine the current host IPv4 for k3s network alignment"
        return 0
    }

    node_ip="$(kubectl get node "${node_name}" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || true)"
    endpoint_ip="$(kubectl get endpoints kubernetes -n default -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)"

    if [[ -n "${node_ip}" ]] && ! host_has_global_ipv4 "${node_ip}"; then
        needs_repair="true"
        reasons+=("node InternalIP ${node_ip} is not assigned on this host")
    fi
    if [[ -n "${node_ip}" ]] && ! api_listener_reachable "${node_ip}"; then
        needs_repair="true"
        reasons+=("node InternalIP ${node_ip}:6443 is not reachable")
    fi
    if [[ -n "${endpoint_ip}" ]] && ! api_listener_reachable "${endpoint_ip}"; then
        needs_repair="true"
        reasons+=("service/kubernetes endpoint ${endpoint_ip}:6443 is not reachable")
    fi

    if [[ "${needs_repair}" != "true" ]]; then
        return 0
    fi

    if ! api_listener_reachable "${desired_ip}"; then
        fail "Detected stale k3s control-plane IP (${node_ip:-unknown}), but current host IP ${desired_ip}:6443 is not reachable"
    fi

    warn "Detected stale k3s control-plane networking: ${reasons[*]}"
    info "Aligning k3s node-ip and advertise-address to ${desired_ip}"
    write_k3s_network_config "${desired_ip}" || true

    info "Restarting k3s to refresh node/${node_name} and service/kubernetes..."
    run_privileged systemctl restart k3s

    if ! wait_for_k3s_network_alignment "${desired_ip}" "${node_name}"; then
        fail "k3s restarted but node/${node_name} and service/kubernetes did not converge to ${desired_ip}"
    fi

    info "k3s control-plane networking aligned to ${desired_ip}"
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
        if is_k3s_context; then
            info "Using kubeconfig from environment: ${KUBECONFIG}"
            return 0
        fi
        warn "Ignoring KUBECONFIG='${KUBECONFIG}' (non-k3s), using local k3s config"
        unset KUBECONFIG
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

read_env_file_value() {
    local file_path="$1"
    local key="$2"
    [[ -f "${file_path}" ]] || return 0

    python3 - "${file_path}" "${key}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
target = sys.argv[2]

for raw in path.read_text(encoding="utf-8").splitlines():
    stripped = raw.strip()
    if not stripped or stripped.startswith("#") or "=" not in raw:
        continue
    key, value = raw.split("=", 1)
    if key.strip() != target:
        continue
    value = value.strip().strip('"').strip("'")
    print(value)
    break
PY
}

secret_key_value() {
    local key="$1"
    local encoded
    encoded="$(kubectl get secret "${GRAFANA_SECRET_NAME}" -n "${NAMESPACE}" -o "jsonpath={.data.${key}}" 2>/dev/null || true)"
    [[ -n "${encoded}" ]] || return 0
    printf '%s' "${encoded}" | base64 -d 2>/dev/null || true
}

grafana_secret_sync_required() {
    if ! kubectl get secret "${GRAFANA_SECRET_NAME}" -n "${NAMESPACE}" >/dev/null 2>&1; then
        return 0
    fi

    local secret_user secret_password
    secret_user="$(secret_key_value "${GRAFANA_SECRET_USER_KEY}")"
    secret_password="$(secret_key_value "${GRAFANA_SECRET_PASSWORD_KEY}")"

    if [[ -z "${secret_user}" || -z "${secret_password}" ]]; then
        return 0
    fi

    if [[ ! -f "${SECRETS_FILE}" ]]; then
        return 1
    fi

    local file_user file_password
    file_user="$(read_env_file_value "${SECRETS_FILE}" "${GRAFANA_SECRET_USER_KEY}")"
    file_password="$(read_env_file_value "${SECRETS_FILE}" "${GRAFANA_SECRET_PASSWORD_KEY}")"

    if [[ -z "${file_user}" || -z "${file_password}" ]]; then
        return 0
    fi

    [[ "${file_user}" != "${secret_user}" || "${file_password}" != "${secret_password}" ]]
}

ensure_secrets() {
    if kubectl get secret "${GRAFANA_SECRET_NAME}" -n "${NAMESPACE}" >/dev/null 2>&1 && ! grafana_secret_sync_required; then
        return 0
    fi

    if [[ -f "${SECRETS_FILE}" ]]; then
        local grafana_exists="false"
        if kubectl get deployment grafana -n "${NAMESPACE}" >/dev/null 2>&1; then
            grafana_exists="true"
        fi

        info "Applying local secrets..."
        "${PROJECT_DIR}/scripts/product/jarvis-secrets-apply.sh" >/dev/null
        if [[ "${grafana_exists}" == "true" ]]; then
            GRAFANA_RESTART_REQUIRED="true"
        fi
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
    info "Ensuring TLS certificates are current..."
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
    REGISTRY="${IMAGE_REGISTRY}" \
    IMAGE_NAMESPACE="${IMAGE_REPO}" \
    BUILD_MVN_CLEAN=false \
    JARVIS_IMPORT_REQUIRE_K3S_CONTEXT="${require_k3s_context}" \
    JARVIS_K3S_IMPORT_MODE=auto \
    JARVIS_NONINTERACTIVE="${JARVIS_NONINTERACTIVE:-}" \
        "${build_script}" "${build_args[@]}"
}

jsonpath_value() {
    local kind="$1"
    local name="$2"
    local path="$3"
    kubectl get "${kind}" "${name}" -n "${NAMESPACE}" -o "jsonpath=${path}" 2>/dev/null || true
}

workload_is_ready() {
    local kind="$1"
    local name="$2"
    local allow_zero="${3:-false}"
    local desired=""
    local ready=""
    local available=""
    local updated=""
    local current=""
    local generation=""
    local observed_generation=""

    case "${kind}" in
        deployment)
            desired="$(jsonpath_value deployment "${name}" '{.spec.replicas}')"
            ready="$(jsonpath_value deployment "${name}" '{.status.readyReplicas}')"
            available="$(jsonpath_value deployment "${name}" '{.status.availableReplicas}')"
            updated="$(jsonpath_value deployment "${name}" '{.status.updatedReplicas}')"
            current="$(jsonpath_value deployment "${name}" '{.status.replicas}')"
            generation="$(jsonpath_value deployment "${name}" '{.metadata.generation}')"
            observed_generation="$(jsonpath_value deployment "${name}" '{.status.observedGeneration}')"
            ;;
        statefulset)
            desired="$(jsonpath_value statefulset "${name}" '{.spec.replicas}')"
            ready="$(jsonpath_value statefulset "${name}" '{.status.readyReplicas}')"
            available="${ready}"
            ;;
        *)
            return 1
            ;;
    esac

    [[ "${desired}" =~ ^[0-9]+$ ]] || return 1
    [[ "${ready}" =~ ^[0-9]+$ ]] || ready=0
    [[ "${available}" =~ ^[0-9]+$ ]] || available=0
    [[ "${updated}" =~ ^[0-9]+$ ]] || updated="${ready}"
    [[ "${current}" =~ ^[0-9]+$ ]] || current="${desired}"

    if [[ "${desired}" -eq 0 ]]; then
        if [[ "${allow_zero}" == "true" ]]; then
            return 0
        fi
        return 1
    fi

    if [[ "${kind}" == "deployment" ]]; then
        if [[ "${generation}" =~ ^[0-9]+$ && "${observed_generation}" =~ ^[0-9]+$ && "${observed_generation}" -lt "${generation}" ]]; then
            return 1
        fi
        [[ "${updated}" -ge "${desired}" ]] || return 1
        [[ "${current}" -le "${updated}" ]] || return 1
    fi

    [[ "${ready}" -ge "${desired}" && "${available}" -ge "${desired}" ]]
}

wait_workload() {
    local kind="$1"
    local name="$2"
    local timeout="$3"
    local required="${4:-true}"
    local message="${5:-${kind}/${name} not ready}"

    if workload_is_ready "${kind}" "${name}"; then
        info "${kind}/${name} already available"
        return 0
    fi

    if kubectl rollout status "${kind}/${name}" -n "${NAMESPACE}" --timeout="${timeout}" >/dev/null 2>&1; then
        return 0
    fi

    if workload_is_ready "${kind}" "${name}"; then
        info "${kind}/${name} already available; rollout status is stale or paused"
        return 0
    fi

    if [[ "${required}" == "true" ]]; then
        fail "${message}"
    fi

    OPTIONAL_FAILED="true"
    warn "${message}"
    return 1
}

backend_core_ready() {
    workload_is_ready statefulset postgres || return 1
    workload_is_ready deployment security-service || return 1
    workload_is_ready deployment api-gateway || return 1
    workload_is_ready deployment orchestrator || return 1
    workload_is_ready deployment life-tracker || return 1
    workload_is_ready deployment analytics-service || return 1
    workload_is_ready deployment planner-service || return 1
    workload_is_ready deployment user-profile || return 1
    workload_is_ready deployment nlp-service || return 1
    workload_is_ready deployment pc-control || return 1
    workload_is_ready deployment smart-home-service || return 1

    if is_truthy "${REQUIRE_VOICE_GATEWAY}" && ! workload_is_ready deployment voice-gateway; then
        return 1
    fi

    if ! pods_have_no_image_pull_failures; then
        return 1
    fi

    return 0
}

pods_have_no_image_pull_failures() {
    # Surface ImagePullBackOff / ErrImagePull in the namespace so the launcher
    # cannot mark itself "ready" while a workload is permanently broken on its
    # image. Returns 0 when no failures, 1 otherwise.
    local broken
    broken="$(kubectl get pods -n "${NAMESPACE}" \
        -o "jsonpath={range .items[*]}{range .status.containerStatuses[*]}{.state.waiting.reason}{'|'}{end}{end}" 2>/dev/null \
        | tr '|' '\n' \
        | grep -E '^(ImagePullBackOff|ErrImagePull|CrashLoopBackOff)$' \
        | head -n 1 || true)"
    if [[ -n "${broken}" ]]; then
        warn "Pod(s) in ${NAMESPACE} are in ${broken} — readiness blocked"
        return 1
    fi
    return 0
}

assess_optional_workloads() {
    if is_truthy "${REQUIRE_VOICE_GATEWAY}"; then
        if ! workload_is_ready deployment voice-gateway; then
            fail "Required deployment/voice-gateway not ready"
        fi
    elif ! workload_is_ready deployment voice-gateway; then
        OPTIONAL_FAILED="true"
        warn "Optional deployment/voice-gateway not ready; backend is available in DEGRADED mode"
    fi

    if [[ "${ENABLE_LLM}" == "true" ]]; then
        if ! workload_is_ready deployment embedding-service || ! workload_is_ready deployment llm-server || ! workload_is_ready deployment llm-service; then
            OPTIONAL_FAILED="true"
            warn "Optional LLM workloads not fully ready"
        fi
    fi

    if [[ "${ENABLE_MEMORY}" == "true" ]]; then
        if ! workload_is_ready statefulset postgres-pgvector || ! workload_is_ready deployment memory-service; then
            OPTIONAL_FAILED="true"
            warn "Optional memory workloads not fully ready"
        fi
    fi

    if [[ "${ENABLE_VISION_SECURITY}" == "true" && "${VISION_SECURITY_BRIDGE_WIRED}" != "true" ]]; then
        OPTIONAL_FAILED="true"
        warn "Optional vision-security host bridge not wired"
    fi
}

observability_stack_ready() {
    local deployment
    for deployment in "${OBSERVABILITY_DEPLOYMENTS[@]}"; do
        if ! workload_is_ready deployment "${deployment}"; then
            return 1
        fi
    done

    return 0
}

deployment_is_paused() {
    local name="$1"
    [[ "$(kubectl get deployment "${name}" -n "${NAMESPACE}" -o jsonpath='{.spec.paused}' 2>/dev/null || true)" == "true" ]]
}

apply_observability_manifests() {
    local apply_output=""

    if [[ ! -f "${OBSERVABILITY_KUSTOMIZATION_DIR}/kustomization.yaml" ]]; then
        fail "Missing observability kustomization: ${OBSERVABILITY_KUSTOMIZATION_DIR}/kustomization.yaml"
    fi

    if ! apply_output="$(kubectl apply -k "${OBSERVABILITY_KUSTOMIZATION_DIR}" 2>&1)"; then
        echo "${apply_output}" >&2
        fail "Failed to apply observability kustomization: ${OBSERVABILITY_KUSTOMIZATION_DIR}"
    fi

    if grep -Eq 'configmap/grafana-datasources (created|configured)' <<<"${apply_output}"; then
        GRAFANA_RESTART_REQUIRED="true"
        info "Grafana provisioning config changed; a Grafana restart will be performed"
    fi
}

resume_paused_observability_deployments() {
    local deployment

    for deployment in "${OBSERVABILITY_DEPLOYMENTS[@]}"; do
        if ! deployment_is_paused "${deployment}"; then
            continue
        fi

        info "Resuming paused deployment/${deployment}..."
        if ! kubectl rollout resume "deployment/${deployment}" -n "${NAMESPACE}" >/dev/null; then
            fail "Failed to resume paused deployment/${deployment}"
        fi

        if [[ "${deployment}" == "grafana" ]]; then
            GRAFANA_RESTART_REQUIRED="true"
        fi
    done
}

restart_grafana_if_needed() {
    if [[ "${GRAFANA_RESTART_REQUIRED}" != "true" ]]; then
        return 0
    fi

    info "Restarting Grafana to reload provisioned datasources and dashboards..."
    if ! kubectl rollout restart deployment/grafana -n "${NAMESPACE}" >/dev/null; then
        fail "Failed to restart deployment/grafana after provisioning changes"
    fi
}

ensure_observability_stack() {
    info "Reconciling observability stack..."
    apply_observability_manifests
    resume_paused_observability_deployments
    restart_grafana_if_needed

    info "Waiting for Grafana / Prometheus / Loki / Tempo / Alloy..."
    wait_workload deployment grafana 180s true "deployment/grafana not ready"
    wait_workload deployment prometheus 180s true "deployment/prometheus not ready"
    wait_workload deployment loki 180s true "deployment/loki not ready"
    wait_workload deployment tempo 180s true "deployment/tempo not ready"
    wait_workload deployment alloy 180s true "deployment/alloy not ready"

    info "Observability stack ready"
}

verify_observability_stack() {
    if [[ ! -x "${OBSERVABILITY_VERIFY_SCRIPT}" ]]; then
        fail "Missing observability verification script: ${OBSERVABILITY_VERIFY_SCRIPT}"
    fi

    info "Verifying Grafana / Loki / Alloy / provisioning / Loki log ingestion..."
    if ! "${OBSERVABILITY_VERIFY_SCRIPT}"; then
        fail "Observability verification failed. See ${JARVIS_HOME}/run/observability-status.json for details."
    fi

    info "Observability verification passed"
}

provision_grafana_dashboards() {
    if [[ ! -x "${GRAFANA_DASHBOARD_PROVISION_SCRIPT}" ]]; then
        fail "Missing Grafana dashboard provisioning script: ${GRAFANA_DASHBOARD_PROVISION_SCRIPT}"
    fi

    info "Provisioning Grafana dashboards from repo files..."
    if ! "${GRAFANA_DASHBOARD_PROVISION_SCRIPT}"; then
        fail "Grafana dashboard provisioning failed"
    fi
}

sync_grafana_admin_credentials() {
    if [[ ! -x "${GRAFANA_ADMIN_SYNC_SCRIPT}" ]]; then
        fail "Missing Grafana admin sync script: ${GRAFANA_ADMIN_SYNC_SCRIPT}"
    fi

    info "Synchronizing Grafana admin credentials from jarvis-secrets..."
    if ! "${GRAFANA_ADMIN_SYNC_SCRIPT}"; then
        fail "Grafana admin credential sync failed"
    fi
}

print_ready_status() {
    local overall_status="$1"

    echo ""
    echo -e "${GREEN}✓${NC} Jarvis backend is up"
    echo ""
    echo -e "  ${CYAN}API Gateway (HTTPS):${NC} ${RUN_API_URL}"
    echo -e "  ${CYAN}Voice Gateway (WSS):${NC} ${RUN_VOICE_URL}"
    echo -e "  ${CYAN}Grafana:${NC} ${RUN_GRAFANA_URL}"
    if [[ "${ENABLE_VISION_SECURITY}" == "true" ]]; then
        if [[ "${VISION_SECURITY_BRIDGE_WIRED}" == "true" ]]; then
            echo -e "  ${CYAN}Vision Security (host bridge):${NC} http://127.0.0.1:8094  →  vision-security-service.${NAMESPACE}.svc.cluster.local:8094"
        else
            echo -e "  ${YELLOW}Vision Security:${NC} requested but host bridge not wired (see warnings)"
        fi
    else
        echo -e "  ${CYAN}Vision Security:${NC} disabled (--disable-vision-security or --core-only)"
    fi
    echo -e "  ${CYAN}TTS:${NC} optional in k8s — voice-gateway returns TTS_UNAVAILABLE when espeak-ng is not in the image (JARVIS_VOICE_TTS_REQUIRED_FOR_READINESS=false keeps readiness UP)"
    echo ""
    echo -e "${CYAN}Status:${NC} ${overall_status}"
    echo -e "${YELLOW}Next:${NC}"
    echo "  - Open monitoring: ${RUN_GRAFANA_URL}"
    echo "  - Grafana credentials: ${SECRETS_FILE} (${GRAFANA_SECRET_USER_KEY} / ${GRAFANA_SECRET_PASSWORD_KEY})"
    echo "  - Observability proof: ${JARVIS_HOME}/run/observability-status.json"
}

maybe_skip_redeploy() {
    if is_truthy "${FORCE_REDEPLOY}"; then
        return 1
    fi

    if [[ "${USE_RELEASE_OVERLAY}" != "true" ]]; then
        info "Mutable launcher mode is active; forcing redeploy to avoid stale-runtime drift"
        return 1
    fi

    ensure_release_overlay_supports_requested_features

    if ! backend_core_ready; then
        return 1
    fi

    if ! "${PROJECT_DIR}/scripts/product/jarvis-rollout-validate.sh" --namespace="${NAMESPACE}" --overlay="${RELEASE_OVERLAY_PATH}" >/dev/null 2>&1; then
        info "Live cluster does not match the digest-pinned release overlay; redeploy required"
        return 1
    fi

    verify_observability_stack

    info "Backend already matches the digest-pinned release overlay; skipping rebuild/reapply"
    assess_optional_workloads
    if [[ "${OPTIONAL_FAILED}" == "true" ]]; then
        RUN_STATUS="degraded"
        print_ready_status "DEGRADED"
    else
        RUN_STATUS="ready"
        print_ready_status "READY"
    fi
    write_run_summary
    info "Set JARVIS_FORCE_REDEPLOY=true to force rebuild/reapply"
    return 0
}

print_header() {
    echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║   🤖 JARVIS 2.0 (Kubernetes) 🤖       ║${NC}"
    echo -e "${BLUE}║         Starting up...                 ║${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
    echo ""
}

# Workstation-local vision-security-service bridge.
# vision-security-service requires camera + screen access on the host, so we
# run it as a host Java process and expose it to api-gateway pods via the
# selectorless Service in infra/k8s/base/vision-security-service/. After the
# host process is healthy we patch the Endpoints object to the host's LAN IP
# (same pattern as the host-model-daemon bridge).
ensure_vision_security_local_bridge() {
    if [[ "${ENABLE_VISION_SECURITY}" != "true" ]]; then
        info "vision-security disabled; skipping workstation host bridge"
        return 0
    fi

    local up_script="${PROJECT_DIR}/scripts/product/jarvis-vision-security-up.sh"
    local patcher="${PROJECT_DIR}/infra/scripts/microk8s/apply-vision-security-host-endpoints.sh"

    if [[ ! -x "${up_script}" ]]; then
        warn "Missing or non-executable ${up_script}; vision-security bridge skipped"
        return 1
    fi
    if [[ ! -x "${patcher}" ]]; then
        warn "Missing or non-executable ${patcher}; vision-security bridge skipped"
        return 1
    fi

    info "Starting workstation-local vision-security-service on host port 8094..."
    if ! "${up_script}"; then
        warn "vision-security host process failed to become healthy; bridge not wired"
        return 1
    fi

    info "Patching vision-security-service Endpoints with the host IP..."
    if ! "${patcher}" >/dev/null; then
        warn "Failed to patch vision-security-service Endpoints; bridge not wired"
        return 1
    fi

    VISION_SECURITY_BRIDGE_WIRED="true"
    info "vision-security workstation bridge is wired (Service -> host:8094)"
    return 0
}

apply_vision_security_gateway_env() {
    if [[ "${VISION_SECURITY_BRIDGE_WIRED}" == "true" ]]; then
        if ! kubectl set env deployment/api-gateway -n "${NAMESPACE}" \
            VISION_SECURITY_ENABLED=true VISION_SECURITY_LOCAL_BRIDGE=true \
            >/dev/null 2>&1; then
            warn "Failed to set api-gateway vision-security env vars"
            return 1
        fi
    else
        if ! kubectl set env deployment/api-gateway -n "${NAMESPACE}" \
            VISION_SECURITY_ENABLED=false VISION_SECURITY_LOCAL_BRIDGE=false \
            >/dev/null 2>&1; then
            warn "Failed to clear api-gateway vision-security env vars"
            return 1
        fi
    fi
    wait_workload deployment api-gateway 180s true "deployment/api-gateway not ready after vision-security env update"
}

preflight_check_port_conflicts() {
    local -a conflict_ports=(80 443)
    if is_truthy "${ENABLE_PORT_FORWARD}"; then
        conflict_ports+=(8080)
    fi
    local blocking_found="false"

    for port in "${conflict_ports[@]}"; do
        local listener_pid
        listener_pid="$(ss -tlnp "sport = :${port}" 2>/dev/null | awk 'NR>1 {print $0}' || true)"
        if [[ -z "${listener_pid}" ]]; then
            continue
        fi

        if echo "${listener_pid}" | grep -q "docker-proxy"; then
            blocking_found="true"
            warn "docker-proxy is listening on port ${port} — likely a stale Docker container with restart policy"
            warn "  Identified listener: $(echo "${listener_pid}" | head -1)"
            warn "  Fix: use your container runtime CLI to identify and remove the stale container publishing port ${port}"
        elif echo "${listener_pid}" | grep -q "traefik"; then
            warn "traefik is listening on port ${port} — will attempt to resolve via ensure_ingress_routing"
        elif echo "${listener_pid}" | grep -q "nginx"; then
            : # likely our ingress-nginx, expected
        else
            warn "Unknown process listening on port ${port}: $(echo "${listener_pid}" | head -1)"
        fi
    done

    if [[ "${blocking_found}" == "true" ]]; then
        fail "Stale Docker listener(s) detected on ingress ports. Remove them before launching Jarvis k8s stack."
    fi
}

# === Start ===
if [[ "${DRY_RUN}" == "true" ]]; then
    print_dry_run_summary
    exit 0
fi

if [[ "${USE_RELEASE_OVERLAY}" == "true" ]]; then
    ensure_release_overlay_supports_requested_features
fi

print_header

require_cmd kubectl
ensure_kubeconfig
ensure_cluster
ensure_k3s_network_alignment

info "Pre-flight: checking for port conflicts..."
preflight_check_port_conflicts

info "Ensuring ingress-nginx..."
ensure_ingress_nginx
ensure_ingress_routing

info "Ensuring namespace..."
ensure_namespace

mkdir -p "${JARVIS_MODELS_DIR}"

info "Ensuring secrets..."
ensure_secrets

info "Ensuring TLS..."
ensure_tls
apply_tls_secret

ensure_observability_stack
sync_grafana_admin_credentials
provision_grafana_dashboards

rotate_backend_log

if maybe_skip_redeploy; then
    exit 0
fi

if [[ "${ENABLE_LLM}" == "true" && "${ENABLE_GPU}" == "true" ]]; then
    if ! kubectl get nodes -o jsonpath='{.items[*].status.allocatable}' 2>/dev/null | grep -q 'nvidia.com/gpu'; then
        info "GPU not detected in cluster, attempting GPU setup..."
        if [[ -x "${PROJECT_DIR}/scripts/product/jarvis-gpu-setup.sh" ]]; then
            # Best-effort. Failure (nvidia-ctk missing, etc.) is logged but
            # does NOT abort the launch — llm-server runs on CPU when the
            # GGUF model is loaded with LLM_DEVICE=cpu / N_GPU_LAYERS=0.
            if ! run_privileged "${PROJECT_DIR}/scripts/product/jarvis-gpu-setup.sh"; then
                warn "GPU setup did not complete (likely no NVIDIA driver). Falling back to CPU LLM."
            fi
        else
            warn "GPU setup script not found: ${PROJECT_DIR}/scripts/product/jarvis-gpu-setup.sh"
        fi
    fi
    if ! kubectl get nodes -o jsonpath='{.items[*].status.allocatable}' 2>/dev/null | grep -q 'nvidia.com/gpu'; then
        warn "GPU not detected; running LLM on CPU (LLM_DEVICE=cpu, N_GPU_LAYERS=0)."
        ENABLE_GPU="false"
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

if [[ "${USE_RELEASE_OVERLAY}" == "true" ]]; then
    info "Applying digest-pinned release overlay..."
    apply_release_overlay
else
    build_images

    info "Applying core manifests..."
    if [ -d "${K8S_DIR}/overlays/prod" ]; then
        init_image_config
        OVERLAY_PATH="$(prepare_prod_overlay)"
        trap '[[ -n "${OVERLAY_PATH:-}" && -d "${OVERLAY_PATH}" ]] && rm -rf "${OVERLAY_PATH}"' EXIT
        MODELS_PATH="${JARVIS_MODELS_DIR}"
        MODELS_PATH_ENV_FILE="${OVERLAY_PATH}/models-path.env"
        printf "JARVIS_MODELS_PATH=%s\n" "${MODELS_PATH}" > "${MODELS_PATH_ENV_FILE}"
        kubectl kustomize --load-restrictor=LoadRestrictionsNone "${OVERLAY_PATH}" | \
            kubectl apply -f - >/dev/null
    else
        fail "Overlay not found: ${K8S_DIR}/overlays/prod"
    fi
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

# Keep the api-gateway capability flags in sync with the actual scale state so
# /api/v1/memory and /api/v1/llm degrade with FEATURE_DISABLED instead of
# bubbling Bad-Gateway/Service-Unavailable noise from a missing downstream pod.
if [[ "${ENABLE_MEMORY}" == "true" ]]; then
    kubectl set env deployment/api-gateway -n "${NAMESPACE}" \
        MEMORY_SERVICE_ENABLED=true SERVICES_MEMORY_ENABLED=true JARVIS_MEMORY_ENABLED=true \
        >/dev/null 2>&1 || true
else
    kubectl set env deployment/api-gateway -n "${NAMESPACE}" \
        MEMORY_SERVICE_ENABLED=false SERVICES_MEMORY_ENABLED=false JARVIS_MEMORY_ENABLED=false \
        >/dev/null 2>&1 || true
fi
if [[ "${ENABLE_LLM}" == "true" ]]; then
    kubectl set env deployment/api-gateway -n "${NAMESPACE}" \
        JARVIS_LLM_ENABLED=true SERVICES_LLM_ENABLED=true \
        >/dev/null 2>&1 || true
else
    kubectl set env deployment/api-gateway -n "${NAMESPACE}" \
        JARVIS_LLM_ENABLED=false SERVICES_LLM_ENABLED=false \
        >/dev/null 2>&1 || true
fi

force_mutable_image_refresh_policy
restart_mutable_app_workloads

# Wait for core services
info "Waiting for core services..."
wait_workload statefulset postgres 180s true "statefulset/postgres not ready"
wait_workload deployment security-service 180s true "deployment/security-service not ready"
wait_workload deployment api-gateway 180s true "deployment/api-gateway not ready"
wait_workload deployment orchestrator 180s true "deployment/orchestrator not ready"
wait_workload deployment life-tracker 180s true "deployment/life-tracker not ready"
wait_workload deployment analytics-service 180s true "deployment/analytics-service not ready"
wait_workload deployment planner-service 180s true "deployment/planner-service not ready"
wait_workload deployment user-profile 180s true "deployment/user-profile not ready"
wait_workload deployment nlp-service 180s true "deployment/nlp-service not ready"
wait_workload deployment pc-control 180s true "deployment/pc-control not ready"
wait_workload deployment smart-home-service 180s true "deployment/smart-home-service not ready"
wait_workload deployment sync-service 180s false "Optional deployment/sync-service not ready (image pull or crash; check pod logs)"
if is_truthy "${REQUIRE_VOICE_GATEWAY}"; then
    wait_workload deployment voice-gateway 180s true "Required deployment/voice-gateway not ready"
else
    wait_workload deployment voice-gateway 180s false "Optional deployment/voice-gateway not ready; backend is available in DEGRADED mode"
fi

# Optional waits — generous timeouts because llama.cpp warm-up loads a
# multi-GB GGUF model into memory before the readiness probe goes UP.
if [[ "${ENABLE_LLM}" == "true" ]]; then
    wait_workload deployment embedding-service 300s false "Optional deployment/embedding-service not ready"
    wait_workload deployment llm-server 900s false "Optional deployment/llm-server not ready"
    wait_workload deployment llm-service 360s false "Optional deployment/llm-service not ready"
fi
if [[ "${ENABLE_MEMORY}" == "true" ]]; then
    wait_workload statefulset postgres-pgvector 300s false "Optional statefulset/postgres-pgvector not ready"
    wait_workload deployment memory-service 300s false "Optional deployment/memory-service not ready"
fi

# Workstation-local vision-security host bridge. Brought up after the k8s
# core is ready so api-gateway exists when we flip its capability env vars.
if [[ "${ENABLE_VISION_SECURITY}" == "true" ]]; then
    if ensure_vision_security_local_bridge; then
        apply_vision_security_gateway_env || OPTIONAL_FAILED="true"
    else
        OPTIONAL_FAILED="true"
        warn "vision-security bridge not wired; api-gateway keeps VISION_SECURITY_ENABLED=false"
        ENABLE_VISION_SECURITY="false"
        apply_vision_security_gateway_env || true
    fi
else
    apply_vision_security_gateway_env || OPTIONAL_FAILED="true"
fi

# Endpoints
API_URL="https://api.jarvis.local"
VOICE_WS_URL="wss://voice.jarvis.local"
RUN_API_URL="${API_URL}"
RUN_VOICE_URL="${VOICE_WS_URL}"
RUN_GRAFANA_URL="https://grafana.jarvis.local"

verify_observability_stack

if [[ "${OPTIONAL_FAILED}" == "true" ]]; then
    RUN_STATUS="degraded"
    print_ready_status "DEGRADED"
else
    RUN_STATUS="ready"
    print_ready_status "READY"
fi
write_run_summary

if [[ "${ENABLE_PORT_FORWARD}" == "true" ]]; then
    info "Starting port-forward (debug)..."
    if ss -tlnp "sport = :8080" 2>/dev/null | grep -q '^LISTEN'; then
        warn "Port 8080 already in use; skipping port-forward for api-gateway"
    else
        kubectl port-forward svc/api-gateway 8080:8080 -n "${NAMESPACE}" >/dev/null 2>&1 &
        echo -e "  ${CYAN}[DEBUG]${NC} http://localhost:8080"
    fi
    if ss -tlnp "sport = :8081" 2>/dev/null | grep -q '^LISTEN'; then
        warn "Port 8081 already in use; skipping port-forward for voice-gateway"
    else
        kubectl port-forward svc/voice-gateway 8081:8081 -n "${NAMESPACE}" >/dev/null 2>&1 &
    fi
fi
