#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env

printf 'Created or refreshed local env: %s\n' "${LOCAL_ENV_FILE}"
printf 'Runtime mode: %s\n' "${JARVIS_RUNTIME_MODE}"
printf 'API base: %s\n' "${JARVIS_API_BASE_URL}"
printf 'Smart-home provider: %s\n' "${SMART_HOME_PROVIDER}"
printf 'Canonical AI stack: %s\n' "${JARVIS_CANONICAL_LOCAL_AI_STACK}"
printf 'LLM model dir: %s\n' "${JARVIS_LLM_MODEL_DIR}"
printf 'Embedding model path: %s\n' "${JARVIS_EMBEDDING_MODEL_PATH}"
printf '\nNext steps:\n'
printf '  1. ./scripts/check-local-env.sh\n'
printf '  2. ./scripts/setup-ai-local.sh\n'
printf '  3. ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh\n'
printf '  4. ./scripts/runtime-status.sh\n'
printf '  5. ./scripts/ai-local-smoke.sh\n'
