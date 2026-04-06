#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Deploy Digest-Pinned Backend Release Overlay
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${PROJECT_ROOT}/k8s/overlays/prod-release"
NAMESPACE="jarvis"
PREFLIGHT_ONLY=false

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-deploy-prod.sh [options]

Options:
  --overlay=PATH      Release overlay path, default: k8s/overlays/prod-release
  --namespace=NAME    Kubernetes namespace, default: jarvis
  --preflight-only    Run server-side preflight but skip apply + rollout validation
  --help, -h          Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --overlay=*)
      OVERLAY_PATH="${arg#*=}"
      ;;
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
    --preflight-only)
      PREFLIGHT_ONLY=true
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

main() {
  require_cmd kubectl
  detect_kubeconfig

  if [[ ! -f "${OVERLAY_PATH}/kustomization.yaml" ]]; then
    echo "❌ Missing release overlay: ${OVERLAY_PATH}/kustomization.yaml" >&2
    echo "   Generate it first with ./scripts/product/jarvis-promote-images.sh" >&2
    exit 1
  fi

  echo "🔎 Running server-side preflight against ${OVERLAY_PATH}"
  K8S_PREFLIGHT_MODE=server \
  K8S_PREFLIGHT_NAMESPACE="${NAMESPACE}" \
  K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE=enforce \
    "${PROJECT_ROOT}/scripts/ci/k8s-preflight.sh" "${OVERLAY_PATH}"

  if [[ "${PREFLIGHT_ONLY}" == "true" ]]; then
    echo "✅ Preflight-only mode complete"
    exit 0
  fi

  echo "🚀 Applying ${OVERLAY_PATH}"
  kubectl apply -k "${OVERLAY_PATH}"

  echo "🔎 Validating rollout"
  "${PROJECT_ROOT}/scripts/product/jarvis-rollout-validate.sh" \
    --namespace="${NAMESPACE}" \
    --overlay="${OVERLAY_PATH}"
}

main
