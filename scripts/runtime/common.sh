#!/usr/bin/env bash

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "This script is meant to be sourced." >&2
    exit 1
fi

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
JARVIS_MODELS_DIR="${JARVIS_MODELS_DIR:-${JARVIS_HOME}/models}"
JARVIS_TOOLS_DIR="${JARVIS_TOOLS_DIR:-${JARVIS_HOME}/tools}"
JARVIS_CANONICAL_LOCAL_VOICE_STACK="${JARVIS_CANONICAL_LOCAL_VOICE_STACK:-vosk+espeak-ng}"
JARVIS_CANONICAL_LOCAL_AI_STACK="${JARVIS_CANONICAL_LOCAL_AI_STACK:-qwen2.5-3b-instruct-q4_k_m+multilingual-e5-small+llamacpp+pgvector}"
JARVIS_CANONICAL_LOCAL_LLM_MODEL_ID="${JARVIS_CANONICAL_LOCAL_LLM_MODEL_ID:-Qwen/Qwen2.5-3B-Instruct-GGUF}"
JARVIS_CANONICAL_LOCAL_LLM_MODEL_FILENAME="${JARVIS_CANONICAL_LOCAL_LLM_MODEL_FILENAME:-qwen2.5-3b-instruct-q4_k_m.gguf}"
JARVIS_CANONICAL_LOCAL_EMBEDDING_MODEL_ID="${JARVIS_CANONICAL_LOCAL_EMBEDDING_MODEL_ID:-intfloat/multilingual-e5-small}"
RUNTIME_DIR="${JARVIS_HOME}/run/local-runtime"
LOG_DIR="${JARVIS_HOME}/logs/local-runtime"
LOCAL_ENV_FILE="${RUNTIME_DIR}/local.env"
RUN_SUMMARY="${JARVIS_HOME}/run/last-run.json"
POSTGRES_CONTAINER="${JARVIS_LOCAL_POSTGRES_CONTAINER:-jarvis-local-postgres}"
JARVIS_LOCAL_POSTGRES_IMAGE="${JARVIS_LOCAL_POSTGRES_IMAGE:-pgvector/pgvector:pg16}"

CORE_SERVICES=(
    "security-service"
    "user-profile"
    "nlp-service"
    "orchestrator"
    "voice-gateway"
    "pc-control"
    "smart-home-service"
    "life-tracker"
    "analytics-service"
    "api-gateway"
    "planner-service"
)

OPTIONAL_SERVICES=(
    "llm-server"
    "embedding-service"
    "memory-service"
    "llm-service"
)

mkdir -p "${RUNTIME_DIR}" "${LOG_DIR}" "${JARVIS_HOME}/run" "${JARVIS_MODELS_DIR}" "${JARVIS_TOOLS_DIR}"

# shellcheck disable=SC1091
source "${ROOT_DIR}/scripts/runtime/python_ai.sh"

log() {
    printf '[%s] %s\n' "$(date -Is)" "$*"
}

warn() {
    printf '[%s] WARN: %s\n' "$(date -Is)" "$*" >&2
}

fail() {
    printf '[%s] ERROR: %s\n' "$(date -Is)" "$*" >&2
    exit 1
}

is_truthy() {
    case "${1:-}" in
        1|true|TRUE|yes|YES|on|ON)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

append_java_tool_options() {
    local current="${1-}"
    shift || true

    local result="${current}"
    local option
    for option in "$@"; do
        if [[ -z "${result}" ]]; then
            result="${option}"
        elif [[ " ${result} " != *" ${option} "* ]]; then
            result="${result} ${option}"
        fi
    done

    printf '%s' "${result}"
}

runtime_api_scheme() {
    if is_truthy "${JARVIS_USE_TLS:-false}"; then
        printf 'https'
    else
        printf 'http'
    fi
}

runtime_ws_scheme() {
    if is_truthy "${JARVIS_USE_TLS:-false}"; then
        printf 'wss'
    else
        printf 'ws'
    fi
}

runtime_local_http_url() {
    local port="$1"
    printf 'http://127.0.0.1:%s' "${port}"
}

runtime_api_port() {
    printf '%s' "${JARVIS_API_GATEWAY_PORT:-8080}"
}

runtime_api_base_url() {
    printf '%s://127.0.0.1:%s' "$(runtime_api_scheme)" "$(runtime_api_port)"
}

runtime_voice_ws_url() {
    printf '%s://127.0.0.1:%s/ws/voice' "$(runtime_ws_scheme)" "$(runtime_api_port)"
}

runtime_pc_ws_url() {
    printf '%s://127.0.0.1:%s/ws/pc-control' "$(runtime_ws_scheme)" "$(runtime_api_port)"
}

runtime_local_ca_cert() {
    printf '%s/tls/jarvis-ca.crt' "${JARVIS_HOME}"
}

runtime_local_java_truststore() {
    printf '%s/tls/jarvis-cacerts.jks' "${JARVIS_HOME}"
}

runtime_local_gateway_keystore() {
    printf '%s/tls/jarvis-keystore.p12' "${JARVIS_HOME}"
}

runtime_java_tool_options() {
    local current="${1-}"

    if ! is_truthy "${JARVIS_USE_TLS:-false}"; then
        printf '%s' "${current}"
        return 0
    fi

    local truststore="${JARVIS_JAVA_TRUSTSTORE:-$(runtime_local_java_truststore)}"
    if [[ ! -f "${truststore}" ]]; then
        printf '%s' "${current}"
        return 0
    fi

    append_java_tool_options \
        "${current}" \
        "-Djavax.net.ssl.trustStore=${truststore}" \
        "-Djavax.net.ssl.trustStorePassword=${JARVIS_JAVA_TRUSTSTORE_PASSWORD:-changeit}"
}

curl_with_runtime_tls() {
    local url="$1"
    shift

    local -a tls_args=()
    case "${url}" in
        https://127.0.0.1:*|https://localhost:*|https://api.jarvis.local*|https://voice.jarvis.local*)
            local ca_cert
            ca_cert="${JARVIS_TLS_CA_CERT:-$(runtime_local_ca_cert)}"
            if [[ -f "${ca_cert}" ]]; then
                tls_args+=(--cacert "${ca_cert}")
            fi
            ;;
    esac

    curl "$@" "${tls_args[@]}" "${url}"
}

ensure_local_tls_material() {
    if ! is_truthy "${JARVIS_USE_TLS:-false}"; then
        return 0
    fi

    local ca_cert="${JARVIS_TLS_CA_CERT:-$(runtime_local_ca_cert)}"
    local keystore="${JARVIS_GATEWAY_KEYSTORE_PATH:-$(runtime_local_gateway_keystore)}"
    local truststore="${JARVIS_JAVA_TRUSTSTORE:-$(runtime_local_java_truststore)}"

    [[ -f "${ca_cert}" ]] || fail "Local HTTPS/WSS requested (JARVIS_USE_TLS=true) but CA certificate is missing at ${ca_cert}. Run ./scripts/product/jarvis-generate-certs.sh."
    [[ -f "${keystore}" ]] || fail "Local HTTPS/WSS requested but API gateway keystore is missing at ${keystore}. Run ./scripts/product/jarvis-generate-certs.sh."
    [[ -f "${truststore}" ]] || fail "Local HTTPS/WSS requested but Java truststore is missing at ${truststore}. Run ./scripts/product/jarvis-generate-certs.sh."
}

canonical_local_voice_stack_id() {
    printf '%s' "${JARVIS_CANONICAL_LOCAL_VOICE_STACK:-vosk+espeak-ng}"
}

canonical_local_ai_stack_id() {
    printf '%s' "${JARVIS_CANONICAL_LOCAL_AI_STACK:-qwen2.5-3b-instruct-q4_k_m+multilingual-e5-small+llamacpp+pgvector}"
}

canonical_local_llm_model_id() {
    printf '%s' "${JARVIS_CANONICAL_LOCAL_LLM_MODEL_ID:-Qwen/Qwen2.5-3B-Instruct-GGUF}"
}

canonical_local_llm_model_filename() {
    printf '%s' "${JARVIS_CANONICAL_LOCAL_LLM_MODEL_FILENAME:-qwen2.5-3b-instruct-q4_k_m.gguf}"
}

