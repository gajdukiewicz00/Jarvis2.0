#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/scripts/lib/k8s-common.sh"

OVERLAY_PATH="${PROJECT_ROOT}/infra/k8s/overlays/prod"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
RENDER_ONLY=false
SKIP_SECRETS=false
SKIP_TLS_SECRET=false
SKIP_SMOKE=false

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-deploy-microk8s-prod.sh [options]

Options:
  --overlay=PATH       Kustomize overlay path, default: infra/k8s/overlays/prod
  --namespace=NAME     Kubernetes namespace, default: jarvis-prod
  --render-only        Render and preflight only; skip live cluster apply
  --skip-secrets       Skip ./scripts/product/jarvis-secrets-apply.sh
  --skip-tls-secret    Skip ./scripts/product/jarvis-apply-edge-tls-secret.sh
  --skip-smoke         Skip ./scripts/k8s-smoke.sh after rollout validation
  --help, -h           Show this help
EOF
}

detect_ingress_conflicts() {
  local conflicts

  conflicts="$(
    kubectl get ingress -A \
      -o jsonpath='{range .items[*]}{.metadata.namespace}{"\t"}{.metadata.name}{"\t"}{range .spec.rules[*]}{.host}{" "}{end}{"\n"}{end}' \
      | awk -v target_ns="${NAMESPACE}" '$1 != target_ns && ($0 ~ /api\.jarvis\.local/ || $0 ~ /voice\.jarvis\.local/ || $0 ~ /grafana\.jarvis\.local/) { print }'
  )"

  if [[ -z "${conflicts}" ]]; then
    return 0
  fi

  echo "❌ Conflicting ingress hosts already exist outside namespace '${NAMESPACE}':" >&2
  printf '%s\n' "${conflicts}" >&2
  echo "   Remove or retarget the legacy ingress before applying jarvis-prod." >&2
  return 1
}

for arg in "$@"; do
  case "${arg}" in
    --overlay=*)
      OVERLAY_PATH="${arg#*=}"
      ;;
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
    --render-only)
      RENDER_ONLY=true
      ;;
    --skip-secrets)
      SKIP_SECRETS=true
      ;;
    --skip-tls-secret)
      SKIP_TLS_SECRET=true
      ;;
    --skip-smoke)
      SKIP_SMOKE=true
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

[[ -f "${OVERLAY_PATH}/kustomization.yaml" ]] || {
  echo "❌ Missing overlay: ${OVERLAY_PATH}/kustomization.yaml" >&2
  exit 1
}

echo "Using Kubernetes CLI: $(jarvis_kubectl_description)"
echo "Overlay: ${OVERLAY_PATH}"
echo "Namespace: ${NAMESPACE}"

echo "🧾 Rendering ${OVERLAY_PATH}"
kubectl kustomize "${OVERLAY_PATH}" >/tmp/jarvis-microk8s-prod-render.yaml

echo "🔎 Running manifest preflight"
K8S_PREFLIGHT_MODE=client \
K8S_PREFLIGHT_NAMESPACE="${NAMESPACE}" \
K8S_PREFLIGHT_CORE_MIN_REPLICAS=1 \
K8S_PREFLIGHT_OPTIONAL_WORKLOADS=llm-service \
K8S_PREFLIGHT_RENDER_OUTPUT=/tmp/jarvis-microk8s-prod-render.yaml \
  "${PROJECT_ROOT}/scripts/ci/k8s-preflight.sh" "${OVERLAY_PATH}"

if [[ "${RENDER_ONLY}" == "true" ]]; then
  echo "✅ Render-only validation complete"
  exit 0
fi

if ! jarvis_cluster_reachable; then
  echo "❌ Kubernetes cluster is not reachable for live apply." >&2
  echo "   Static render completed successfully. Re-run once MicroK8s is available," >&2
  echo "   or use --render-only for offline validation." >&2
  exit 1
fi

if ! kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1; then
  kubectl create namespace "${NAMESPACE}" >/dev/null
fi

detect_ingress_conflicts || exit 1

if [[ "${SKIP_SECRETS}" != "true" ]]; then
  JARVIS_NAMESPACE="${NAMESPACE}" "${PROJECT_ROOT}/scripts/product/jarvis-secrets-apply.sh"
fi

if [[ "${SKIP_TLS_SECRET}" != "true" ]]; then
  JARVIS_NAMESPACE="${NAMESPACE}" "${PROJECT_ROOT}/scripts/product/jarvis-apply-edge-tls-secret.sh"
fi

echo "🔎 Running server-side preflight"
K8S_PREFLIGHT_MODE=server \
K8S_PREFLIGHT_NAMESPACE="${NAMESPACE}" \
K8S_PREFLIGHT_CORE_MIN_REPLICAS=1 \
K8S_PREFLIGHT_OPTIONAL_WORKLOADS=llm-service \
  "${PROJECT_ROOT}/scripts/ci/k8s-preflight.sh" "${OVERLAY_PATH}"

echo "🚀 Applying ${OVERLAY_PATH}"
kubectl apply -k "${OVERLAY_PATH}"

echo "🔗 Patching host-model-daemon Endpoints"
JARVIS_NAMESPACE="${NAMESPACE}" \
  "${PROJECT_ROOT}/infra/scripts/microk8s/apply-host-endpoints.sh" \
  --namespace="${NAMESPACE}"

echo "🧱 Bootstrapping PostgreSQL databases"
JARVIS_NAMESPACE="${NAMESPACE}" \
  "${PROJECT_ROOT}/scripts/product/jarvis-bootstrap-postgres.sh" \
  --namespace="${NAMESPACE}"

echo "🔎 Validating rollout"
JARVIS_NAMESPACE="${NAMESPACE}" \
  "${PROJECT_ROOT}/scripts/product/jarvis-rollout-validate.sh" \
  --namespace="${NAMESPACE}" \
  --overlay="${OVERLAY_PATH}"

if [[ "${SKIP_SMOKE}" != "true" ]]; then
  echo "🧪 Running production smoke"
  JARVIS_NAMESPACE="${NAMESPACE}" "${PROJECT_ROOT}/scripts/k8s-smoke.sh" --namespace="${NAMESPACE}"
fi

echo "✅ Jarvis local production foundation is applied in namespace '${NAMESPACE}'"
