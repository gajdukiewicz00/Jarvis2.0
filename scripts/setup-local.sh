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
printf '\nNext steps:\n'
printf '  1. ./scripts/check-local-env.sh\n'
printf '  2. ./scripts/runtime-up.sh\n'
printf '  3. JARVIS_RUNTIME_SMOKE_STOP_ON_EXIT=true ./scripts/runtime-smoke.sh\n'
