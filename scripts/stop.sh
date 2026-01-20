#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Stop Wrapper
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "[INFO] Use ${PROJECT_ROOT}/jarvis-stop.sh for full cleanup" >&2
exec "${PROJECT_ROOT}/jarvis-stop.sh" "$@"