canonical_local_embedding_model_id() {
    printf '%s' "${JARVIS_CANONICAL_LOCAL_EMBEDDING_MODEL_ID:-intfloat/multilingual-e5-small}"
}

runtime_local_models_dir() {
    printf '%s/models' "${JARVIS_HOME}"
}

runtime_local_tools_dir() {
    printf '%s/tools' "${JARVIS_HOME}"
}

runtime_local_llm_model_dir() {
    printf '%s/llm' "${JARVIS_MODELS_DIR}"
}

runtime_local_llm_model_path() {
    printf '%s/%s' "$(runtime_local_llm_model_dir)" "$(canonical_local_llm_model_filename)"
}

runtime_local_embedding_model_path() {
    printf '%s/embeddings/intfloat-multilingual-e5-small' "${JARVIS_MODELS_DIR}"
}

runtime_local_ai_gpu_status_file() {
    printf '%s/ai-gpu-status.json' "${RUNTIME_DIR}"
}

runtime_local_vosk_model_path() {
    local language="${1:-ru-RU}"
    case "${language}" in
        en|en-US|en_US)
            printf '%s/stt/vosk/vosk-model-small-en-us-0.15' "${JARVIS_MODELS_DIR}"
            ;;
        *)
            printf '%s/stt/vosk/vosk-model-small-ru-0.22' "${JARVIS_MODELS_DIR}"
            ;;
    esac
}

runtime_local_whisper_model_path() {
    printf '%s/stt/whisper/ggml-small.bin' "${JARVIS_MODELS_DIR}"
}

runtime_local_espeak_binary() {
    printf '%s/bin/espeak-ng' "${JARVIS_TOOLS_DIR}"
}

resolve_local_espeak_binary() {
    local configured="${JARVIS_TTS_ESPEAK_BINARY:-}"
    if [[ -n "${configured}" && -x "${configured}" ]]; then
        printf '%s' "${configured}"
        return 0
    fi

    local binary
    for binary in espeak-ng espeak; do
        if command -v "${binary}" >/dev/null 2>&1; then
            command -v "${binary}"
            return 0
        fi
    done

    return 1
}

voice_stt_ready() {
    case "${JARVIS_STT_PROVIDER:-vosk}" in
        vosk)
            [[ -d "${JARVIS_VOSK_MODEL_PATH_RU}" || -d "${JARVIS_VOSK_MODEL_PATH_EN}" ]]
            ;;
        whisper)
            [[ -f "${JARVIS_VOICE_WHISPER_MODEL_PATH}" ]]
            ;;
        *)
            return 1
            ;;
    esac
}

voice_tts_ready() {
    if ! is_truthy "${TTS_ENABLED:-true}"; then
        return 1
    fi

    case "${TTS_PROVIDER:-espeak}" in
        espeak)
            resolve_local_espeak_binary >/dev/null 2>&1
            ;;
        google)
            [[ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" && -f "${GOOGLE_APPLICATION_CREDENTIALS}" ]]
            ;;
        *)
            return 1
            ;;
    esac
}

voice_readiness_status() {
    local stt_ready="false"
    local tts_ready="false"

    if voice_stt_ready; then
        stt_ready="true"
    fi
    if voice_tts_ready; then
        tts_ready="true"
    fi

    if [[ "${stt_ready}" == "true" && "${tts_ready}" == "true" ]]; then
        printf 'full-audio ready'
    elif [[ "${stt_ready}" == "true" ]]; then
        printf 'tts missing'
    elif [[ "${tts_ready}" == "true" ]]; then
        printf 'stt missing'
    else
        printf 'text-only ready'
    fi
}

random_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 48 | tr -d '\n'
        return 0
    fi

    python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(48))
PY
}

default_stt_provider() {
    printf 'vosk'
}

default_llm_threads() {
    local cpu_count
    cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || printf '8')"

    if [[ ! "${cpu_count}" =~ ^[0-9]+$ ]]; then
        printf '6'
        return 0
    fi

    if ((cpu_count <= 4)); then
        printf '%s' "${cpu_count}"
    elif ((cpu_count <= 8)); then
        printf '%s' "$((cpu_count - 1))"
    else
        printf '%s' "$((cpu_count / 2))"
    fi
}

write_env_assignment() {
    local file="$1"
    local key="$2"
    local value="${3-}"
    printf '%s=' "${key}" >>"${file}"
    printf '%q' "${value}" >>"${file}"
    printf '\n' >>"${file}"
}

