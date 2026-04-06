#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

CORE_BACKEND_SERVICES=(
  api-gateway
  security-service
  user-profile
  nlp-service
  orchestrator
  voice-gateway
  smart-home-service
  life-tracker
  analytics-service
  planner-service
  pc-control
)

CORE_SIGNED_SERVICES=(
  api-gateway
  security-service
  orchestrator
  voice-gateway
  life-tracker
  planner-service
)

fail() {
  echo "❌ $*" >&2
  exit 1
}

require_executable() {
  local path="$1"
  [[ -x "${ROOT_DIR}/${path}" ]] || fail "Expected executable script: ${path}"
}

digest_for_index() {
  local index="$1"
  printf '%064x' "${index}"
}

main() {
  local refs_file="${TMP_DIR}/backend-image-refs.env"
  local overlay_dir="${TMP_DIR}/prod-release"
  local service
  local idx=1
  local sign_log="${TMP_DIR}/cosign.log"
  local env_digest_refs=()

  require_executable "scripts/product/jarvis-promote-images.sh"
  require_executable "scripts/product/jarvis-deploy-prod.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-nlp.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-planner-api-gateway.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-planner-api-gateway.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-api-gateway.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-orchestrator-api-gateway.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-orchestrator-api-gateway.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-api-gateway.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-voice-gateway-api-gateway.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-voice-gateway-api-gateway.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-ingress-api-gateway.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-security-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-security-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-security-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-analytics-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-analytics-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-analytics-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-analytics-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-pc-control.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-pc-control.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-pc-control.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-pc-control.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-life-tracker.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-life-tracker.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-life-tracker.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-life-tracker.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-voice-gateway.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-voice-gateway.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-voice-gateway.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-voice-gateway.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-voice-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-voice-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-voice-gateway-orchestrator.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-analytics-service-life-tracker.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-analytics-service-life-tracker.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-analytics-service-life-tracker.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-analytics-service-life-tracker.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-nlp-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-orchestrator-nlp-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-orchestrator-nlp-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-orchestrator-nlp-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-planner-service-analytics-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-planner-service-analytics-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-planner-service-analytics-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-planner-service-analytics-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-planner-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-api-gateway-planner-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-api-gateway-planner-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-api-gateway-planner-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-planner-service-voice-gateway.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-planner-service-voice-gateway.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-planner-service-voice-gateway.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-planner-service-voice-gateway.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-voice-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-voice-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-voice-gateway-smart-home-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-smart-home-service.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-orchestrator-smart-home-service.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-orchestrator-smart-home-service.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-orchestrator-smart-home-service.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-pc-control.sh"
  require_executable "scripts/product/jarvis-generate-internal-tls-orchestrator-pc-control.sh"
  require_executable "scripts/product/jarvis-apply-internal-tls-orchestrator-pc-control.sh"
  require_executable "scripts/product/jarvis-smoke-internal-tls-orchestrator-pc-control.sh"
  require_executable "scripts/product/jarvis-deploy-prod-internal-tls-verified.sh"
  require_executable "scripts/product/jarvis-smoke-prod-internal-tls-verified.sh"
  require_executable "scripts/product/jarvis-rollout-validate.sh"
  require_executable "scripts/ci/k8s-preflight.sh"
  require_executable "scripts/ci/cosign-sign-core-images.sh"
  require_executable "scripts/runtime-smoke.sh"
  require_executable "scripts/analytics-smoke.sh"

  bash -n \
    "${ROOT_DIR}/scripts/product/jarvis-promote-images.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-nlp.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-planner-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-planner-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-orchestrator-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-orchestrator-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-voice-gateway-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-voice-gateway-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-ingress-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-security-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-security-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-security-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-voice-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-voice-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-voice-gateway-orchestrator.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-analytics-service-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-analytics-service-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-analytics-service-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-analytics-service-life-tracker.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-nlp-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-orchestrator-nlp-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-orchestrator-nlp-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-orchestrator-nlp-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-planner-service-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-planner-service-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-planner-service-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-planner-service-analytics-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-planner-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-api-gateway-planner-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-api-gateway-planner-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-api-gateway-planner-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-planner-service-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-planner-service-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-planner-service-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-planner-service-voice-gateway.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-voice-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-voice-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-voice-gateway-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-orchestrator-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-orchestrator-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-orchestrator-smart-home-service.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-generate-internal-tls-orchestrator-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-apply-internal-tls-orchestrator-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-internal-tls-orchestrator-pc-control.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-deploy-prod-internal-tls-verified.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-smoke-prod-internal-tls-verified.sh" \
    "${ROOT_DIR}/scripts/product/jarvis-rollout-validate.sh" \
    "${ROOT_DIR}/scripts/ci/k8s-preflight.sh" \
    "${ROOT_DIR}/scripts/ci/cosign-sign-core-images.sh" \
    "${ROOT_DIR}/scripts/runtime-smoke.sh" \
    "${ROOT_DIR}/scripts/analytics-smoke.sh"

  grep -q 'scripts/ci/cosign-sign-core-images.sh' \
    "${ROOT_DIR}/.github/workflows/prod-image-sign.yml" \
    || fail "prod-image-sign workflow does not reference scripts/ci/cosign-sign-core-images.sh"
  grep -q 'scripts/ci/check-backend-release-wiring.sh' \
    "${ROOT_DIR}/.github/workflows/backend-readiness.yml" \
    || fail "backend-readiness workflow does not reference scripts/ci/check-backend-release-wiring.sh"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-nlp/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-nlp/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-planner-api-gateway/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-planner-api-gateway/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-orchestrator-api-gateway/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-orchestrator-api-gateway/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-voice-gateway-api-gateway/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-voice-gateway-api-gateway/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-ingress-api-gateway/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-ingress-api-gateway/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-security-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-security-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-analytics-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-analytics-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-pc-control/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-pc-control/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-life-tracker/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-life-tracker/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-smart-home-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-smart-home-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-orchestrator/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-orchestrator/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-voice-gateway/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-voice-gateway/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-voice-gateway-orchestrator/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-voice-gateway-orchestrator/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-analytics-service-life-tracker/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-analytics-service-life-tracker/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-orchestrator-nlp-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-orchestrator-nlp-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-planner-service-analytics-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-planner-service-analytics-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-api-gateway-planner-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-api-gateway-planner-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-planner-service-voice-gateway/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-planner-service-voice-gateway/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-voice-gateway-smart-home-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-voice-gateway-smart-home-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-orchestrator-smart-home-service/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-orchestrator-smart-home-service/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-orchestrator-pc-control/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-orchestrator-pc-control/kustomization.yaml"
  [[ -f "${ROOT_DIR}/k8s/overlays/prod-release-internal-tls-verified/kustomization.yaml" ]] \
    || fail "Missing committed internal TLS overlay: k8s/overlays/prod-release-internal-tls-verified/kustomization.yaml"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-nlp.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the internal TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-planner-api-gateway.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the planner/api-gateway TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-orchestrator-api-gateway.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the orchestrator/api-gateway TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-voice-gateway-api-gateway.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the voice-gateway/api-gateway TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-ingress-api-gateway.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the ingress/api-gateway TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-security-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/security-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-analytics-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/analytics-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-pc-control.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/pc-control TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-life-tracker.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/life-tracker TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-smart-home-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/smart-home-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-orchestrator.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/orchestrator TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-voice-gateway.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/voice-gateway TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-voice-gateway-orchestrator.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the voice-gateway/orchestrator TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-analytics-service-life-tracker.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the analytics-service/life-tracker TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-orchestrator-nlp-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the orchestrator/nlp-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-planner-service-analytics-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the planner-service/analytics-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-api-gateway-planner-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the api-gateway/planner-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-planner-service-voice-gateway.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the planner-service/voice-gateway TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-voice-gateway-smart-home-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the voice-gateway/smart-home-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-orchestrator-smart-home-service.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the orchestrator/smart-home-service TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-orchestrator-pc-control.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the orchestrator/pc-control TLS slice deploy wrapper"
  grep -q 'jarvis-deploy-prod-internal-tls-verified.sh' \
    "${ROOT_DIR}/docs/HTTPS_STANDARD.md" \
    || fail "HTTPS_STANDARD.md does not reference the promoted internal TLS deploy wrapper"

  : > "${refs_file}"
  for service in "${CORE_BACKEND_SERVICES[@]}"; do
    printf '%s=%s\n' \
      "${service}" \
      "example.invalid/jarvis/${service}@sha256:$(digest_for_index "${idx}")" \
      >> "${refs_file}"
    idx=$((idx + 1))
  done

  JARVIS_RELEASE_OUTPUT_DIR="${overlay_dir}" \
    "${ROOT_DIR}/scripts/product/jarvis-promote-images.sh" \
    --refs-file="${refs_file}" >/dev/null

  [[ -f "${overlay_dir}/kustomization.yaml" ]] || fail "Release overlay was not generated"
  grep -q '^resources:$' "${overlay_dir}/kustomization.yaml" || fail "Generated overlay is missing resources stanza"
  grep -q '  - ../prod' "${overlay_dir}/kustomization.yaml" || fail "Generated overlay does not reference ../prod"

  while IFS= read -r service; do
    env_digest_refs+=("example.invalid/jarvis/${service}@sha256:$(digest_for_index 99)")
  done < <(printf '%s\n' "${CORE_SIGNED_SERVICES[@]}")

  cat > "${TMP_DIR}/cosign" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${COSIGN_TEST_LOG}"
EOF
  chmod +x "${TMP_DIR}/cosign"

  PATH="${TMP_DIR}:${PATH}" \
  COSIGN_TEST_LOG="${sign_log}" \
  COSIGN_CORE_IMAGES="$(printf '%s ' "${env_digest_refs[@]}")" \
    "${ROOT_DIR}/scripts/ci/cosign-sign-core-images.sh" >/dev/null

  [[ "$(wc -l < "${sign_log}")" -eq 6 ]] || fail "Expected 6 core image signing invocations"
  grep -q '^sign --yes example.invalid/jarvis/api-gateway@sha256:' "${sign_log}" || fail "Signing log is missing api-gateway"
  grep -q '^sign --yes example.invalid/jarvis/planner-service@sha256:' "${sign_log}" || fail "Signing log is missing planner-service"

  echo "✅ Backend release wiring checks passed"
}

main "$@"
