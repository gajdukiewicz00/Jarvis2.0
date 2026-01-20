#!/usr/bin/env bash
# =============================================================================
# Deprecated wrapper: use scripts/product/jarvis-generate-certs.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEW_SCRIPT="${SCRIPT_DIR}/product/jarvis-generate-certs.sh"

echo "[DEPRECATED] Use: ${NEW_SCRIPT}" >&2
exec "${NEW_SCRIPT}" "$@"