ensure_local_env() {
    local override_vars=(
        SPRING_DATASOURCE_URL
        SPRING_DATASOURCE_USERNAME
        SPRING_DATASOURCE_PASSWORD
        POSTGRES_USER
        POSTGRES_PASSWORD
        JWT_SECRET
        SERVICE_JWT_SECRET
        ENABLE_LLM
        JARVIS_LLM_ENABLED
        ENABLE_MEMORY
        MEMORY_SERVICE_ENABLED
        JARVIS_STT_PROVIDER
        JARVIS_MODELS_DIR
        JARVIS_TOOLS_DIR
        JARVIS_VOSK_MODEL_PATH_RU
        JARVIS_VOSK_MODEL_PATH_EN
        JARVIS_VOICE_WHISPER_MODEL_PATH
        JARVIS_TTS_ESPEAK_BINARY
        LLM_SERVER_URL
        MEMORY_SERVICE_URL
        EMBEDDING_SERVICE_URL
        JARVIS_API_GATEWAY_PORT
        JARVIS_VOICE_GATEWAY_PORT
        JARVIS_NLP_SERVICE_PORT
        JARVIS_ORCHESTRATOR_PORT
        JARVIS_PC_CONTROL_PORT
        JARVIS_LIFE_TRACKER_PORT
        JARVIS_SMART_HOME_PORT
        JARVIS_ANALYTICS_PORT
        JARVIS_SECURITY_PORT
        JARVIS_USER_PROFILE_PORT
        JARVIS_LLM_SERVICE_PORT
        JARVIS_PLANNER_PORT
        JARVIS_MEMORY_SERVICE_PORT
        LLM_SERVER_PORT
        EMBEDDING_SERVICE_PORT
        JARVIS_LLM_MANAGED_SERVER
        JARVIS_CANONICAL_LOCAL_AI_STACK
        JARVIS_LLM_MODEL_ID
        JARVIS_LLM_MODEL_FILENAME
        JARVIS_LLM_MODEL_DIR
        JARVIS_LLM_MODEL_PATH
        JARVIS_EMBEDDING_MODEL_ID
        JARVIS_EMBEDDING_MODEL_PATH
        JARVIS_AI_GPU_STATUS_FILE
        JARVIS_LLAMACPP_PACKAGE_SPEC
        JARVIS_LLAMACPP_EXTRA_INDEX_URL
        JARVIS_LLAMACPP_RUNTIME_PACKAGES
        LLM_BACKEND
        N_GPU_LAYERS
        N_CTX
        N_BATCH
        N_THREADS
        CHAT_WORKERS
        CHAT_FORMAT
        MAX_NEW_TOKENS
        MAX_GENERATION_SECONDS
        ENABLE_STREAMING
        ENABLE_WARMUP
        LLM_SERVER_LOG_LEVEL
        VERBOSE_LLAMACPP
        EMBEDDING_MODEL_NAME
        EMBEDDING_CACHE_SIZE
        EMBEDDING_MAX_BATCH_SIZE
        EMBEDDING_MAX_REQUEST_TEXTS
        EMBEDDING_MAX_TEXT_LENGTH
        EMBEDDING_LOG_LEVEL
        HF_HOME
        SENTENCE_TRANSFORMERS_HOME
        SMART_HOME_PROVIDER
        JARVIS_USE_TLS
        JARVIS_TLS_CA_CERT
        JARVIS_JAVA_TRUSTSTORE
        JARVIS_JAVA_TRUSTSTORE_PASSWORD
        JARVIS_GATEWAY_SSL_ENABLED
        JARVIS_GATEWAY_KEYSTORE_PATH
        JARVIS_GATEWAY_KEYSTORE_PASSWORD
        JARVIS_GATEWAY_KEYSTORE_TYPE
        MANAGEMENT_HEALTH_RABBIT_ENABLED
        MANAGEMENT_HEALTH_KAFKA_ENABLED
    )
    declare -A explicit_overrides=()
    local override_var
    for override_var in "${override_vars[@]}"; do
        if [[ -v "${override_var}" ]]; then
            explicit_overrides["${override_var}"]="${!override_var}"
        fi
    done

    if [[ -f "${LOCAL_ENV_FILE}" ]]; then
        set +e
        set -a
        # shellcheck disable=SC1090
        source "${LOCAL_ENV_FILE}" >/dev/null 2>&1
        local local_env_status=$?
        set +a
        set -e
        if (( local_env_status != 0 )); then
            warn "Existing local env file ${LOCAL_ENV_FILE} had invalid shell syntax or stale values; rewriting it."
        fi
    fi

    if [[ ! -v explicit_overrides[LLM_SERVER_PORT] ]] && [[ "${LLM_SERVER_PORT:-}" == "5000" ]]; then
        LLM_SERVER_PORT="15000"
    fi
    if [[ ! -v explicit_overrides[EMBEDDING_SERVICE_PORT] ]] && [[ "${EMBEDDING_SERVICE_PORT:-}" == "5001" ]]; then
        EMBEDDING_SERVICE_PORT="15001"
    fi
    if [[ ! -v explicit_overrides[LLM_SERVER_URL] ]] && [[ "${LLM_SERVER_URL:-}" == "http://127.0.0.1:5000" ]]; then
        LLM_SERVER_URL="http://127.0.0.1:${LLM_SERVER_PORT:-15000}"
    fi
    if [[ ! -v explicit_overrides[EMBEDDING_SERVICE_URL] ]] && [[ "${EMBEDDING_SERVICE_URL:-}" == "http://127.0.0.1:5001" ]]; then
        EMBEDDING_SERVICE_URL="http://127.0.0.1:${EMBEDDING_SERVICE_PORT:-15001}"
    fi
    if [[ ! -v explicit_overrides[JARVIS_LLM_MODEL_DIR] ]] && [[ "${JARVIS_LLM_MODEL_DIR:-}" == "${ROOT_DIR}/models/llm" ]]; then
        JARVIS_LLM_MODEL_DIR="${JARVIS_MODELS_DIR}/llm"
    fi
    if [[ ! -v explicit_overrides[JARVIS_LLM_MODEL_PATH] ]] && [[ -n "${JARVIS_LLM_MODEL_PATH:-}" ]] &&
        [[ "${JARVIS_LLM_MODEL_PATH}" == "${ROOT_DIR}/models/llm/"* ]]; then
        JARVIS_LLM_MODEL_PATH="${JARVIS_MODELS_DIR}/llm/$(basename "${JARVIS_LLM_MODEL_PATH}")"
    fi
    if [[ ! -v explicit_overrides[EMBEDDING_MODEL_NAME] ]] && [[ "${EMBEDDING_MODEL_NAME:-}" == "intfloat/multilingual-e5-small" ]]; then
        EMBEDDING_MODEL_NAME="${JARVIS_MODELS_DIR}/embeddings/intfloat-multilingual-e5-small"
    fi
    if [[ ! -v explicit_overrides[JARVIS_LLAMACPP_PACKAGE_SPEC] ]] && [[ "${JARVIS_LLAMACPP_PACKAGE_SPEC:-}" == "llama-cpp-python==0.3.4" ]]; then
        JARVIS_LLAMACPP_PACKAGE_SPEC="llama-cpp-python==0.3.19"
    fi
    if [[ ! -v explicit_overrides[N_GPU_LAYERS] ]] && [[ "${N_GPU_LAYERS:-}" == "-1" ]]; then
        N_GPU_LAYERS="0"
    fi

    for override_var in "${!explicit_overrides[@]}"; do
        printf -v "${override_var}" '%s' "${explicit_overrides[${override_var}]}"
    done

    : "${SPRING_DATASOURCE_URL:=jdbc:postgresql://127.0.0.1:${JARVIS_LOCAL_POSTGRES_PORT:-5432}/jarvis}"
    : "${SPRING_DATASOURCE_USERNAME:=jarvis}"
    : "${SPRING_DATASOURCE_PASSWORD:=$(random_secret)}"
    : "${POSTGRES_USER:=${SPRING_DATASOURCE_USERNAME}}"
    : "${POSTGRES_PASSWORD:=${SPRING_DATASOURCE_PASSWORD}}"
    : "${JWT_SECRET:=$(random_secret)}"
    : "${SERVICE_JWT_SECRET:=${JWT_SECRET}}"
    : "${ENABLE_LLM:=false}"
    : "${JARVIS_LLM_ENABLED:=${ENABLE_LLM}}"
    : "${ENABLE_MEMORY:=false}"
    : "${MEMORY_SERVICE_ENABLED:=${ENABLE_MEMORY}}"
    : "${SMART_HOME_PROVIDER:=mock}"
    : "${JARVIS_USE_TLS:=false}"
    : "${JARVIS_TLS_CA_CERT:=$(runtime_local_ca_cert)}"
    : "${JARVIS_JAVA_TRUSTSTORE:=$(runtime_local_java_truststore)}"
    : "${JARVIS_JAVA_TRUSTSTORE_PASSWORD:=changeit}"
    : "${JARVIS_GATEWAY_SSL_ENABLED:=${JARVIS_USE_TLS}}"
    : "${JARVIS_GATEWAY_KEYSTORE_PATH:=$(runtime_local_gateway_keystore)}"
    : "${JARVIS_GATEWAY_KEYSTORE_PASSWORD:=${JARVIS_JAVA_TRUSTSTORE_PASSWORD}}"
    : "${JARVIS_GATEWAY_KEYSTORE_TYPE:=PKCS12}"
    : "${JARVIS_MODELS_DIR:=$(runtime_local_models_dir)}"
    : "${JARVIS_TOOLS_DIR:=$(runtime_local_tools_dir)}"
    : "${JARVIS_VOSK_MODEL_PATH_RU:=$(runtime_local_vosk_model_path ru-RU)}"
    : "${JARVIS_VOSK_MODEL_PATH_EN:=$(runtime_local_vosk_model_path en-US)}"
    : "${JARVIS_VOICE_WHISPER_MODEL_PATH:=$(runtime_local_whisper_model_path)}"
    : "${JARVIS_STT_PROVIDER:=$(default_stt_provider)}"
    : "${TTS_ENABLED:=true}"
    : "${TTS_PROVIDER:=espeak}"
    : "${JARVIS_TTS_ESPEAK_BINARY:=$(runtime_local_espeak_binary)}"
    : "${JARVIS_API_GATEWAY_PORT:=8080}"
    : "${JARVIS_VOICE_GATEWAY_PORT:=8081}"
    : "${JARVIS_NLP_SERVICE_PORT:=8082}"
    : "${JARVIS_ORCHESTRATOR_PORT:=8083}"
    : "${JARVIS_PC_CONTROL_PORT:=8084}"
    : "${JARVIS_LIFE_TRACKER_PORT:=8085}"
    : "${JARVIS_SMART_HOME_PORT:=8086}"
    : "${JARVIS_ANALYTICS_PORT:=8087}"
    : "${JARVIS_SECURITY_PORT:=8088}"
    : "${JARVIS_USER_PROFILE_PORT:=8089}"
    : "${JARVIS_LLM_SERVICE_PORT:=8091}"
    : "${JARVIS_PLANNER_PORT:=8092}"
    : "${JARVIS_MEMORY_SERVICE_PORT:=8093}"
    : "${LLM_SERVER_PORT:=15000}"
    : "${EMBEDDING_SERVICE_PORT:=15001}"
    : "${LLM_SERVER_URL:=$(runtime_local_http_url "${LLM_SERVER_PORT}")}"
    : "${MEMORY_SERVICE_URL:=$(runtime_local_http_url "${JARVIS_MEMORY_SERVICE_PORT}")}"
    : "${EMBEDDING_SERVICE_URL:=$(runtime_local_http_url "${EMBEDDING_SERVICE_PORT}")}"
    : "${JARVIS_LLM_MANAGED_SERVER:=true}"
    : "${JARVIS_CANONICAL_LOCAL_AI_STACK:=$(canonical_local_ai_stack_id)}"
    : "${JARVIS_LLM_MODEL_ID:=$(canonical_local_llm_model_id)}"
    : "${JARVIS_LLM_MODEL_FILENAME:=$(canonical_local_llm_model_filename)}"
    : "${JARVIS_LLM_MODEL_DIR:=$(runtime_local_llm_model_dir)}"
    : "${JARVIS_LLM_MODEL_PATH:=$(runtime_local_llm_model_path)}"
    : "${JARVIS_EMBEDDING_MODEL_ID:=$(canonical_local_embedding_model_id)}"
    : "${JARVIS_EMBEDDING_MODEL_PATH:=$(runtime_local_embedding_model_path)}"
    : "${JARVIS_AI_GPU_STATUS_FILE:=$(runtime_local_ai_gpu_status_file)}"
    : "${JARVIS_LLAMACPP_PACKAGE_SPEC:=llama-cpp-python==0.3.19}"
    : "${JARVIS_LLAMACPP_EXTRA_INDEX_URL:=https://abetlen.github.io/llama-cpp-python/whl/cu124}"
    : "${JARVIS_LLAMACPP_RUNTIME_PACKAGES:=nvidia-cuda-runtime-cu12 nvidia-cublas-cu12}"
    : "${LLM_BACKEND:=llamacpp}"
    : "${N_GPU_LAYERS:=0}"
    : "${N_CTX:=4096}"
    : "${N_BATCH:=512}"
    : "${N_THREADS:=$(default_llm_threads)}"
    : "${CHAT_WORKERS:=1}"
    : "${CHAT_FORMAT:=}"
    : "${MAX_NEW_TOKENS:=512}"
    : "${MAX_GENERATION_SECONDS:=180}"
    : "${ENABLE_STREAMING:=true}"
    : "${ENABLE_WARMUP:=true}"
    : "${LLM_SERVER_LOG_LEVEL:=INFO}"
    : "${VERBOSE_LLAMACPP:=false}"
    : "${EMBEDDING_MODEL_NAME:=${JARVIS_EMBEDDING_MODEL_PATH}}"
    : "${EMBEDDING_CACHE_SIZE:=1000}"
    : "${EMBEDDING_MAX_BATCH_SIZE:=16}"
    : "${EMBEDDING_MAX_REQUEST_TEXTS:=128}"
    : "${EMBEDDING_MAX_TEXT_LENGTH:=512}"
    : "${EMBEDDING_LOG_LEVEL:=INFO}"
    : "${HF_HOME:=${JARVIS_HOME}/cache/huggingface}"
    : "${SENTENCE_TRANSFORMERS_HOME:=${JARVIS_HOME}/cache/sentence-transformers}"
    : "${MANAGEMENT_HEALTH_RABBIT_ENABLED:=false}"
    : "${MANAGEMENT_HEALTH_KAFKA_ENABLED:=false}"

    # Keep the public runtime toggles and the service-specific flags aligned when
    # callers override only one side of the pair (for example ENABLE_LLM=true).
    if [[ -v explicit_overrides[ENABLE_LLM] && ! -v explicit_overrides[JARVIS_LLM_ENABLED] ]]; then
        JARVIS_LLM_ENABLED="${ENABLE_LLM}"
    elif [[ -v explicit_overrides[JARVIS_LLM_ENABLED] && ! -v explicit_overrides[ENABLE_LLM] ]]; then
        ENABLE_LLM="${JARVIS_LLM_ENABLED}"
    fi

    if [[ -v explicit_overrides[ENABLE_MEMORY] && ! -v explicit_overrides[MEMORY_SERVICE_ENABLED] ]]; then
        MEMORY_SERVICE_ENABLED="${ENABLE_MEMORY}"
    elif [[ -v explicit_overrides[MEMORY_SERVICE_ENABLED] && ! -v explicit_overrides[ENABLE_MEMORY] ]]; then
        ENABLE_MEMORY="${MEMORY_SERVICE_ENABLED}"
    fi

    if [[ -v explicit_overrides[JARVIS_USE_TLS] && ! -v explicit_overrides[JARVIS_GATEWAY_SSL_ENABLED] ]]; then
        JARVIS_GATEWAY_SSL_ENABLED="${JARVIS_USE_TLS}"
    elif [[ -v explicit_overrides[JARVIS_GATEWAY_SSL_ENABLED] && ! -v explicit_overrides[JARVIS_USE_TLS] ]]; then
        JARVIS_USE_TLS="${JARVIS_GATEWAY_SSL_ENABLED}"
    fi

    if ! is_truthy "${JARVIS_ALLOW_DISTINCT_LOCAL_SERVICE_JWT_SECRET:-false}"; then
        SERVICE_JWT_SECRET="${JWT_SECRET}"
    fi

    ensure_local_tls_material

    : >"${LOCAL_ENV_FILE}"
    write_env_assignment "${LOCAL_ENV_FILE}" "SPRING_DATASOURCE_URL" "${SPRING_DATASOURCE_URL}"
    write_env_assignment "${LOCAL_ENV_FILE}" "SPRING_DATASOURCE_USERNAME" "${SPRING_DATASOURCE_USERNAME}"
    write_env_assignment "${LOCAL_ENV_FILE}" "SPRING_DATASOURCE_PASSWORD" "${SPRING_DATASOURCE_PASSWORD}"
    write_env_assignment "${LOCAL_ENV_FILE}" "POSTGRES_USER" "${POSTGRES_USER}"
    write_env_assignment "${LOCAL_ENV_FILE}" "POSTGRES_PASSWORD" "${POSTGRES_PASSWORD}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JWT_SECRET" "${JWT_SECRET}"
    write_env_assignment "${LOCAL_ENV_FILE}" "SERVICE_JWT_SECRET" "${SERVICE_JWT_SECRET}"
    write_env_assignment "${LOCAL_ENV_FILE}" "ENABLE_LLM" "${ENABLE_LLM}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLM_ENABLED" "${JARVIS_LLM_ENABLED}"
    write_env_assignment "${LOCAL_ENV_FILE}" "ENABLE_MEMORY" "${ENABLE_MEMORY}"
    write_env_assignment "${LOCAL_ENV_FILE}" "MEMORY_SERVICE_ENABLED" "${MEMORY_SERVICE_ENABLED}"
    write_env_assignment "${LOCAL_ENV_FILE}" "SMART_HOME_PROVIDER" "${SMART_HOME_PROVIDER}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_USE_TLS" "${JARVIS_USE_TLS}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_TLS_CA_CERT" "${JARVIS_TLS_CA_CERT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_JAVA_TRUSTSTORE" "${JARVIS_JAVA_TRUSTSTORE}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_JAVA_TRUSTSTORE_PASSWORD" "${JARVIS_JAVA_TRUSTSTORE_PASSWORD}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_GATEWAY_SSL_ENABLED" "${JARVIS_GATEWAY_SSL_ENABLED}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_GATEWAY_KEYSTORE_PATH" "${JARVIS_GATEWAY_KEYSTORE_PATH}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_GATEWAY_KEYSTORE_PASSWORD" "${JARVIS_GATEWAY_KEYSTORE_PASSWORD}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_GATEWAY_KEYSTORE_TYPE" "${JARVIS_GATEWAY_KEYSTORE_TYPE}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_STT_PROVIDER" "${JARVIS_STT_PROVIDER}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_MODELS_DIR" "${JARVIS_MODELS_DIR}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_TOOLS_DIR" "${JARVIS_TOOLS_DIR}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_VOSK_MODEL_PATH_RU" "${JARVIS_VOSK_MODEL_PATH_RU}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_VOSK_MODEL_PATH_EN" "${JARVIS_VOSK_MODEL_PATH_EN}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_VOICE_WHISPER_MODEL_PATH" "${JARVIS_VOICE_WHISPER_MODEL_PATH}"
    write_env_assignment "${LOCAL_ENV_FILE}" "TTS_ENABLED" "${TTS_ENABLED}"
    write_env_assignment "${LOCAL_ENV_FILE}" "TTS_PROVIDER" "${TTS_PROVIDER}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_TTS_ESPEAK_BINARY" "${JARVIS_TTS_ESPEAK_BINARY}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_API_GATEWAY_PORT" "${JARVIS_API_GATEWAY_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_VOICE_GATEWAY_PORT" "${JARVIS_VOICE_GATEWAY_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_NLP_SERVICE_PORT" "${JARVIS_NLP_SERVICE_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_ORCHESTRATOR_PORT" "${JARVIS_ORCHESTRATOR_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_PC_CONTROL_PORT" "${JARVIS_PC_CONTROL_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LIFE_TRACKER_PORT" "${JARVIS_LIFE_TRACKER_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_SMART_HOME_PORT" "${JARVIS_SMART_HOME_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_ANALYTICS_PORT" "${JARVIS_ANALYTICS_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_SECURITY_PORT" "${JARVIS_SECURITY_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_USER_PROFILE_PORT" "${JARVIS_USER_PROFILE_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLM_SERVICE_PORT" "${JARVIS_LLM_SERVICE_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_PLANNER_PORT" "${JARVIS_PLANNER_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_MEMORY_SERVICE_PORT" "${JARVIS_MEMORY_SERVICE_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "LLM_SERVER_URL" "${LLM_SERVER_URL}"
    write_env_assignment "${LOCAL_ENV_FILE}" "MEMORY_SERVICE_URL" "${MEMORY_SERVICE_URL}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_SERVICE_URL" "${EMBEDDING_SERVICE_URL}"
    write_env_assignment "${LOCAL_ENV_FILE}" "LLM_SERVER_PORT" "${LLM_SERVER_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_SERVICE_PORT" "${EMBEDDING_SERVICE_PORT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLM_MANAGED_SERVER" "${JARVIS_LLM_MANAGED_SERVER}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_CANONICAL_LOCAL_AI_STACK" "${JARVIS_CANONICAL_LOCAL_AI_STACK}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLM_MODEL_ID" "${JARVIS_LLM_MODEL_ID}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLM_MODEL_FILENAME" "${JARVIS_LLM_MODEL_FILENAME}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLM_MODEL_DIR" "${JARVIS_LLM_MODEL_DIR}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLM_MODEL_PATH" "${JARVIS_LLM_MODEL_PATH}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_EMBEDDING_MODEL_ID" "${JARVIS_EMBEDDING_MODEL_ID}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_EMBEDDING_MODEL_PATH" "${JARVIS_EMBEDDING_MODEL_PATH}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_AI_GPU_STATUS_FILE" "${JARVIS_AI_GPU_STATUS_FILE}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLAMACPP_PACKAGE_SPEC" "${JARVIS_LLAMACPP_PACKAGE_SPEC}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLAMACPP_EXTRA_INDEX_URL" "${JARVIS_LLAMACPP_EXTRA_INDEX_URL}"
    write_env_assignment "${LOCAL_ENV_FILE}" "JARVIS_LLAMACPP_RUNTIME_PACKAGES" "${JARVIS_LLAMACPP_RUNTIME_PACKAGES}"
    write_env_assignment "${LOCAL_ENV_FILE}" "LLM_BACKEND" "${LLM_BACKEND}"
    write_env_assignment "${LOCAL_ENV_FILE}" "N_GPU_LAYERS" "${N_GPU_LAYERS}"
    write_env_assignment "${LOCAL_ENV_FILE}" "N_CTX" "${N_CTX}"
    write_env_assignment "${LOCAL_ENV_FILE}" "N_BATCH" "${N_BATCH}"
    write_env_assignment "${LOCAL_ENV_FILE}" "N_THREADS" "${N_THREADS}"
    write_env_assignment "${LOCAL_ENV_FILE}" "CHAT_WORKERS" "${CHAT_WORKERS}"
    write_env_assignment "${LOCAL_ENV_FILE}" "CHAT_FORMAT" "${CHAT_FORMAT}"
    write_env_assignment "${LOCAL_ENV_FILE}" "MAX_NEW_TOKENS" "${MAX_NEW_TOKENS}"
    write_env_assignment "${LOCAL_ENV_FILE}" "MAX_GENERATION_SECONDS" "${MAX_GENERATION_SECONDS}"
    write_env_assignment "${LOCAL_ENV_FILE}" "ENABLE_STREAMING" "${ENABLE_STREAMING}"
    write_env_assignment "${LOCAL_ENV_FILE}" "ENABLE_WARMUP" "${ENABLE_WARMUP}"
    write_env_assignment "${LOCAL_ENV_FILE}" "LLM_SERVER_LOG_LEVEL" "${LLM_SERVER_LOG_LEVEL}"
    write_env_assignment "${LOCAL_ENV_FILE}" "VERBOSE_LLAMACPP" "${VERBOSE_LLAMACPP}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_MODEL_NAME" "${EMBEDDING_MODEL_NAME}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_CACHE_SIZE" "${EMBEDDING_CACHE_SIZE}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_MAX_BATCH_SIZE" "${EMBEDDING_MAX_BATCH_SIZE}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_MAX_REQUEST_TEXTS" "${EMBEDDING_MAX_REQUEST_TEXTS}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_MAX_TEXT_LENGTH" "${EMBEDDING_MAX_TEXT_LENGTH}"
    write_env_assignment "${LOCAL_ENV_FILE}" "EMBEDDING_LOG_LEVEL" "${EMBEDDING_LOG_LEVEL}"
    write_env_assignment "${LOCAL_ENV_FILE}" "HF_HOME" "${HF_HOME}"
    write_env_assignment "${LOCAL_ENV_FILE}" "SENTENCE_TRANSFORMERS_HOME" "${SENTENCE_TRANSFORMERS_HOME}"
    write_env_assignment "${LOCAL_ENV_FILE}" "MANAGEMENT_HEALTH_RABBIT_ENABLED" "${MANAGEMENT_HEALTH_RABBIT_ENABLED}"
    write_env_assignment "${LOCAL_ENV_FILE}" "MANAGEMENT_HEALTH_KAFKA_ENABLED" "${MANAGEMENT_HEALTH_KAFKA_ENABLED}"

    export SPRING_DATASOURCE_URL
    export SPRING_DATASOURCE_USERNAME
    export SPRING_DATASOURCE_PASSWORD
    export POSTGRES_USER
    export POSTGRES_PASSWORD
    export JWT_SECRET
    export SERVICE_JWT_SECRET
    export ENABLE_LLM
    export JARVIS_LLM_ENABLED
    export ENABLE_MEMORY
    export MEMORY_SERVICE_ENABLED
    export SMART_HOME_PROVIDER
    export JARVIS_USE_TLS
    export JARVIS_TLS_CA_CERT
    export JARVIS_JAVA_TRUSTSTORE
    export JARVIS_JAVA_TRUSTSTORE_PASSWORD
    export JARVIS_GATEWAY_SSL_ENABLED
    export JARVIS_GATEWAY_KEYSTORE_PATH
    export JARVIS_GATEWAY_KEYSTORE_PASSWORD
    export JARVIS_GATEWAY_KEYSTORE_TYPE
    export JARVIS_STT_PROVIDER
    export JARVIS_MODELS_DIR
    export JARVIS_TOOLS_DIR
    export JARVIS_VOSK_MODEL_PATH_RU
    export JARVIS_VOSK_MODEL_PATH_EN
    export JARVIS_VOICE_WHISPER_MODEL_PATH
    export TTS_ENABLED
    export TTS_PROVIDER
    export JARVIS_TTS_ESPEAK_BINARY
    export JARVIS_API_GATEWAY_PORT
    export JARVIS_VOICE_GATEWAY_PORT
    export JARVIS_NLP_SERVICE_PORT
    export JARVIS_ORCHESTRATOR_PORT
    export JARVIS_PC_CONTROL_PORT
    export JARVIS_LIFE_TRACKER_PORT
    export JARVIS_SMART_HOME_PORT
    export JARVIS_ANALYTICS_PORT
    export JARVIS_SECURITY_PORT
    export JARVIS_USER_PROFILE_PORT
    export JARVIS_LLM_SERVICE_PORT
    export JARVIS_PLANNER_PORT
    export JARVIS_MEMORY_SERVICE_PORT
    export LLM_SERVER_URL
    export MEMORY_SERVICE_URL
    export EMBEDDING_SERVICE_URL
    export LLM_SERVER_PORT
    export EMBEDDING_SERVICE_PORT
    export JARVIS_LLM_MANAGED_SERVER
    export JARVIS_CANONICAL_LOCAL_AI_STACK
    export JARVIS_LLM_MODEL_ID
    export JARVIS_LLM_MODEL_FILENAME
    export JARVIS_LLM_MODEL_DIR
    export JARVIS_LLM_MODEL_PATH
    export JARVIS_EMBEDDING_MODEL_ID
    export JARVIS_EMBEDDING_MODEL_PATH
    export JARVIS_AI_GPU_STATUS_FILE
    export JARVIS_LLAMACPP_PACKAGE_SPEC
    export JARVIS_LLAMACPP_EXTRA_INDEX_URL
    export JARVIS_LLAMACPP_RUNTIME_PACKAGES
    export LLM_BACKEND
    export N_GPU_LAYERS
    export N_CTX
    export N_BATCH
    export N_THREADS
    export CHAT_WORKERS
    export CHAT_FORMAT
    export MAX_NEW_TOKENS
    export MAX_GENERATION_SECONDS
    export ENABLE_STREAMING
    export ENABLE_WARMUP
    export LLM_SERVER_LOG_LEVEL
    export VERBOSE_LLAMACPP
    export EMBEDDING_MODEL_NAME
    export EMBEDDING_CACHE_SIZE
    export EMBEDDING_MAX_BATCH_SIZE
    export EMBEDDING_MAX_REQUEST_TEXTS
    export EMBEDDING_MAX_TEXT_LENGTH
    export EMBEDDING_LOG_LEVEL
    export HF_HOME
    export SENTENCE_TRANSFORMERS_HOME
    export MANAGEMENT_HEALTH_RABBIT_ENABLED
    export MANAGEMENT_HEALTH_KAFKA_ENABLED
    export JARVIS_RUNTIME_MODE="local"
    export JARVIS_API_BASE_URL="$(runtime_api_base_url)"
    export VOICE_GATEWAY_URL="$(runtime_local_http_url "${JARVIS_VOICE_GATEWAY_PORT}")"
    export NLP_SERVICE_URL="$(runtime_local_http_url "${JARVIS_NLP_SERVICE_PORT}")"
    export ORCHESTRATOR_URL="$(runtime_local_http_url "${JARVIS_ORCHESTRATOR_PORT}")"
    export PC_CONTROL_URL="$(runtime_local_http_url "${JARVIS_PC_CONTROL_PORT}")"
    export LIFE_TRACKER_URL="$(runtime_local_http_url "${JARVIS_LIFE_TRACKER_PORT}")"
    export SMART_HOME_URL="$(runtime_local_http_url "${JARVIS_SMART_HOME_PORT}")"
    export ANALYTICS_URL="$(runtime_local_http_url "${JARVIS_ANALYTICS_PORT}")"
    export SECURITY_URL="$(runtime_local_http_url "${JARVIS_SECURITY_PORT}")"
    export USER_PROFILE_URL="$(runtime_local_http_url "${JARVIS_USER_PROFILE_PORT}")"
    export API_GATEWAY_URL="$(runtime_api_base_url)"
    export PLANNER_URL="$(runtime_local_http_url "${JARVIS_PLANNER_PORT}")"
    export LLM_SERVICE_URL="$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")"
    export JARVIS_ORCHESTRATOR_URL="$(runtime_local_http_url "${JARVIS_ORCHESTRATOR_PORT}")"
    export SPRING_RABBITMQ_USERNAME="${SPRING_RABBITMQ_USERNAME:-jarvis}"
    export SPRING_RABBITMQ_PASSWORD="${SPRING_RABBITMQ_PASSWORD:-jarvis-local-pass}"
    export SPRING_RABBITMQ_HOST="${SPRING_RABBITMQ_HOST:-127.0.0.1}"
    export SPRING_RABBITMQ_PORT="${SPRING_RABBITMQ_PORT:-5672}"
    export USER_PROFILE_ENABLED="true"
    export MEMORY_ENABLED="${ENABLE_MEMORY}"
}

