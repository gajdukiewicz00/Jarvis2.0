#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

ensure_local_env

errors=()
warnings=()

has_porcupine_access_key() {
    if [[ -n "${PORCUPINE_ACCESS_KEY:-}" ]]; then
        return 0
    fi

    local candidate
    local candidates=(
        "${SCRIPT_DIR}/../secrets/secrets.env"
        "${HOME}/.jarvis/secrets/secrets.env"
    )

    if [[ -n "${JARVIS_PROJECT_ROOT:-}" ]]; then
        candidates=("${JARVIS_PROJECT_ROOT}/secrets/secrets.env" "${candidates[@]}")
    fi

    for candidate in "${candidates[@]}"; do
        if [[ -f "${candidate}" ]] && grep -Eq '^[[:space:]]*PORCUPINE_ACCESS_KEY[[:space:]]*=[[:space:]]*[^[:space:]#]' "${candidate}"; then
            return 0
        fi
    done

    return 1
}

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

case "${JARVIS_STT_PROVIDER}" in
    vosk)
        if [[ ! -d "${JARVIS_VOSK_MODEL_PATH_RU}" && ! -d "${JARVIS_VOSK_MODEL_PATH_EN}" ]]; then
            warnings+=("JARVIS_STT_PROVIDER=vosk, but neither configured model path exists (${JARVIS_VOSK_MODEL_PATH_RU}, ${JARVIS_VOSK_MODEL_PATH_EN}). Run ./scripts/setup-voice-local.sh to install the canonical local Vosk models.")
        fi
        ;;
    whisper)
        if [[ ! -f "${JARVIS_VOICE_WHISPER_MODEL_PATH}" ]]; then
            warnings+=("JARVIS_STT_PROVIDER=whisper, but the configured model file is missing at ${JARVIS_VOICE_WHISPER_MODEL_PATH}. Audio STT will be unavailable.")
        fi
        ;;
    noop)
        warnings+=("JARVIS_STT_PROVIDER=noop. Full audio STT is intentionally disabled; only text-command and notification flows are verified.")
        ;;
    *)
        warnings+=("JARVIS_STT_PROVIDER=${JARVIS_STT_PROVIDER} is unsupported. Supported values: vosk, whisper, noop.")
        ;;
esac

