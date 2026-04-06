#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "jarvis-desktop-install.sh is now a compatibility wrapper."
echo "Installing the canonical Jarvis desktop entry via scripts/product/jarvis-install.sh..."

exec "${SCRIPT_DIR}/jarvis-install.sh" "$@"