service_pid_file() {
    printf '%s/%s.pid' "${RUNTIME_DIR}" "$1"
}

service_log_file() {
    printf '%s/%s.log' "${LOG_DIR}" "$1"
}

service_jar() {
    printf '%s/apps/%s/target/%s-0.1.0-SNAPSHOT.jar' "${ROOT_DIR}" "$1" "$1"
}

service_health_url() {
    case "$1" in
        security-service) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_SECURITY_PORT}")" ;;
        user-profile) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_USER_PROFILE_PORT}")" ;;
        nlp-service) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_NLP_SERVICE_PORT}")" ;;
        orchestrator) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_ORCHESTRATOR_PORT}")" ;;
        voice-gateway) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_VOICE_GATEWAY_PORT}")" ;;
        pc-control) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_PC_CONTROL_PORT}")" ;;
        api-gateway) printf '%s/actuator/health/readiness' "$(runtime_api_base_url)" ;;
        smart-home-service) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_SMART_HOME_PORT}")" ;;
        life-tracker) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_LIFE_TRACKER_PORT}")" ;;
        analytics-service) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_ANALYTICS_PORT}")" ;;
        planner-service) printf '%s/actuator/health/readiness' "$(runtime_local_http_url "${JARVIS_PLANNER_PORT}")" ;;
        llm-server) printf 'http://127.0.0.1:%s/health' "${LLM_SERVER_PORT}" ;;
        embedding-service) printf 'http://127.0.0.1:%s/health' "${EMBEDDING_SERVICE_PORT}" ;;
        llm-service) printf '%s/api/v1/llm/health' "$(runtime_local_http_url "${JARVIS_LLM_SERVICE_PORT}")" ;;
        memory-service) printf '%s/memory/health' "$(runtime_local_http_url "${JARVIS_MEMORY_SERVICE_PORT}")" ;;
        *)
            return 1
            ;;
    esac
}

