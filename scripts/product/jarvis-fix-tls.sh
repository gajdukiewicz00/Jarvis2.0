#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - TLS Fix (certs + trust + hosts)
# =============================================================================
# Runs privileged TLS setup in a single step for launcher UI.
# =============================================================================

set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run with sudo or pkexec: $0"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
        local user_name
        local user_home
        user_name=$(getent passwd "${PKEXEC_UID}" | cut -d: -f1 || true)
        user_home=$(getent passwd "${PKEXEC_UID}" | cut -d: -f6 || true)
        if [[ -n "${user_home}" ]]; then
            export JARVIS_HOME="${user_home}/.jarvis"
            export JARVIS_USER="${user_name}"
            return 0
        fi
    fi
    export JARVIS_HOME="${HOME}/.jarvis"
}

resolve_user_home

if [[ -z "${KUBECONFIG:-}" && -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG="/etc/rancher/k3s/k3s.yaml"
fi

"${SCRIPT_DIR}/jarvis-generate-certs.sh"
"${SCRIPT_DIR}/jarvis-install-tls.sh"
"${SCRIPT_DIR}/jarvis-setup-hosts.sh"
