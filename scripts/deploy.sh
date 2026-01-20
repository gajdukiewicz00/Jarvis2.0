#!/usr/bin/env bash
# =============================================================================
# Deprecated: use ./jarvis-launch.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "[DEPRECATED] Use: ${PROJECT_ROOT}/jarvis-launch.sh" >&2
exec "${PROJECT_ROOT}/jarvis-launch.sh" "$@"