service_ready_pattern() {
    case "$1" in
        api-gateway|security-service|user-profile|nlp-service|orchestrator|voice-gateway|pc-control|smart-home-service|life-tracker|analytics-service|planner-service)
            printf '"status"[[:space:]]*:[[:space:]]*"UP"'
            ;;
        llm-server) printf '"status"[[:space:]]*:[[:space:]]*"healthy"' ;;
        embedding-service) printf '"status"[[:space:]]*:[[:space:]]*"healthy"' ;;
        llm-service) printf '"status"[[:space:]]*:[[:space:]]*"healthy"' ;;
        memory-service) printf '"status"[[:space:]]*:[[:space:]]*"healthy"' ;;
        *)
            return 1
            ;;
    esac
}

module_list() {
    local modules=(
        "apps/security-service"
        "apps/user-profile"
        "apps/nlp-service"
        "apps/orchestrator"
        "apps/voice-gateway"
        "apps/pc-control"
        "apps/smart-home-service"
        "apps/life-tracker"
        "apps/analytics-service"
        "apps/api-gateway"
        "apps/planner-service"
    )

    if is_truthy "${ENABLE_LLM:-false}"; then
        modules+=("apps/llm-service")
    fi

    if is_truthy "${ENABLE_MEMORY:-false}"; then
        modules+=("apps/memory-service")
    fi

    local joined=""
    local first="true"
    local module
    for module in "${modules[@]}"; do
        if [[ "${first}" == "true" ]]; then
            joined="${module}"
            first="false"
        else
            joined="${joined},${module}"
        fi
    done
    printf '%s' "${joined}"
}

