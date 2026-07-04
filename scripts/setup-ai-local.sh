#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

QWEN_GGUF_URL="${JARVIS_LLM_MODEL_DOWNLOAD_URL:-https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf?download=true}"

export ENABLE_LLM="true"
export JARVIS_LLM_ENABLED="true"
export ENABLE_MEMORY="true"
export MEMORY_SERVICE_ENABLED="true"
export JARVIS_LLM_MANAGED_SERVER="true"
export JARVIS_CANONICAL_LOCAL_AI_STACK="$(canonical_local_ai_stack_id)"
export JARVIS_LLM_MODEL_ID="$(canonical_local_llm_model_id)"
export JARVIS_LLM_MODEL_FILENAME="$(canonical_local_llm_model_filename)"
export JARVIS_LLM_MODEL_DIR="$(runtime_local_llm_model_dir)"
export JARVIS_LLM_MODEL_PATH="$(runtime_local_llm_model_path)"
export JARVIS_EMBEDDING_MODEL_ID="$(canonical_local_embedding_model_id)"
export JARVIS_EMBEDDING_MODEL_PATH="$(runtime_local_embedding_model_path)"
export N_GPU_LAYERS="${N_GPU_LAYERS:-0}"
export EMBEDDING_MODEL_NAME="${JARVIS_EMBEDDING_MODEL_PATH}"

ensure_local_env

require_command() {
    local command_name="$1"
    command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
}

ensure_parent_dir() {
    local target="$1"
    mkdir -p "$(dirname "${target}")"
}

maybe_link_legacy_file() {
    local legacy_path="$1"
    local canonical_path="$2"
    local label="$3"

    if [[ -e "${canonical_path}" ]]; then
        return 0
    fi

    if [[ -e "${legacy_path}" ]]; then
        ensure_parent_dir "${canonical_path}"
        ln -s "${legacy_path}" "${canonical_path}"
        log "Linked legacy ${label}: ${canonical_path} -> ${legacy_path}"
    fi
}

