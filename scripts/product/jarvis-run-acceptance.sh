#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Acceptance Runner (UI-friendly)
# =============================================================================
# Runs verify + acceptance via internal port-forward.
# Writes report to ~/.jarvis/logs/acceptance-<timestamp>.txt
# =============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
LOG_DIR="${JARVIS_HOME}/logs"
mkdir -p "${LOG_DIR}"

timestamp="$(date +%Y%m%d-%H%M%S)"
REPORT_FILE="${LOG_DIR}/acceptance-${timestamp}.txt"

PORT="${JARVIS_ACCEPT_PORT:-18080}"
USER_ID="${JARVIS_USER_ID:-acceptance-user}"

if [[ -z "${KUBECONFIG:-}" && -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG="/etc/rancher/k3s/k3s.yaml"
fi

exec > >(tee -a "${REPORT_FILE}") 2>&1

echo "=== Jarvis Acceptance Run ==="
echo "Timestamp: $(date -Is)"
echo "Port-forward: ${PORT}"
echo "User ID: ${USER_ID}"

if ! command -v kubectl >/dev/null 2>&1; then
    echo "kubectl not found"
    exit 1
fi

kubectl -n jarvis port-forward svc/api-gateway "${PORT}:8080" >/dev/null 2>&1 &
pf_pid=$!
sleep 2

cleanup() {
    if kill -0 "${pf_pid}" >/dev/null 2>&1; then
        kill "${pf_pid}" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

export JARVIS_API_BASE_URL="http://127.0.0.1:${PORT}"
export JARVIS_USER_ID="${USER_ID}"

"${ROOT_DIR}/scripts/acceptance-ai.sh"
echo "Acceptance report: ${REPORT_FILE}"
