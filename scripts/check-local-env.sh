#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env

errors=()
warnings=()

require_command() {
    local command_name="$1"
    if ! command -v "${command_name}" >/dev/null 2>&1; then
        errors+=("Missing required command: ${command_name}")
    fi
}

warn_if_missing_command() {
    local command_name="$1"
    local message="$2"
    if ! command -v "${command_name}" >/dev/null 2>&1; then
        warnings+=("${message}")
    fi
}

require_command java
require_command mvn
require_command curl
require_command python3
warn_if_missing_command docker "Docker not found. Local runtime will need an external PostgreSQL instead of the managed container."

if [[ ! -f "${LOCAL_ENV_FILE}" ]]; then
    errors+=("Local env file was not created at ${LOCAL_ENV_FILE}")
fi

if [[ ! -d "${JARVIS_VOSK_MODEL_PATH_RU}" && ! -d "${JARVIS_VOSK_MODEL_PATH_EN}" ]]; then
    warnings+=("No Vosk models found. Voice wake/STT remains optional until you download them.")
fi

if [[ -z "${PORCUPINE_ACCESS_KEY:-}" ]]; then
    warnings+=("PORCUPINE_ACCESS_KEY is not set. The desktop app will still work, but wake-word mode will be unavailable.")
fi

if [[ "${SMART_HOME_PROVIDER}" != "mock" ]]; then
    warnings+=("SMART_HOME_PROVIDER is ${SMART_HOME_PROVIDER}. Local deterministic smart-home behavior expects 'mock'.")
fi

printf 'Local env: %s\n' "${LOCAL_ENV_FILE}"
printf 'API base: %s\n' "${JARVIS_API_BASE_URL}"
printf 'Smart-home provider: %s\n' "${SMART_HOME_PROVIDER}"

if ((${#warnings[@]} > 0)); then
    printf '\nWarnings:\n'
    for warning in "${warnings[@]}"; do
        printf ' - %s\n' "${warning}"
    done
fi

if ((${#errors[@]} > 0)); then
    printf '\nErrors:\n' >&2
    for error in "${errors[@]}"; do
        printf ' - %s\n' "${error}" >&2
    done
    exit 1
fi

printf '\nLocal environment looks runnable.\n'
