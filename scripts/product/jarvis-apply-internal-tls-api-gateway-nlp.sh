#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
NAMESPACE="jarvis"
SECRET_NAME="jarvis-internal-tls-api-gateway-nlp"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh [--namespace=jarvis]

Creates or updates the Kubernetes secret that backs the first internal TLS slice:
  api-gateway (HTTPS client) -> nlp-service (HTTPS server)
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "❌ Unknown argument: ${arg}" >&2
      usage >&2
      exit 1
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

JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
SLICE_DIR="${JARVIS_HOME}/tls/internal/api-gateway-nlp"
SERVER_KEYSTORE="${SLICE_DIR}/nlp-service-keystore.p12"
TRUSTSTORE="${SLICE_DIR}/jarvis-internal-truststore.jks"
CA_CERT="${SLICE_DIR}/ca.crt"
KEYSTORE_PASSWORD="${JARVIS_INTERNAL_TLS_KEYSTORE_PASSWORD:-changeit}"
TRUSTSTORE_PASSWORD="${JARVIS_INTERNAL_TLS_TRUSTSTORE_PASSWORD:-changeit}"

if [[ ! -f "${SERVER_KEYSTORE}" || ! -f "${TRUSTSTORE}" || ! -f "${CA_CERT}" ]]; then
  "${PROJECT_ROOT}/scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh"
fi

kubectl get namespace "${NAMESPACE}" >/dev/null

kubectl -n "${NAMESPACE}" create secret generic "${SECRET_NAME}" \
  --from-file=nlp-service-keystore.p12="${SERVER_KEYSTORE}" \
  --from-file=truststore.jks="${TRUSTSTORE}" \
  --from-file=ca.crt="${CA_CERT}" \
  --from-literal=keystore-password="${KEYSTORE_PASSWORD}" \
  --from-literal=truststore-password="${TRUSTSTORE_PASSWORD}" \
  --dry-run=client \
  -o yaml | kubectl apply -f -

echo "✅ Applied secret ${SECRET_NAME} in namespace ${NAMESPACE}"
