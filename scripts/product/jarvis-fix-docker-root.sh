#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Fix Docker Root Dir Symlink
# =============================================================================
# Resets /var/lib/docker when it is a symlink (common source of overlay2 errors)
# =============================================================================

set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run with sudo: sudo $0 --reset"
    exit 1
fi

if [[ "${1:-}" != "--reset" ]]; then
    echo "Usage: sudo $0 --reset"
    exit 1
fi

if [[ ! -L /var/lib/docker ]]; then
    echo "/var/lib/docker is not a symlink. Nothing to fix."
    exit 0
fi

LINK_TARGET="$(readlink -f /var/lib/docker)"

echo "Stopping docker..."
systemctl stop docker

echo "Resetting /var/lib/docker..."
rm /var/lib/docker
mkdir -p /var/lib/docker
chmod 711 /var/lib/docker

echo "Starting docker..."
systemctl start docker

echo "Done. Old data remains at: ${LINK_TARGET}"
