#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VERIFY_AI="${PROJECT_ROOT}/scripts/verify-ai.sh"

TMP_ROOT="$(mktemp -d)"
NEGATIVE_LOG="$(mktemp)"

cleanup() {
    rm -rf "${TMP_ROOT}"
    rm -f "${NEGATIVE_LOG}"
}

trap cleanup EXIT

echo "[verify-ai-test] positive: current repo passes"
JARVIS_VERIFY_PREFLIGHT=false \
JARVIS_VERIFY_LLM=false \
JARVIS_VERIFY_MEMORY=false \
"${VERIFY_AI}" >/dev/null

echo "[verify-ai-test] negative: missing required files fail fast"
if JARVIS_VERIFY_ROOT_DIR="${TMP_ROOT}" \
    JARVIS_VERIFY_PREFLIGHT=false \
    JARVIS_VERIFY_LLM=false \
    JARVIS_VERIFY_MEMORY=false \
    "${VERIFY_AI}" >"${NEGATIVE_LOG}" 2>&1; then
    echo "[verify-ai-test] ERROR: expected verify-ai to fail against empty root" >&2
    exit 1
fi

if ! grep -q "Missing required file" "${NEGATIVE_LOG}"; then
    echo "[verify-ai-test] ERROR: missing-file failure message not found" >&2
    cat "${NEGATIVE_LOG}" >&2
    exit 1
fi

echo "[verify-ai-test] OK"
