#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${PROJECT_ROOT}/k8s/overlays/prod-release-internal-tls-ingress-api-gateway"
INGRESS_NAMESPACE="${JARVIS_INGRESS_NAMESPACE:-ingress-nginx}"
INGRESS_SERVICE="${JARVIS_INGRESS_SERVICE:-ingress-nginx-controller}"

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

if ! kubectl -n "${INGRESS_NAMESPACE}" get service "${INGRESS_SERVICE}" >/dev/null 2>&1; then
  echo "❌ Missing ingress controller service '${INGRESS_SERVICE}' in namespace '${INGRESS_NAMESPACE}'" >&2
  exit 1
fi

export K8S_PREFLIGHT_REQUIRED_SECRETS="${K8S_PREFLIGHT_REQUIRED_SECRETS:-jarvis-secrets,jarvis-tls,jarvis-internal-tls-api-gateway-nlp,jarvis-internal-tls-planner-api-gateway,jarvis-internal-tls-orchestrator-api-gateway,jarvis-internal-tls-voice-gateway-api-gateway}"

"${PROJECT_ROOT}/scripts/product/jarvis-deploy-prod.sh" --overlay="${OVERLAY_PATH}" "$@"
