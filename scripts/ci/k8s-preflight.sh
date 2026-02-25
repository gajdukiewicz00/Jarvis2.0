#!/usr/bin/env bash
# Validate k8s manifests before deploy:
# 1) kustomize render + server-side dry-run apply
# 2) fail if any image tag uses :latest
# 3) enforce MQTT auth hardening checks

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${1:-${PROJECT_ROOT}/k8s/overlays/prod}"
PREFLIGHT_MODE="${K8S_PREFLIGHT_MODE:-server}"
TOOLCHAIN_IMAGE="${K8S_PREFLIGHT_TOOLCHAIN_IMAGE:-bitnami/kubectl:1.30}"
K8S_NAMESPACE="${K8S_PREFLIGHT_NAMESPACE:-jarvis}"
REQUIRED_SECRETS_RAW="${K8S_PREFLIGHT_REQUIRED_SECRETS:-jarvis-secrets}"
IFS=',' read -r -a REQUIRED_SECRETS <<< "${REQUIRED_SECRETS_RAW}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

require_cmd grep

echo "🔎 Checking for forbidden ':latest' image tags in k8s manifests..."
if grep -RIn --include="*.yaml" --include="*.yml" "image:.*:latest" "${PROJECT_ROOT}/k8s"; then
  echo "❌ Found ':latest' image tags in k8s manifests"
  exit 1
fi

if [[ -x "${PROJECT_ROOT}/scripts/ci/check-mqtt-hardening.sh" ]]; then
  "${PROJECT_ROOT}/scripts/ci/check-mqtt-hardening.sh"
fi

run_local_preflight() {
  local dry_run_flag="--dry-run=${PREFLIGHT_MODE}"
  ensure_required_secrets_exist_local
  echo "🧪 Running k8s preflight (${PREFLIGHT_MODE} dry-run)..."
  kustomize build "${OVERLAY_PATH}" | kubectl apply "${dry_run_flag}" -f -
}

ensure_required_secrets_exist_local() {
  if [[ "${#REQUIRED_SECRETS[@]}" -eq 0 ]]; then
    return
  fi
  echo "🔐 Verifying required secrets in namespace '${K8S_NAMESPACE}'..."
  for secret_name in "${REQUIRED_SECRETS[@]}"; do
    if ! kubectl -n "${K8S_NAMESPACE}" get secret "${secret_name}" >/dev/null 2>&1; then
      echo "❌ Required secret '${secret_name}' is missing in namespace '${K8S_NAMESPACE}'"
      echo "   Apply secrets first: ./scripts/product/jarvis-secrets-apply.sh"
      exit 1
    fi
  done
}

run_container_preflight() {
  require_cmd docker
  local dry_run_flag="--dry-run=${PREFLIGHT_MODE}"
  local secrets_csv="${REQUIRED_SECRETS_RAW}"
  echo "🧪 Running k8s preflight in container (${TOOLCHAIN_IMAGE})..."
  docker run --rm \
    -v "${PROJECT_ROOT}:${PROJECT_ROOT}" \
    -v "${HOME}/.kube:/root/.kube:ro" \
    -w "${PROJECT_ROOT}" \
    -e "K8S_NAMESPACE=${K8S_NAMESPACE}" \
    -e "REQUIRED_SECRETS=${secrets_csv}" \
    "${TOOLCHAIN_IMAGE}" \
    sh -ec '
      IFS="," read -r -a secrets <<< "${REQUIRED_SECRETS}"
      for secret_name in "${secrets[@]}"; do
        if [ -n "${secret_name}" ] && ! kubectl -n "${K8S_NAMESPACE}" get secret "${secret_name}" >/dev/null 2>&1; then
          echo "❌ Required secret '\''${secret_name}'\'' is missing in namespace '\''${K8S_NAMESPACE}'\''"
          exit 1
        fi
      done
      kubectl kustomize --load-restrictor=LoadRestrictionsNone "'"${OVERLAY_PATH}"'" | kubectl apply '"${dry_run_flag}"' -f -
    '
}

if command -v kustomize >/dev/null 2>&1 && command -v kubectl >/dev/null 2>&1; then
  run_local_preflight
else
  if [[ -n "${CI:-}" ]] || [[ "${K8S_PREFLIGHT_FORCE_CONTAINER:-false}" == "true" ]]; then
    run_container_preflight
  else
    echo "⚠️  Skipping k8s preflight locally: missing kustomize/kubectl toolchain."
    echo "   Install dependencies or run with K8S_PREFLIGHT_FORCE_CONTAINER=true."
    exit 0
  fi
fi

echo "✅ k8s preflight passed"
