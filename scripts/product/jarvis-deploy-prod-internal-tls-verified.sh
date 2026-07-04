#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${PROJECT_ROOT}/k8s/overlays/prod-release-internal-tls-verified"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"

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

require_cmd kubectl
detect_kubeconfig

for secret_name in "${REQUIRED_SECRETS[@]}"; do
  if ! kubectl -n "${NAMESPACE}" get secret "${secret_name}" >/dev/null 2>&1; then
    echo "❌ Missing required secret '${secret_name}' in namespace '${NAMESPACE}'" >&2
    exit 1
  fi
done

secret_csv="$(IFS=,; echo "${REQUIRED_SECRETS[*]}")"
export K8S_PREFLIGHT_REQUIRED_SECRETS="${K8S_PREFLIGHT_REQUIRED_SECRETS:-${secret_csv}}"

"${PROJECT_ROOT}/scripts/product/jarvis-deploy-prod.sh" --overlay="${OVERLAY_PATH}" "$@"