maybe_build_services() {
    if is_truthy "${JARVIS_SKIP_BUILD:-false}"; then
        log "Skipping Maven package step (JARVIS_SKIP_BUILD=true)."
        return 0
    fi

    log "Packaging local runtime services..."
    (
        cd "${ROOT_DIR}"
        mvn -q -pl "$(module_list)" -am -DskipTests package
    )
}

jdbc_host_port() {
    python3 - "$SPRING_DATASOURCE_URL" <<'PY'
import re
import sys

url = sys.argv[1]
match = re.match(r"jdbc:postgresql://([^/:]+)(?::(\d+))?/([^?]+)", url)
if not match:
    sys.exit(1)

host = match.group(1)
port = match.group(2) or "5432"
db = match.group(3)
print(f"{host} {port} {db}")
PY
}

managed_postgres_matches_target() {
    local host="$1"
    local port="$2"

    [[ "${host}" == "127.0.0.1" || "${host}" == "localhost" ]] || return 1

    docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || return 1

    local mapped_port
    mapped_port="$(
        docker inspect -f '{{with (index .NetworkSettings.Ports "5432/tcp")}}{{(index . 0).HostPort}}{{end}}' \
            "${POSTGRES_CONTAINER}" 2>/dev/null || true
    )"

    [[ -n "${mapped_port}" && "${mapped_port}" == "${port}" ]]
}

