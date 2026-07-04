#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Validate Kubernetes Rollouts for the Backend Deployment Slice
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/scripts/lib/k8s-common.sh"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
OVERLAY_PATH=""
ROLLOUT_TIMEOUT="${JARVIS_ROLLOUT_TIMEOUT:-240s}"

REQUIRED_DEPLOYMENTS=(
  api-gateway
  alloy
  embedding-service
  grafana
  life-tracker
  loki
  memory-service
  nlp-service
  orchestrator
  pc-control
  planner-service
  prometheus
  security-service
  smart-home-service
  tempo
  user-profile
  voice-gateway
  analytics-service
)

REQUIRED_STATEFULSETS=(
  kafka
  postgres
  postgres-pgvector
  rabbitmq
)

OPTIONAL_DEPLOYMENTS=(
  llm-service
)

OPTIONAL_STATEFULSETS=(
)

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-rollout-validate.sh [options]

Options:
  --namespace=NAME   Kubernetes namespace, default: jarvis-prod
  --overlay=PATH     Render PATH and verify live workload image refs match the overlay
  --help, -h         Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
    --overlay=*)
      OVERLAY_PATH="${arg#*=}"
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
  jarvis_detect_kubeconfig
}

cluster_access_or_fail() {
  local err_file
  err_file="$(mktemp)"
  if kubectl cluster-info >/dev/null 2>"${err_file}"; then
    rm -f "${err_file}"
    return 0
  fi
  echo "❌ Kubernetes cluster is not reachable for rollout validation"
  sed 's/^/   /' "${err_file}"
  rm -f "${err_file}"
  exit 1
}

jsonpath_value() {
  local kind="$1"
  local name="$2"
  local path="$3"
  kubectl -n "${NAMESPACE}" get "${kind}" "${name}" -o "jsonpath=${path}" 2>/dev/null || true
}

workload_exists() {
  local kind="$1"
  local name="$2"
  kubectl -n "${NAMESPACE}" get "${kind}" "${name}" >/dev/null 2>&1
}

workload_ready() {
  local kind="$1"
  local name="$2"
  local desired=""
  local ready=""
  local available=""

  case "${kind}" in
    deployment)
      desired="$(jsonpath_value deployment "${name}" '{.spec.replicas}')"
      ready="$(jsonpath_value deployment "${name}" '{.status.readyReplicas}')"
      available="$(jsonpath_value deployment "${name}" '{.status.availableReplicas}')"
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

  [[ "${desired}" -eq 0 ]] && return 0
  [[ "${ready}" -ge "${desired}" && "${available}" -ge "${desired}" ]]
}

emit_diagnostics() {
  local kind="$1"
  local name="$2"

  echo "---- diagnostics: ${kind}/${name} ----"
  kubectl -n "${NAMESPACE}" get "${kind}" "${name}" -o wide || true
  kubectl -n "${NAMESPACE}" get pods -l "app=${name}" -o wide || true
  kubectl -n "${NAMESPACE}" get events --sort-by=.metadata.creationTimestamp | tail -n 20 || true
}

wait_for_workload() {
  local kind="$1"
  local name="$2"
  local required="$3"
  local allow_zero="${4:-false}"
  local desired=""

  if ! workload_exists "${kind}" "${name}"; then
    if [[ "${required}" == "true" ]]; then
      echo "❌ Missing required workload: ${kind}/${name}"
      return 1
    fi
    echo "ℹ️  Optional workload absent: ${kind}/${name}"
    return 0
  fi

  desired="$(jsonpath_value "${kind}" "${name}" '{.spec.replicas}')"
  if [[ "${allow_zero}" == "true" && "${desired}" == "0" ]]; then
    echo "ℹ️  Optional workload scaled to zero: ${kind}/${name}"
    return 0
  fi

  if kubectl -n "${NAMESPACE}" rollout status "${kind}/${name}" --timeout="${ROLLOUT_TIMEOUT}" >/dev/null 2>&1; then
    echo "✅ Ready: ${kind}/${name}"
    return 0
  fi

  if workload_ready "${kind}" "${name}"; then
    echo "✅ Ready: ${kind}/${name}"
    return 0
  fi

  echo "❌ Rollout not ready: ${kind}/${name}"
  emit_diagnostics "${kind}" "${name}"
  return 1
}

