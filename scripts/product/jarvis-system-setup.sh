#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - System Setup (TLS trust + /etc/hosts)
# =============================================================================
# Runs privileged steps in one shot (intended for GUI launchers).
# =============================================================================

set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run with sudo or pkexec: $0"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure JARVIS_HOME is set to user home when running as root
if [[ -z "${JARVIS_HOME:-}" ]]; then
    if [[ -n "${SUDO_USER:-}" ]]; then
        USER_HOME=$(getent passwd "${SUDO_USER}" | cut -d: -f6 || true)
        if [[ -n "${USER_HOME}" ]]; then
            export JARVIS_HOME="${USER_HOME}/.jarvis"
        fi
    elif [[ -n "${PKEXEC_UID:-}" ]]; then
        USER_HOME=$(getent passwd "${PKEXEC_UID}" | cut -d: -f6 || true)
        if [[ -n "${USER_HOME}" ]]; then
            export JARVIS_HOME="${USER_HOME}/.jarvis"
        fi
    fi
fi

# If KUBECONFIG is not set, prefer k3s default
if [[ -z "${KUBECONFIG:-}" && -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG="/etc/rancher/k3s/k3s.yaml"
fi

"${SCRIPT_DIR}/jarvis-install-tls.sh"
"${SCRIPT_DIR}/jarvis-setup-hosts.sh"
