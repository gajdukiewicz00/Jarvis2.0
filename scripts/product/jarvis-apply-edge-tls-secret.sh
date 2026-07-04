#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/scripts/lib/k8s-common.sh"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
SECRET_NAME="${JARVIS_TLS_SECRET_NAME:-jarvis-tls}"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
TLS_DIR="${JARVIS_HOME}/tls"
TLS_CERT="${JARVIS_TLS_CERT_FILE:-${TLS_DIR}/jarvis.crt}"
TLS_KEY="${JARVIS_TLS_KEY_FILE:-${TLS_DIR}/jarvis.key}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-apply-edge-tls-secret.sh [options]

Options:
  --namespace=NAME  Kubernetes namespace, default: jarvis-prod
  --cert=PATH       TLS certificate path, default: ~/.jarvis/tls/jarvis.crt
  --key=PATH        TLS private key path, default: ~/.jarvis/tls/jarvis.key
  --help, -h        Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
    --cert=*)
      TLS_CERT="${arg#*=}"
      ;;
    --key=*)
      TLS_KEY="${arg#*=}"
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

jarvis_require_kubectl >/dev/null

[[ -r "${TLS_CERT}" ]] || {
  echo "❌ Missing TLS certificate: ${TLS_CERT}" >&2
  echo "   Generate local certs first: ${PROJECT_ROOT}/scripts/product/jarvis-generate-certs.sh" >&2
  exit 1
}

[[ -r "${TLS_KEY}" ]] || {
  echo "❌ Missing TLS private key: ${TLS_KEY}" >&2
  echo "   Generate local certs first: ${PROJECT_ROOT}/scripts/product/jarvis-generate-certs.sh" >&2
  exit 1
}

echo "Using Kubernetes CLI: $(jarvis_kubectl_description)"
echo "Applying TLS secret '${SECRET_NAME}' in namespace '${NAMESPACE}'"

if ! kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1; then
  kubectl create namespace "${NAMESPACE}" >/dev/null
fi

kubectl create secret tls "${SECRET_NAME}" \
  --cert="${TLS_CERT}" \
  --key="${TLS_KEY}" \
  -n "${NAMESPACE}" \
  --dry-run=client \
  -o yaml | kubectl apply -f - >/dev/null

kubectl -n "${NAMESPACE}" get secret "${SECRET_NAME}" >/dev/null
echo "✅ TLS secret '${SECRET_NAME}' is present in namespace '${NAMESPACE}'"
