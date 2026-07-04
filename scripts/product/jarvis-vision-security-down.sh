#!/usr/bin/env bash
# =============================================================================
# Stop the workstation-local vision-security-service Java process.
# =============================================================================
# Counterpart of jarvis-vision-security-up.sh. Uses the standard runtime PID
# file under ~/.jarvis/run/local-runtime/.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# shellcheck disable=SC1091
source "${ROOT_DIR}/scripts/runtime/common.sh"

stop_service "vision-security-service"
