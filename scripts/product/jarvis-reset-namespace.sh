#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Reset Namespace (safe by default)
# =============================================================================
# Default: delete workloads and configs in namespace, preserve PVC/PV.
# Use --wipe-data to delete namespace (PVCs will be removed).
# =============================================================================

set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run with sudo or pkexec: $0"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
WIPE_DATA="false"

for arg in "$@"; do
    case "$arg" in
        --wipe-data)
            WIPE_DATA="true"
            ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: $0 [--wipe-data]"
            exit 1
            ;;
    esac
done

resolve_user_home() {
    if [[ -n "${JARVIS_HOME:-}" ]]; then
        return 0
    fi
    if [[ -n "${SUDO_USER:-}" ]]; then
        local user_home
        user_home=$(getent passwd "${SUDO_USER}" | cut -d: -f6 || true)
        if [[ -n "${user_home}" ]]; then
            export JARVIS_HOME="${user_home}/.jarvis"
            return 0
        fi
    fi
    if [[ -n "${PKEXEC_UID:-}" ]]; then
        local user_home
        user_home=$(getent passwd "${PKEXEC_UID}" | cut -d: -f6 || true)
        if [[ -n "${user_home}" ]]; then
            export JARVIS_HOME="${user_home}/.jarvis"
            return 0
        fi
    fi
    export JARVIS_HOME="${HOME}/.jarvis"
}

resolve_user_home

LOG_DIR="${JARVIS_HOME}/logs"
mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/reset.log"

exec > >(tee -a "${LOG_FILE}") 2>&1

if [[ -z "${KUBECONFIG:-}" && -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG="/etc/rancher/k3s/k3s.yaml"
fi

echo "=== Jarvis Reset Namespace ==="
echo "Timestamp: $(date -Is)"
echo "Namespace: ${NAMESPACE}"
echo "Wipe data: ${WIPE_DATA}"

if [[ "${WIPE_DATA}" == "true" ]]; then
    kubectl delete namespace "${NAMESPACE}" --wait=true || true
    kubectl create namespace "${NAMESPACE}" || true
    echo "Namespace reset complete (data wiped)."
    exit 0
fi

if ! kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1; then
    kubectl create namespace "${NAMESPACE}" >/dev/null
    echo "Namespace created."
fi

kubectl delete deployment,statefulset,daemonset,job,cronjob,service,ingress,configmap,secret,serviceaccount,role,rolebinding,networkpolicy \
    -n "${NAMESPACE}" --ignore-not-found || true

kubectl delete pod --all -n "${NAMESPACE}" --ignore-not-found || true

echo "Namespace reset complete (PVCs preserved)."
