#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${PROJECT_ROOT}/k8s/overlays/prod-release-internal-tls-verified"
NAMESPACE="jarvis"

SMOKE_SCRIPTS=(
  jarvis-smoke-internal-tls-ingress-api-gateway.sh
  jarvis-smoke-internal-tls-api-gateway-nlp.sh
  jarvis-smoke-internal-tls-planner-api-gateway.sh
  jarvis-smoke-internal-tls-orchestrator-api-gateway.sh
  jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh
  jarvis-smoke-internal-tls-api-gateway-security-service.sh
  jarvis-smoke-internal-tls-api-gateway-analytics-service.sh
  jarvis-smoke-internal-tls-api-gateway-pc-control.sh
  jarvis-smoke-internal-tls-api-gateway-life-tracker.sh
  jarvis-smoke-internal-tls-api-gateway-smart-home-service.sh
  jarvis-smoke-internal-tls-api-gateway-orchestrator.sh
  jarvis-smoke-internal-tls-api-gateway-voice-gateway.sh
  jarvis-smoke-internal-tls-voice-gateway-orchestrator.sh
  jarvis-smoke-internal-tls-analytics-service-life-tracker.sh
  jarvis-smoke-internal-tls-orchestrator-nlp-service.sh
  jarvis-smoke-internal-tls-planner-service-analytics-service.sh
  jarvis-smoke-internal-tls-api-gateway-planner-service.sh
  jarvis-smoke-internal-tls-planner-service-voice-gateway.sh
  jarvis-smoke-internal-tls-voice-gateway-smart-home-service.sh
  jarvis-smoke-internal-tls-orchestrator-smart-home-service.sh
  jarvis-smoke-internal-tls-orchestrator-pc-control.sh
)

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-prod-internal-tls-verified.sh [--namespace=jarvis]

Validates the promoted internal-TLS release path by:
  1. running rollout validation against the promoted overlay
  2. replaying the twenty-one verified internal HTTPS hop smokes serially
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

"${PROJECT_ROOT}/scripts/product/jarvis-rollout-validate.sh" \
  --namespace="${NAMESPACE}" \
  --overlay="${OVERLAY_PATH}"

for smoke_script in "${SMOKE_SCRIPTS[@]}"; do
  echo "==> ${smoke_script}"
  "${PROJECT_ROOT}/scripts/product/${smoke_script}" --namespace="${NAMESPACE}"
done

echo "✅ Promoted internal TLS release path validated"
