#!/usr/bin/env bash
# Validate k8s manifests before deploy:
# 1) kustomize render + server-side dry-run apply
# 2) fail if any image tag uses :latest

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${1:-${PROJECT_ROOT}/k8s/overlays/prod}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

require_cmd kustomize
require_cmd kubectl
require_cmd grep

echo "🔎 Checking for forbidden ':latest' image tags in k8s manifests..."
if grep -RIn --include="*.yaml" --include="*.yml" "image:.*:latest" "${PROJECT_ROOT}/k8s"; then
  echo "❌ Found ':latest' image tags in k8s manifests"
  exit 1
fi

echo "🧪 Running k8s preflight server dry-run..."
kustomize build "${OVERLAY_PATH}" | kubectl apply --dry-run=server -f -

echo "✅ k8s preflight passed"