if is_truthy "${TTS_ENABLED:-true}"; then
    case "${TTS_PROVIDER:-espeak}" in
        espeak)
            if ! resolve_local_espeak_binary >/dev/null 2>&1; then
                warnings+=("TTS_PROVIDER=espeak, but no executable eSpeak binary is available. Expected ${JARVIS_TTS_ESPEAK_BINARY}. Run ./scripts/setup-voice-local.sh to install the canonical local TTS path.")
            fi
            ;;
        google)
            if [[ -z "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
                warnings+=("TTS_PROVIDER=google, but GOOGLE_APPLICATION_CREDENTIALS is not set. Voice audio synthesis remains provider-dependent until Google credentials are configured.")
            elif [[ ! -f "${GOOGLE_APPLICATION_CREDENTIALS}" ]]; then
                warnings+=("TTS_PROVIDER=google, but GOOGLE_APPLICATION_CREDENTIALS points to a missing file: ${GOOGLE_APPLICATION_CREDENTIALS}.")
            fi
            ;;
        *)
            warnings+=("TTS_PROVIDER=${TTS_PROVIDER} is unsupported. Supported values: espeak, google.")
            ;;
    esac
else
    warnings+=("TTS is disabled (TTS_ENABLED=false). Voice replies will be text-only unless a pre-recorded asset exists.")
fi

if ! has_porcupine_access_key; then
    warnings+=("PORCUPINE_ACCESS_KEY is not configured in env or secrets/secrets.env. The desktop app will still work, but wake-word mode will be unavailable.")
fi

if [[ "${SMART_HOME_PROVIDER}" != "mock" ]]; then
    warnings+=("SMART_HOME_PROVIDER is ${SMART_HOME_PROVIDER}. Local deterministic smart-home behavior expects 'mock'.")
fi

model_count=0
resolved_model=""
if [[ -n "${JARVIS_LLM_MODEL_PATH:-}" ]]; then
    if [[ -f "${JARVIS_LLM_MODEL_PATH}" ]]; then
        resolved_model="${JARVIS_LLM_MODEL_PATH}"
        model_count=1
    else
        warnings+=("Configured JARVIS_LLM_MODEL_PATH is missing: ${JARVIS_LLM_MODEL_PATH}. Run ./scripts/setup-ai-local.sh to install the canonical local AI model stack.")
    fi
else
    mkdir -p "${JARVIS_LLM_MODEL_DIR}"
    while IFS= read -r model_path; do
        [[ -n "${model_path}" ]] || continue
        resolved_model="${model_path}"
        model_count=$((model_count + 1))
    done < <(find "${JARVIS_LLM_MODEL_DIR}" -maxdepth 1 -type f -iname '*.gguf' | sort)
fi

if ((model_count == 0)); then
    warnings+=("No GGUF model found under ${JARVIS_LLM_MODEL_DIR}. Run ./scripts/setup-ai-local.sh to install the canonical local AI model stack.")
elif ((model_count > 1)) && [[ -z "${JARVIS_LLM_MODEL_PATH:-}" ]]; then
    warnings+=("Multiple GGUF models found under ${JARVIS_LLM_MODEL_DIR}. Set JARVIS_LLM_MODEL_PATH in ${LOCAL_ENV_FILE} to pick one explicitly.")
fi

if is_truthy "${ENABLE_LLM:-false}" && ! command -v nvidia-smi >/dev/null 2>&1; then
    warnings+=("nvidia-smi is not available. llama.cpp can still run, but GPU verification will be limited.")
fi

if [[ -n "${JARVIS_EMBEDDING_MODEL_PATH:-}" ]] && [[ ! -d "${JARVIS_EMBEDDING_MODEL_PATH}" ]]; then
    warnings+=("Configured embedding model path is missing: ${JARVIS_EMBEDDING_MODEL_PATH}. Run ./scripts/setup-ai-local.sh to download the canonical embedding model.")
fi

printf 'Local env: %s\n' "${LOCAL_ENV_FILE}"
printf 'API base: %s\n' "${JARVIS_API_BASE_URL}"
printf 'Canonical voice stack: %s\n' "$(canonical_local_voice_stack_id)"
printf 'Canonical AI stack: %s\n' "$(canonical_local_ai_stack_id)"
printf 'Voice readiness: %s\n' "$(voice_readiness_status)"
printf 'Smart-home provider: %s\n' "${SMART_HOME_PROVIDER}"
printf 'STT provider: %s\n' "${JARVIS_STT_PROVIDER}"
printf 'TTS provider: %s (enabled=%s)\n' "${TTS_PROVIDER:-espeak}" "${TTS_ENABLED:-true}"
printf 'Voice models dir: %s\n' "${JARVIS_MODELS_DIR}"
printf 'Configured eSpeak binary: %s\n' "${JARVIS_TTS_ESPEAK_BINARY:-<unset>}"
if resolve_local_espeak_binary >/dev/null 2>&1; then
    printf 'Resolved eSpeak binary: %s\n' "$(resolve_local_espeak_binary)"
fi
printf 'LLM model dir: %s\n' "${JARVIS_LLM_MODEL_DIR}"
if [[ -n "${resolved_model}" ]] && ((model_count == 1)); then
    printf 'Resolved GGUF model: %s\n' "${resolved_model}"
fi
printf 'Embedding model id: %s\n' "${JARVIS_EMBEDDING_MODEL_ID:-<unset>}"
printf 'Embedding model path: %s\n' "${JARVIS_EMBEDDING_MODEL_PATH:-<unset>}"

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