managed_postgres_image_matches_target() {
    docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || return 1
    local current_image
    current_image="$(docker inspect -f '{{.Config.Image}}' "${POSTGRES_CONTAINER}" 2>/dev/null || true)"
    [[ "${current_image}" == "${JARVIS_LOCAL_POSTGRES_IMAGE}" ]]
}

ensure_local_postgres_extensions() {
    local db="$1"

    if ! is_truthy "${ENABLE_MEMORY:-false}"; then
        return 0
    fi

    docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || return 0
    docker exec "${POSTGRES_CONTAINER}" psql -U "${POSTGRES_USER}" -d "${db}" \
        -c "CREATE EXTENSION IF NOT EXISTS vector;" \
        -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" >/dev/null 2>&1 \
        || fail "Failed to enable pgvector/pgcrypto in managed PostgreSQL container ${POSTGRES_CONTAINER}"
}

ensure_local_postgres() {
    local host port db
    read -r host port db < <(jdbc_host_port) || fail "Unsupported SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}"

    if [[ "${host}" != "127.0.0.1" && "${host}" != "localhost" ]]; then
        log "Using external PostgreSQL at ${host}:${port}/${db}"
        return 0
    fi

    if docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1 && \
        ! managed_postgres_image_matches_target && \
        is_truthy "${JARVIS_KEEP_LOCAL_POSTGRES_DATA:-false}"; then
        fail "Managed PostgreSQL container ${POSTGRES_CONTAINER} uses image $(docker inspect -f '{{.Config.Image}}' "${POSTGRES_CONTAINER}" 2>/dev/null || echo unknown), but local runtime expects ${JARVIS_LOCAL_POSTGRES_IMAGE}. Re-run with JARVIS_KEEP_LOCAL_POSTGRES_DATA=false or recreate the container."
    fi

    if managed_postgres_matches_target "${host}" "${port}" && ! is_truthy "${JARVIS_KEEP_LOCAL_POSTGRES_DATA:-false}"; then
        if any_runtime_service_running; then
            log "Managed PostgreSQL container ${POSTGRES_CONTAINER} already matches target and Jarvis services are running; preserving the existing local database."
        else
            log "Refreshing managed PostgreSQL container ${POSTGRES_CONTAINER} for a clean local schema..."
            docker rm -f "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
        fi
    fi

    if python3 - "${host}" "${port}" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
sock = socket.socket()
sock.settimeout(0.5)
try:
    sock.connect((host, port))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
    then
        log "PostgreSQL already reachable at ${host}:${port}"
        ensure_local_postgres_extensions "${db}"
        return 0
    fi

    command -v docker >/dev/null 2>&1 || fail "Docker is required to auto-start local PostgreSQL"

    if docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1; then
        if is_truthy "${JARVIS_KEEP_LOCAL_POSTGRES_DATA:-false}"; then
            log "Starting existing PostgreSQL container ${POSTGRES_CONTAINER}..."
            docker start "${POSTGRES_CONTAINER}" >/dev/null
        else
            log "Recreating managed PostgreSQL container ${POSTGRES_CONTAINER} for a clean local schema..."
            docker rm -f "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
            docker run -d \
                --name "${POSTGRES_CONTAINER}" \
                -e POSTGRES_DB="${db}" \
                -e POSTGRES_USER="${POSTGRES_USER}" \
                -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
                -p "${port}:5432" \
                "${JARVIS_LOCAL_POSTGRES_IMAGE}" >/dev/null
        fi
    else
        log "Starting local PostgreSQL container ${POSTGRES_CONTAINER}..."
        docker run -d \
            --name "${POSTGRES_CONTAINER}" \
            -e POSTGRES_DB="${db}" \
            -e POSTGRES_USER="${POSTGRES_USER}" \
            -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
            -p "${port}:5432" \
            "${JARVIS_LOCAL_POSTGRES_IMAGE}" >/dev/null
    fi

    local deadline=$((SECONDS + 60))
    while (( SECONDS < deadline )); do
        if docker exec "${POSTGRES_CONTAINER}" pg_isready -U "${POSTGRES_USER}" -d "${db}" >/dev/null 2>&1; then
            ensure_local_postgres_extensions "${db}"
            log "Local PostgreSQL is ready."
            return 0
        fi
        sleep 1
    done

    fail "Timed out waiting for local PostgreSQL container ${POSTGRES_CONTAINER}"
}

service_is_running() {
    local pid_file
    pid_file="$(service_pid_file "$1")"
    if [[ ! -f "${pid_file}" ]]; then
        return 1
    fi

    local pid
    pid="$(cat "${pid_file}" 2>/dev/null || true)"
    if [[ -z "${pid}" ]]; then
        return 1
    fi

    if kill -0 "${pid}" >/dev/null 2>&1; then
        return 0
    fi

    rm -f "${pid_file}"
    return 1
}

any_runtime_service_running() {
    local service
    for service in "${CORE_SERVICES[@]}" "${OPTIONAL_SERVICES[@]}"; do
        if service_is_running "${service}"; then
            return 0
        fi
    done

    return 1
}

service_bind_port() {
    case "$1" in
        api-gateway) printf '%s' "${JARVIS_API_GATEWAY_PORT}" ;;
        voice-gateway) printf '%s' "${JARVIS_VOICE_GATEWAY_PORT}" ;;
        nlp-service) printf '%s' "${JARVIS_NLP_SERVICE_PORT}" ;;
        orchestrator) printf '%s' "${JARVIS_ORCHESTRATOR_PORT}" ;;
        pc-control) printf '%s' "${JARVIS_PC_CONTROL_PORT}" ;;
        life-tracker) printf '%s' "${JARVIS_LIFE_TRACKER_PORT}" ;;
        smart-home-service) printf '%s' "${JARVIS_SMART_HOME_PORT}" ;;
        analytics-service) printf '%s' "${JARVIS_ANALYTICS_PORT}" ;;
        security-service) printf '%s' "${JARVIS_SECURITY_PORT}" ;;
        user-profile) printf '%s' "${JARVIS_USER_PROFILE_PORT}" ;;
        planner-service) printf '%s' "${JARVIS_PLANNER_PORT}" ;;
        llm-service) printf '%s' "${JARVIS_LLM_SERVICE_PORT}" ;;
        memory-service) printf '%s' "${JARVIS_MEMORY_SERVICE_PORT}" ;;
        llm-server) printf '%s' "${LLM_SERVER_PORT}" ;;
        embedding-service) printf '%s' "${EMBEDDING_SERVICE_PORT}" ;;
        *)
            return 1
            ;;
    esac
}

service_port_override_var() {
    case "$1" in
        api-gateway) printf 'JARVIS_API_GATEWAY_PORT' ;;
        voice-gateway) printf 'JARVIS_VOICE_GATEWAY_PORT' ;;
        nlp-service) printf 'JARVIS_NLP_SERVICE_PORT' ;;
        orchestrator) printf 'JARVIS_ORCHESTRATOR_PORT' ;;
        pc-control) printf 'JARVIS_PC_CONTROL_PORT' ;;
        life-tracker) printf 'JARVIS_LIFE_TRACKER_PORT' ;;
        smart-home-service) printf 'JARVIS_SMART_HOME_PORT' ;;
        analytics-service) printf 'JARVIS_ANALYTICS_PORT' ;;
        security-service) printf 'JARVIS_SECURITY_PORT' ;;
        user-profile) printf 'JARVIS_USER_PROFILE_PORT' ;;
        planner-service) printf 'JARVIS_PLANNER_PORT' ;;
        llm-service) printf 'JARVIS_LLM_SERVICE_PORT' ;;
        memory-service) printf 'JARVIS_MEMORY_SERVICE_PORT' ;;
        llm-server) printf 'LLM_SERVER_PORT' ;;
        embedding-service) printf 'EMBEDDING_SERVICE_PORT' ;;
        *)
            return 1
            ;;
    esac
}

