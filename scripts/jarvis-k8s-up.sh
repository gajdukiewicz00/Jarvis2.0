#!/usr/bin/env bash
# =============================================================================
# Deprecated: use ./jarvis-launch.sh with flags
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

ENABLE_GPU=true
ENABLE_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --no-gpu)
            ENABLE_GPU=false
            ;;
        --build)
            ENABLE_BUILD=true
            ;;
        --help|-h)
            echo "Usage: $0 [--no-gpu] [--build]"
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg"
            exit 1
            ;;
    esac
done

echo "[DEPRECATED] Use: ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh" >&2

export ENABLE_LLM=true
export ENABLE_MEMORY=true
export ENABLE_GPU
export ENABLE_BUILD

exec "${PROJECT_ROOT}/jarvis-launch.sh" "$@"
