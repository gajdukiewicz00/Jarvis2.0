#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${PROJECT_ROOT}/k8s/overlays/prod-release-internal-tls-api-gateway-life-tracker"
NAMESPACE="jarvis"
SECRET_NAME="jarvis-internal-tls-api-gateway-life-tracker"
REQUIRED_SECRET_KEYS=(
  life-tracker-keystore.p12
  ca.crt
  keystore-password
)

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
  esac
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

detect_kubeconfig() {
  if [[ -n "${KUBECONFIG:-}" ]]; then
    return 0
  fi
  if [[ -r "${HOME}/.jarvis/kubeconfig" ]]; then
    export KUBECONFIG="${HOME}/.jarvis/kubeconfig"
    return 0
  fi
  if [[ -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
  fi
}

require_secret_key() {
  local key="$1"
  local value
  value="$(kubectl -n "${NAMESPACE}" get secret "${SECRET_NAME}" -o "jsonpath={.data.${key//./\\.}}" 2>/dev/null || true)"
  if [[ -z "${value}" ]]; then
    echo "❌ Secret '${SECRET_NAME}' is missing key '${key}' in namespace '${NAMESPACE}'" >&2
    echo "   Refresh it with ./scripts/product/jarvis-apply-internal-tls-api-gateway-life-tracker.sh" >&2
    exit 1
  fi
}

require_cmd kubectl
detect_kubeconfig

if ! kubectl -n "${NAMESPACE}" get secret "${SECRET_NAME}" >/dev/null 2>&1; then
  echo "❌ Missing internal TLS secret '${SECRET_NAME}' in namespace '${NAMESPACE}'" >&2
  echo "   Create it with ./scripts/product/jarvis-apply-internal-tls-api-gateway-life-tracker.sh" >&2
  exit 1
fi

for key in "${REQUIRED_SECRET_KEYS[@]}"; do
  require_secret_key "${key}"
done

export K8S_PREFLIGHT_REQUIRED_SECRETS="${K8S_PREFLIGHT_REQUIRED_SECRETS:-jarvis-secrets,jarvis-tls,jarvis-internal-tls-api-gateway-nlp,jarvis-internal-tls-planner-api-gateway,jarvis-internal-tls-orchestrator-api-gateway,jarvis-internal-tls-voice-gateway-api-gateway,jarvis-internal-tls-api-gateway-security-service,jarvis-internal-tls-api-gateway-analytics-service,jarvis-internal-tls-api-gateway-pc-control,jarvis-internal-tls-api-gateway-life-tracker}"

"${PROJECT_ROOT}/scripts/product/jarvis-deploy-prod.sh" --overlay="${OVERLAY_PATH}" "$@"