port_is_listening() {
    local port="$1"
    python3 - "${port}" <<'PY'
import socket
import sys

sock = socket.socket()
sock.settimeout(0.3)
try:
    sock.connect(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
}

assert_service_port_available() {
    local service="$1"
    local port
    port="$(service_bind_port "${service}" 2>/dev/null || true)"
    [[ -n "${port}" ]] || return 0

    if port_is_listening "${port}"; then
        local override_var
        override_var="$(service_port_override_var "${service}" 2>/dev/null || true)"
        fail "Port ${port} is already in use before starting ${service}. Stop the existing listener or override ${override_var:-the service port} in ${LOCAL_ENV_FILE}."
    fi
}

launch_detached_process() {
    local log_file="$1"
    shift

    if command -v setsid >/dev/null 2>&1; then
        nohup setsid "$@" < /dev/null >"${log_file}" 2>&1 &
    else
        nohup "$@" < /dev/null >"${log_file}" 2>&1 &
    fi
    printf '%s\n' "$!"
}

start_service() {
    local service="$1"
    local jar
    jar="$(service_jar "${service}")"
    local log_file
    log_file="$(service_log_file "${service}")"
    local pid_file
    pid_file="$(service_pid_file "${service}")"

    if service_is_running "${service}"; then
        log "${service} is already running."
        return 0
    fi

    [[ -f "${jar}" ]] || fail "Missing JAR for ${service}: ${jar}"
    assert_service_port_available "${service}"

    log "Starting ${service}..."
    (
        export JARVIS_RUNTIME_MODE
        export JARVIS_API_BASE_URL
        export JARVIS_USE_TLS
        export JARVIS_TLS_CA_CERT
        export JARVIS_JAVA_TRUSTSTORE
        export JARVIS_JAVA_TRUSTSTORE_PASSWORD
        export JARVIS_GATEWAY_SSL_ENABLED
        export JARVIS_GATEWAY_KEYSTORE_PATH
        export JARVIS_GATEWAY_KEYSTORE_PASSWORD
        export JARVIS_GATEWAY_KEYSTORE_TYPE
        export SPRING_DATASOURCE_URL
        export SPRING_DATASOURCE_USERNAME
        export SPRING_DATASOURCE_PASSWORD
        export JWT_SECRET
        export SERVICE_JWT_SECRET
        export API_GATEWAY_URL
        export VOICE_GATEWAY_URL
        export NLP_SERVICE_URL
        export ORCHESTRATOR_URL
        export SECURITY_URL
        export USER_PROFILE_URL
        export LLM_SERVICE_URL
        export JARVIS_ORCHESTRATOR_URL
        export SPRING_RABBITMQ_USERNAME
        export SPRING_RABBITMQ_PASSWORD
        export SPRING_RABBITMQ_HOST
        export SPRING_RABBITMQ_PORT
        export USER_PROFILE_ENABLED
        export MEMORY_ENABLED
        export JARVIS_STT_PROVIDER
        export JARVIS_MODELS_DIR
        export JARVIS_TOOLS_DIR
        export JARVIS_VOSK_MODEL_PATH_RU
        export JARVIS_VOSK_MODEL_PATH_EN
        export JARVIS_VOICE_WHISPER_MODEL_PATH
        export TTS_ENABLED
        export TTS_PROVIDER
        export JARVIS_TTS_ESPEAK_BINARY
        export JARVIS_API_GATEWAY_PORT
        export JARVIS_VOICE_GATEWAY_PORT
        export JARVIS_NLP_SERVICE_PORT
        export JARVIS_ORCHESTRATOR_PORT
        export JARVIS_PC_CONTROL_PORT
        export JARVIS_LIFE_TRACKER_PORT
        export JARVIS_SMART_HOME_PORT
        export JARVIS_ANALYTICS_PORT
        export JARVIS_SECURITY_PORT
        export JARVIS_USER_PROFILE_PORT
        export JARVIS_LLM_SERVICE_PORT
        export JARVIS_PLANNER_PORT
        export JARVIS_MEMORY_SERVICE_PORT
        export LLM_SERVER_URL
        export MEMORY_SERVICE_URL
        export EMBEDDING_SERVICE_URL
        export MEMORY_SERVICE_ENABLED
        export PC_CONTROL_URL
        export PLANNER_URL
        export MANAGEMENT_HEALTH_RABBIT_ENABLED
        export MANAGEMENT_HEALTH_KAFKA_ENABLED

        case "${service}" in
            voice-gateway)
                export JARVIS_ORCHESTRATOR_URL="$(runtime_local_http_url "${JARVIS_ORCHESTRATOR_PORT}")"
                ;;
            llm-service)
                export JARVIS_LLM_ENABLED="true"
                export USER_PROFILE_ENABLED="true"
                export MEMORY_ENABLED="${ENABLE_MEMORY}"
                export MEMORY_SERVICE_ENABLED="${MEMORY_SERVICE_ENABLED}"
                ;;
            memory-service)
                export MEMORY_ENABLED="true"
                export MEMORY_SERVICE_ENABLED="true"
                ;;
            *)
                :
                ;;
        esac

        export JAVA_TOOL_OPTIONS
        JAVA_TOOL_OPTIONS="$(runtime_java_tool_options "${JAVA_TOOL_OPTIONS:-}")"
        launch_detached_process "${log_file}" java -jar "${jar}" >"${pid_file}"
    )
}

wait_for_service() {
    local service="$1"
    local url
    url="$(service_health_url "${service}")" || fail "No health URL configured for ${service}"
    local timeout="${2:-90}"
    local ready_pattern=""
    ready_pattern="$(service_ready_pattern "${service}" 2>/dev/null || true)"
    local deadline=$((SECONDS + timeout))

    while (( SECONDS < deadline )); do
        local body
        body="$(curl_with_runtime_tls "${url}" -fsS 2>/dev/null || true)"
        if [[ -n "${body}" ]]; then
            if [[ -z "${ready_pattern}" ]] || grep -Eq "${ready_pattern}" <<<"${body}"; then
                log "${service} is healthy."
                return 0
            fi
        fi

        if ! service_is_running "${service}"; then
            warn "${service} exited before becoming healthy."
            tail -n 40 "$(service_log_file "${service}")" >&2 || true
            return 1
        fi
        sleep 1
    done

    warn "${service} did not become healthy within ${timeout}s."
    tail -n 40 "$(service_log_file "${service}")" >&2 || true
    return 1
}

stop_service() {
    local service="$1"
    local pid_file
    pid_file="$(service_pid_file "${service}")"

    if [[ ! -f "${pid_file}" ]]; then
        return 0
    fi

    local pid
    pid="$(cat "${pid_file}" 2>/dev/null || true)"
    rm -f "${pid_file}"

    if [[ -z "${pid}" ]]; then
        return 0
    fi

    if ! kill -0 "${pid}" >/dev/null 2>&1; then
        return 0
    fi

    log "Stopping ${service} (PID ${pid})..."
    kill "${pid}" >/dev/null 2>&1 || true

    local deadline=$((SECONDS + 20))
    while (( SECONDS < deadline )); do
        if ! kill -0 "${pid}" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done

    kill -9 "${pid}" >/dev/null 2>&1 || true
}

stop_managed_postgres() {
    local host port db
    read -r host port db < <(jdbc_host_port) || return 0

    if [[ "${host}" != "127.0.0.1" && "${host}" != "localhost" ]]; then
        return 0
    fi

    if docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1; then
        if is_truthy "${JARVIS_KEEP_LOCAL_POSTGRES_DATA:-false}"; then
            log "Stopping managed PostgreSQL container ${POSTGRES_CONTAINER}..."
            docker stop "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
        else
            log "Removing managed PostgreSQL container ${POSTGRES_CONTAINER}..."
            docker rm -f "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
        fi
    fi
}

write_run_summary() {
    local status="$1"
    cat >"${RUN_SUMMARY}" <<EOF
{
  "timestamp": "$(date -Is)",
  "status": "${status}",
  "apiUrl": "$(runtime_api_base_url)",
  "voiceUrl": "$(runtime_voice_ws_url)",
  "enableLlm": "${ENABLE_LLM}",
  "enableMemory": "${ENABLE_MEMORY}",
  "runtimeMode": "local"
}
EOF
}