maybe_link_legacy_llm_model() {
    if [[ -e "${JARVIS_LLM_MODEL_PATH}" ]]; then
        return 0
    fi

    maybe_link_legacy_file \
        "${ROOT_DIR}/models/llm/${JARVIS_LLM_MODEL_FILENAME}" \
        "${JARVIS_LLM_MODEL_PATH}" \
        "LLM model"

    if [[ -e "${JARVIS_LLM_MODEL_PATH}" ]]; then
        return 0
    fi

    mapfile -t legacy_models < <(find "${ROOT_DIR}/models/llm" -maxdepth 1 -type f -iname '*.gguf' 2>/dev/null | sort)
    if ((${#legacy_models[@]} == 1)); then
        ensure_parent_dir "${JARVIS_LLM_MODEL_PATH}"
        ln -s "${legacy_models[0]}" "${JARVIS_LLM_MODEL_PATH}"
        log "Linked legacy LLM model: ${JARVIS_LLM_MODEL_PATH} -> ${legacy_models[0]}"
    fi
}

download_llm_model() {
    if [[ -f "${JARVIS_LLM_MODEL_PATH}" ]]; then
        log "Canonical LLM model already present at ${JARVIS_LLM_MODEL_PATH}"
        return 0
    fi

    ensure_parent_dir "${JARVIS_LLM_MODEL_PATH}"
    local partial_path="${JARVIS_LLM_MODEL_PATH}.part"
    log "Downloading canonical LLM model ${JARVIS_LLM_MODEL_ID} -> ${JARVIS_LLM_MODEL_PATH}"
    curl -fL --retry 3 --continue-at - --output "${partial_path}" "${QWEN_GGUF_URL}"
    mv "${partial_path}" "${JARVIS_LLM_MODEL_PATH}"
    log "Installed canonical LLM model at ${JARVIS_LLM_MODEL_PATH}"
}

verify_llm_runtime_dependencies() {
    ensure_python_service_env "llm-server"
    local venv_dir
    venv_dir="$(python_service_venv_dir "llm-server")"
    local cuda_library_path=""
    cuda_library_path="$(llm_cuda_library_path "${venv_dir}" 2>/dev/null || true)"
    if [[ -n "${cuda_library_path}" ]]; then
        LD_LIBRARY_PATH="${cuda_library_path}:${LD_LIBRARY_PATH:-}" \
            "${venv_dir}/bin/python" - <<'PY'
from llama_cpp import Llama  # noqa: F401
print("llama-cpp-python import OK")
PY
        return 0
    fi

    "${venv_dir}/bin/python" - <<'PY'
from llama_cpp import Llama  # noqa: F401
print("llama-cpp-python import OK")
PY
}

download_embedding_model() {
    # Phase 3 / SPEC-1: automatic model downloads are FORBIDDEN.
    # This function used to call huggingface_hub for snapshot fetching. It
    # now only verifies the embedding model is present and prints explicit
    # manual-placement instructions if it isn't.
    if [[ -f "${JARVIS_EMBEDDING_MODEL_PATH}/config.json" && -f "${JARVIS_EMBEDDING_MODEL_PATH}/model.safetensors" ]]; then
        log "Embedding model already present at ${JARVIS_EMBEDDING_MODEL_PATH}"
        return 0
    fi

    cat <<EOF >&2

❌ Embedding model not found at ${JARVIS_EMBEDDING_MODEL_PATH}

SPEC-1 forbids automatic model downloads. Place the model manually:

  1. From a machine with internet, download the files for
     ${JARVIS_EMBEDDING_MODEL_ID}
     (config.json, model.safetensors, tokenizer.json, sentencepiece.bpe.model,
      modules.json, sentence_bert_config.json, special_tokens_map.json,
      tokenizer_config.json, 1_Pooling/config.json, README.md).

  2. Copy the directory to:
     ${JARVIS_EMBEDDING_MODEL_PATH}

  3. Re-run this script.

EOF
    return 1
}

verify_embedding_model() {
    local venv_dir
    venv_dir="$(python_service_venv_dir "embedding-service")"
    "${venv_dir}/bin/python" - "${JARVIS_EMBEDDING_MODEL_PATH}" <<'PY'
import sys
from sentence_transformers import SentenceTransformer

model = SentenceTransformer(sys.argv[1])
vector = model.encode("query: verify", normalize_embeddings=True)
print(f"embedding-dim={len(vector)}")
PY
}

ensure_postgres_image() {
    require_local_container_engine
    if "${JARVIS_LOCAL_CONTAINER_ENGINE}" image exists "${JARVIS_LOCAL_POSTGRES_IMAGE}" >/dev/null 2>&1; then
        log "PostgreSQL image already present: ${JARVIS_LOCAL_POSTGRES_IMAGE}"
        return 0
    fi

    log "Pulling PostgreSQL image ${JARVIS_LOCAL_POSTGRES_IMAGE} with ${JARVIS_LOCAL_CONTAINER_ENGINE}"
    "${JARVIS_LOCAL_CONTAINER_ENGINE}" pull "${JARVIS_LOCAL_POSTGRES_IMAGE}" >/dev/null
}

require_command curl
require_command python3
require_command java
require_command mvn

mkdir -p "${JARVIS_LLM_MODEL_DIR}" "${JARVIS_EMBEDDING_MODEL_PATH}"

maybe_link_legacy_llm_model
download_llm_model
verify_llm_runtime_dependencies
download_embedding_model
verify_embedding_model
ensure_postgres_image
ensure_local_env

printf 'Canonical AI stack: %s\n' "${JARVIS_CANONICAL_LOCAL_AI_STACK}"
printf 'LLM provider: %s\n' "${LLM_BACKEND}"
printf 'llama-cpp-python spec: %s\n' "${JARVIS_LLAMACPP_PACKAGE_SPEC}"
printf 'LLM model id: %s\n' "${JARVIS_LLM_MODEL_ID}"
printf 'LLM model path: %s\n' "${JARVIS_LLM_MODEL_PATH}"
printf 'Embedding model id: %s\n' "${JARVIS_EMBEDDING_MODEL_ID}"
printf 'Embedding model path: %s\n' "${JARVIS_EMBEDDING_MODEL_PATH}"
printf 'GPU status file: %s\n' "${JARVIS_AI_GPU_STATUS_FILE}"
printf 'LLM server venv: %s\n' "$(python_service_venv_dir "llm-server")"
printf 'Embedding server venv: %s\n' "$(python_service_venv_dir "embedding-service")"
printf 'PostgreSQL image: %s\n' "${JARVIS_LOCAL_POSTGRES_IMAGE}"
printf 'Local env: %s\n' "${LOCAL_ENV_FILE}"
printf '\nNext steps:\n'
printf '  1. ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh\n'
printf '  2. ./scripts/runtime-status.sh\n'
printf '  3. ./scripts/ai-local-smoke.sh\n'
printf '  4. ./scripts/ai-gpu-smoke.sh\n'

log "Canonical local AI stack bootstrap is ready."
