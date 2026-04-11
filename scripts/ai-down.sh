#!/usr/bin/env bash
#
# Stop the AI services without affecting the core runtime.
#
# Usage:
#   ./scripts/ai-down.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env

stop_service "llm-service"
stop_service "memory-service"
stop_service "embedding-service"
stop_service "llm-server"

log "AI services stopped."