expected_image_from_render() {
  local kind="$1"
  local name="$2"
  local render_file="$3"

  awk -v kind="${kind}" -v name="${name}" '
    BEGIN { RS="---" }
    $0 ~ ("kind:[[:space:]]*" kind "([[:space:]]|$)") &&
    $0 ~ ("name:[[:space:]]*" name "([[:space:]]|$)") {
      n=split($0, lines, "\n")
      for (i=1; i<=n; i++) {
        if (lines[i] ~ /^[[:space:]]*image:[[:space:]]*/) {
          line=lines[i]
          sub(/^[[:space:]]*image:[[:space:]]*/, "", line)
          gsub(/["'\'']/, "", line)
          print line
          exit
        }
      }
    }
  ' "${render_file}"
}

live_image_for_workload() {
  local kind="$1"
  local name="$2"
  kubectl -n "${NAMESPACE}" get "${kind}" "${name}" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true
}

verify_images_match_overlay() {
  local render_file
  local kind
  local name
  local expected
  local live

  if [[ -z "${OVERLAY_PATH}" ]]; then
    return 0
  fi
  if [[ ! -f "${OVERLAY_PATH}/kustomization.yaml" ]]; then
    echo "❌ Overlay does not contain kustomization.yaml: ${OVERLAY_PATH}" >&2
    exit 1
  fi

  render_file="$(mktemp)"
  kubectl kustomize "${OVERLAY_PATH}" > "${render_file}"

  for name in "${REQUIRED_DEPLOYMENTS[@]}" "${OPTIONAL_DEPLOYMENTS[@]}"; do
    kind="Deployment"
    expected="$(expected_image_from_render "${kind}" "${name}" "${render_file}")"
    [[ -z "${expected}" ]] && continue
    if workload_exists deployment "${name}"; then
      live="$(live_image_for_workload deployment "${name}")"
      if [[ -n "${live}" && "${live}" != "${expected}" ]]; then
        rm -f "${render_file}"
        echo "❌ Live deployment image drift for deployment/${name}"
        echo "   expected: ${expected}"
        echo "   live:     ${live}"
        exit 1
      fi
    fi
  done

  for name in "${REQUIRED_STATEFULSETS[@]}" "${OPTIONAL_STATEFULSETS[@]}"; do
    kind="StatefulSet"
    expected="$(expected_image_from_render "${kind}" "${name}" "${render_file}")"
    [[ -z "${expected}" ]] && continue
    if workload_exists statefulset "${name}"; then
      live="$(live_image_for_workload statefulset "${name}")"
      if [[ -n "${live}" && "${live}" != "${expected}" ]]; then
        rm -f "${render_file}"
        echo "❌ Live statefulset image drift for statefulset/${name}"
        echo "   expected: ${expected}"
        echo "   live:     ${live}"
        exit 1
      fi
    fi
  done

  rm -f "${render_file}"
  echo "✅ Live workload images match overlay render"
}

main() {
  jarvis_require_kubectl >/dev/null || exit 1
  detect_kubeconfig
  cluster_access_or_fail

  verify_images_match_overlay

  local failed=0
  local name

  for name in "${REQUIRED_STATEFULSETS[@]}"; do
    wait_for_workload statefulset "${name}" true false || failed=1
  done
  for name in "${REQUIRED_DEPLOYMENTS[@]}"; do
    wait_for_workload deployment "${name}" true false || failed=1
  done
  for name in "${OPTIONAL_STATEFULSETS[@]}"; do
    wait_for_workload statefulset "${name}" false true || failed=1
  done
  for name in "${OPTIONAL_DEPLOYMENTS[@]}"; do
    wait_for_workload deployment "${name}" false true || failed=1
  done

  if [[ "${failed}" -ne 0 ]]; then
    echo "❌ Rollout validation failed"
    exit 1
  fi

  echo "✅ Rollout validation passed"
}

main
