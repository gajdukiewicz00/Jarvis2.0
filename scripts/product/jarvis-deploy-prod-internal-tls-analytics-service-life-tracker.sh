#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${PROJECT_ROOT}/k8s/overlays/prod-release-internal-tls-analytics-service-life-tracker"
NAMESPACE="jarvis"
SECRET_NAME="jarvis-internal-tls-analytics-service-life-tracker"

REQUIRED_SECRETS=(
  jarvis-secrets
  jarvis-tls
  jarvis-internal-tls-api-gateway-nlp
  jarvis-internal-tls-planner-api-gateway
  jarvis-internal-tls-orchestrator-api-gateway
  jarvis-internal-tls-voice-gateway-api-gateway
  jarvis-internal-tls-api-gateway-security-service
  jarvis-internal-tls-api-gateway-analytics-service
  jarvis-internal-tls-api-gateway-pc-control
  jarvis-internal-tls-api-gateway-life-tracker
  jarvis-internal-tls-api-gateway-smart-home-service
  jarvis-internal-tls-api-gateway-orchestrator
  jarvis-internal-tls-api-gateway-voice-gateway
  jarvis-internal-tls-voice-gateway-orchestrator
  jarvis-internal-tls-analytics-service-life-tracker
)

REQUIRED_SECRET_KEYS=(
  ca.crt
  truststore.jks
  truststore-password
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
    echo "   Refresh it with ./scripts/product/jarvis-apply-internal-tls-analytics-service-life-tracker.sh" >&2
    exit 1
  fi
}

require_cmd kubectl
detect_kubeconfig

for secret_name in "${REQUIRED_SECRETS[@]}"; do
  if ! kubectl -n "${NAMESPACE}" get secret "${secret_name}" >/dev/null 2>&1; then
    echo "❌ Missing required secret '${secret_name}' in namespace '${NAMESPACE}'" >&2
    exit 1
  fi
done

for key in "${REQUIRED_SECRET_KEYS[@]}"; do
  require_secret_key "${key}"
done

secret_csv="$(IFS=,; echo "${REQUIRED_SECRETS[*]}")"
export K8S_PREFLIGHT_REQUIRED_SECRETS="${K8S_PREFLIGHT_REQUIRED_SECRETS:-${secret_csv}}"

"${PROJECT_ROOT}/scripts/product/jarvis-deploy-prod.sh" --overlay="${OVERLAY_PATH}" "$@"
