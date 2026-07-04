#!/usr/bin/env bash
# =============================================================================
# Phase 1 acceptance wrapper: deploy + cluster readiness + smoke + persistence.
# =============================================================================
# Runs the full Phase 1 acceptance gate end-to-end and captures every step into
# ${EVIDENCE_DIR} (default /tmp/jarvis-phase1) so the result can be pasted into
# docs/architecture/phase-1-acceptance-evidence.md.
#
# Steps:
#   1. (optional) jarvis-deploy-microk8s-prod.sh       -> overlay applied
#   2. kubectl get pods/svc/ingress/pvc -n jarvis-prod -> structural snapshot
#   3. scripts/k8s-smoke.sh                            -> ns + ingress + brokers + HTTPS
#   4. infra/scripts/microk8s/persistence-test.sh      -> brokers/db survive restart
#
# Env knobs:
#   JARVIS_NAMESPACE        default: jarvis-prod
#   EVIDENCE_DIR            default: /tmp/jarvis-phase1
#   SKIP_DEPLOY             default: false (set true to skip step 1)
#   SKIP_SMOKE              default: false
#   SKIP_PERSISTENCE        default: false
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
EVIDENCE_DIR="${EVIDENCE_DIR:-/tmp/jarvis-phase1}"
SKIP_DEPLOY="${SKIP_DEPLOY:-false}"
SKIP_SMOKE="${SKIP_SMOKE:-false}"
SKIP_PERSISTENCE="${SKIP_PERSISTENCE:-false}"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/microk8s/verify-phase1.sh [options]

Options:
  --namespace=NAME       Kubernetes namespace, default: jarvis-prod
  --evidence=PATH        Directory for captured logs, default: /tmp/jarvis-phase1
  --skip-deploy          Skip ./scripts/product/jarvis-deploy-microk8s-prod.sh
  --skip-smoke           Skip ./scripts/k8s-smoke.sh
  --skip-persistence     Skip persistence restart test
  --help, -h             Show this help

The wrapper expects kubectl to be authenticated against the target MicroK8s
cluster. It does not run with sudo. Generate certs and host entries up front:
  ./scripts/product/jarvis-generate-certs.sh
  sudo ./scripts/product/jarvis-setup-hosts.sh
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)        NAMESPACE="${arg#*=}" ;;
    --evidence=*)         EVIDENCE_DIR="${arg#*=}" ;;
    --skip-deploy)        SKIP_DEPLOY=true ;;
    --skip-smoke)         SKIP_SMOKE=true ;;
    --skip-persistence)   SKIP_PERSISTENCE=true ;;
    --help|-h)            usage; exit 0 ;;
    *) echo "❌ Unknown argument: ${arg}" >&2; usage >&2; exit 1 ;;
  esac
done

mkdir -p "${EVIDENCE_DIR}"
SUMMARY="${EVIDENCE_DIR}/summary.txt"
: >"${SUMMARY}"

log_step() {
  printf '── %s ──\n' "$1" | tee -a "${SUMMARY}"
}
record() {
  local label="$1" status="$2" log="$3"
  printf '  %-30s %s   log: %s\n' "${label}" "${status}" "${log}" | tee -a "${SUMMARY}"
}

run_step() {
  local label="$1"; shift
  local log="${EVIDENCE_DIR}/${label}.log"
  log_step "${label}"
  if "$@" >"${log}" 2>&1; then
    record "${label}" "✅" "${log}"
  else
    record "${label}" "❌" "${log}"
    echo "❌ Step '${label}' failed. See ${log}" >&2
    return 1
  fi
}

START_TS="$(date -u +%FT%TZ)"
{
  echo "Phase 1 acceptance run"
  echo "  start: ${START_TS}"
  echo "  namespace: ${NAMESPACE}"
  echo "  evidence: ${EVIDENCE_DIR}"
  echo ""
} | tee -a "${SUMMARY}"

# 1. Deploy
if [[ "${SKIP_DEPLOY}" != "true" ]]; then
  run_step deploy "${ROOT_DIR}/scripts/product/jarvis-deploy-microk8s-prod.sh" \
    --namespace="${NAMESPACE}" --skip-smoke
else
  log_step deploy
  record deploy "⏭" "skipped (SKIP_DEPLOY=true)"
fi

# 2. Structural snapshot
run_step pods     kubectl get pods     -n "${NAMESPACE}" -o wide
run_step services kubectl get svc      -n "${NAMESPACE}"
run_step ingress  kubectl get ingress  -n "${NAMESPACE}"
run_step pvc      kubectl get pvc      -n "${NAMESPACE}"

# 3. Smoke (acceptance #2 + #4)
if [[ "${SKIP_SMOKE}" != "true" ]]; then
  run_step smoke "${ROOT_DIR}/scripts/k8s-smoke.sh" --namespace="${NAMESPACE}"
else
  log_step smoke
  record smoke "⏭" "skipped (SKIP_SMOKE=true)"
fi

# 4. Persistence (acceptance #3)
if [[ "${SKIP_PERSISTENCE}" != "true" ]]; then
  run_step persistence "${SCRIPT_DIR}/persistence-test.sh" --namespace="${NAMESPACE}"
else
  log_step persistence
  record persistence "⏭" "skipped (SKIP_PERSISTENCE=true)"
fi

END_TS="$(date -u +%FT%TZ)"
{
  echo ""
  echo "Phase 1 acceptance complete"
  echo "  finish: ${END_TS}"
  echo "  evidence dir: ${EVIDENCE_DIR}"
} | tee -a "${SUMMARY}"

echo ""
echo "✅ Phase 1 verification finished. Evidence in ${EVIDENCE_DIR}"
