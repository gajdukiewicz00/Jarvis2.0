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
  echo "🧪 Running k8s preflight (${PREFLIGHT_MODE} dry-run)..."
  kustomize build "${OVERLAY_PATH}" | kubectl apply "${dry_run_flag}" -f -
}

run_container_preflight() {
  require_cmd docker
  local dry_run_flag="--dry-run=${PREFLIGHT_MODE}"
  echo "🧪 Running k8s preflight in container (${TOOLCHAIN_IMAGE})..."
  docker run --rm \
    -v "${PROJECT_ROOT}:${PROJECT_ROOT}" \
    -v "${HOME}/.kube:/root/.kube:ro" \
    -w "${PROJECT_ROOT}" \
    "${TOOLCHAIN_IMAGE}" \
    sh -ec "kubectl kustomize --load-restrictor=LoadRestrictionsNone '${OVERLAY_PATH}' | kubectl apply ${dry_run_flag} -f -"
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
