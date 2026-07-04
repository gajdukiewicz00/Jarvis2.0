#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Disk Cleanup (root filesystem)
# =============================================================================
# Frees space by pruning unused container images and system caches.
# Intended to be run via sudo/pkexec from launcher or jarvis-launch.sh.
# =============================================================================

set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run with sudo or pkexec: $0"
    exit 1
fi

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
LOG_FILE="${LOG_DIR}/disk-cleanup.log"

exec > >(tee -a "${LOG_FILE}") 2>&1

threshold="${JARVIS_DISK_THRESHOLD:-95}"
usage="$(df -P / | awk 'NR==2 {gsub(/%/,\"\",$5); print $5}')"

if [[ -z "${usage}" ]]; then
    echo "Unable to determine disk usage."
    exit 1
fi

if [[ "${usage}" -lt "${threshold}" ]]; then
    echo "Disk usage ${usage}% < ${threshold}%; no cleanup needed."
    exit 0
fi

echo "Disk usage ${usage}% >= ${threshold}%; running cleanup..."

if command -v journalctl >/dev/null 2>&1; then
    journalctl --vacuum-size=200M >/dev/null 2>&1 || true
fi

if command -v apt-get >/dev/null 2>&1; then
    apt-get clean >/dev/null 2>&1 || true
fi

if command -v podman >/dev/null 2>&1; then
    podman image prune -af >/dev/null 2>&1 || true
    podman system prune -af >/dev/null 2>&1 || true
fi

if command -v k3s >/dev/null 2>&1; then
    k3s ctr images prune >/dev/null 2>&1 || true
    k3s crictl rmi --prune >/dev/null 2>&1 || true
fi

usage_after="$(df -P / | awk 'NR==2 {gsub(/%/,\"\",$5); print $5}')"
echo "Disk usage after cleanup: ${usage_after}%"
