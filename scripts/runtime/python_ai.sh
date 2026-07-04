#!/usr/bin/env bash

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "This script is meant to be sourced." >&2
    exit 1
fi

set -euo pipefail

AI_VENV_ROOT="${JARVIS_HOME}/venvs"
AI_CACHE_ROOT="${JARVIS_HOME}/cache"
AI_BOOTSTRAP_LOG="${LOG_DIR}/python-bootstrap.log"

mkdir -p "${AI_VENV_ROOT}" "${AI_CACHE_ROOT}"

python_service_dir() {
    case "$1" in
        llm-server) printf '%s/apps/llm-server-py' "${ROOT_DIR}" ;;
        embedding-service) printf '%s/apps/embedding-service-py' "${ROOT_DIR}" ;;
        *)
            return 1
            ;;
    esac
}

python_service_venv_dir() {
    printf '%s/%s' "${AI_VENV_ROOT}" "$1"
}

python_service_requirements() {
    case "$1" in
        llm-server) printf '%s/apps/llm-server-py/requirements-local.txt' "${ROOT_DIR}" ;;
        embedding-service) printf '%s/apps/embedding-service-py/requirements-local.txt' "${ROOT_DIR}" ;;
        *)
            return 1
            ;;
    esac
}

python_service_marker() {
    printf '%s/.deps.sha256' "$(python_service_venv_dir "$1")"
}

python_service_fingerprint() {
    local service="$1"
    local requirements
    requirements="$(python_service_requirements "${service}")"
    [[ -f "${requirements}" ]] || fail "Missing requirements file for ${service}: ${requirements}"

    {
        sha256sum "${requirements}"
        python3 --version
        case "${service}" in
            llm-server)
                printf '%s\n' "${JARVIS_LLAMACPP_PACKAGE_SPEC}"
                printf '%s\n' "${JARVIS_LLAMACPP_EXTRA_INDEX_URL}"
                printf '%s\n' "${JARVIS_LLAMACPP_RUNTIME_PACKAGES}"
                ;;
            *)
                :
                ;;
        esac
    } | sha256sum | awk '{print $1}'
}

python_bootstrap_pip() {
    if python3 -m pip --version >/dev/null 2>&1; then
        return 0
    fi

    command -v curl >/dev/null 2>&1 || fail "curl is required to bootstrap the local Python runtime"

    log "Bootstrapping user-space pip for local AI runtime..."

    local bootstrap_dir
    bootstrap_dir="$(mktemp -d)"
    curl -fsSL https://bootstrap.pypa.io/get-pip.py -o "${bootstrap_dir}/get-pip.py" \
        >>"${AI_BOOTSTRAP_LOG}" 2>&1 || fail "Failed to download get-pip.py. See ${AI_BOOTSTRAP_LOG}"
    python3 "${bootstrap_dir}/get-pip.py" --user --break-system-packages \
        >>"${AI_BOOTSTRAP_LOG}" 2>&1 || fail "Failed to install pip in user space. See ${AI_BOOTSTRAP_LOG}"
    rm -rf "${bootstrap_dir}"
}

python_bootstrap_virtualenv() {
    if python3 -m virtualenv --version >/dev/null 2>&1; then
        return 0
    fi

    python_bootstrap_pip
    log "Installing user-space virtualenv for local AI runtime..."
    python3 -m pip install --user --break-system-packages virtualenv \
        >>"${AI_BOOTSTRAP_LOG}" 2>&1 || fail "Failed to install virtualenv. See ${AI_BOOTSTRAP_LOG}"
}

python_create_virtualenv() {
    local venv_dir="$1"

    if [[ -x "${venv_dir}/bin/python" ]]; then
        return 0
    fi

    python_bootstrap_virtualenv
    log "Creating Python environment: ${venv_dir}"
    python3 -m virtualenv "${venv_dir}" >>"${AI_BOOTSTRAP_LOG}" 2>&1 \
        || fail "Failed to create Python environment ${venv_dir}. See ${AI_BOOTSTRAP_LOG}"
}

ensure_python_service_env() {
    local service="$1"
    local venv_dir
    venv_dir="$(python_service_venv_dir "${service}")"
    local marker
    marker="$(python_service_marker "${service}")"
    local desired
    desired="$(python_service_fingerprint "${service}")"
    local current=""
    if [[ -f "${marker}" ]]; then
        current="$(cat "${marker}" 2>/dev/null || true)"
    fi

    python_create_virtualenv "${venv_dir}"

    if [[ "${current}" == "${desired}" ]]; then
        return 0
    fi

    local install_log="${LOG_DIR}/${service}-python-install.log"
    log "Installing Python dependencies for ${service}..."
    "${venv_dir}/bin/python" -m pip install --upgrade pip wheel setuptools \
        >"${install_log}" 2>&1 || fail "Failed to upgrade packaging tools for ${service}. See ${install_log}"
    "${venv_dir}/bin/python" -m pip install --requirement "$(python_service_requirements "${service}")" \
        >>"${install_log}" 2>&1 || fail "Failed to install Python requirements for ${service}. See ${install_log}"

    if [[ "${service}" == "llm-server" ]]; then
        "${venv_dir}/bin/python" -m pip install \
            --extra-index-url "${JARVIS_LLAMACPP_EXTRA_INDEX_URL}" \
            "${JARVIS_LLAMACPP_PACKAGE_SPEC}" \
            >>"${install_log}" 2>&1 || fail "Failed to install llama-cpp-python for ${service}. See ${install_log}"
        if [[ -n "${JARVIS_LLAMACPP_RUNTIME_PACKAGES}" ]]; then
            "${venv_dir}/bin/python" -m pip install ${JARVIS_LLAMACPP_RUNTIME_PACKAGES} \
                >>"${install_log}" 2>&1 || fail "Failed to install CUDA runtime dependencies for ${service}. See ${install_log}"
        fi
    fi

    printf '%s\n' "${desired}" >"${marker}"
}

python_site_packages_dir() {
    local venv_dir="$1"
    "${venv_dir}/bin/python" - <<'PY'
import sysconfig
print(sysconfig.get_paths()["purelib"])
PY
}

llm_cuda_library_path() {
    local venv_dir="$1"
    local site_packages
    site_packages="$(python_site_packages_dir "${venv_dir}")"
    local -a paths=()
    local candidate
    for candidate in \
        "${site_packages}/nvidia/cublas/lib" \
        "${site_packages}/nvidia/cuda_runtime/lib"; do
        if [[ -d "${candidate}" ]]; then
            paths+=("${candidate}")
        fi
    done

    if ((${#paths[@]} == 0)); then
        return 0
    fi

    local joined
    joined="$(IFS=:; printf '%s' "${paths[*]}")"
    printf '%s' "${joined}"
}

resolve_llm_model_path() {
    if ! is_truthy "${ENABLE_LLM:-false}"; then
        return 1
    fi

    if [[ -n "${JARVIS_LLM_MODEL_PATH:-}" ]]; then
        [[ -f "${JARVIS_LLM_MODEL_PATH}" ]] || fail "Configured JARVIS_LLM_MODEL_PATH does not exist: ${JARVIS_LLM_MODEL_PATH}"
        printf '%s' "${JARVIS_LLM_MODEL_PATH}"
        return 0
    fi

    mkdir -p "${JARVIS_LLM_MODEL_DIR}"

    mapfile -t gguf_files < <(find "${JARVIS_LLM_MODEL_DIR}" -maxdepth 1 -type f \( -iname '*.gguf' -o -iname '*.GGUF' \) | sort)

    if ((${#gguf_files[@]} == 1)); then
        printf '%s' "${gguf_files[0]}"
        return 0
    fi

    if ((${#gguf_files[@]} == 0)); then
        fail "No GGUF model was found under ${JARVIS_LLM_MODEL_DIR}. Download one 7B/8B instruct GGUF model and place it there, or set JARVIS_LLM_MODEL_PATH in ${LOCAL_ENV_FILE}."
    fi

    fail "Multiple GGUF models were found under ${JARVIS_LLM_MODEL_DIR}. Set JARVIS_LLM_MODEL_PATH in ${LOCAL_ENV_FILE} to choose one explicitly."
}

wait_for_http_health() {
    local name="$1"
    local url="$2"
    local ready_pattern="${3:-}"
    local timeout="${4:-180}"
    local deadline=$((SECONDS + timeout))

    while ((SECONDS < deadline)); do
        local body
        body="$(curl -fsS "${url}" 2>/dev/null || true)"
        if [[ -n "${body}" ]]; then
            if [[ -z "${ready_pattern}" ]] || grep -q "${ready_pattern}" <<<"${body}"; then
                log "${name} is healthy."
                return 0
            fi
        fi
        sleep 1
    done

    return 1
}

start_python_service() {
    local service="$1"
    local log_file
    log_file="$(service_log_file "${service}")"
    local pid_file
    pid_file="$(service_pid_file "${service}")"

    if service_is_running "${service}"; then
        log "${service} is already running."
        return 0
    fi

    ensure_python_service_env "${service}"

    local venv_dir
    venv_dir="$(python_service_venv_dir "${service}")"
    local service_dir
    service_dir="$(python_service_dir "${service}")"

    assert_service_port_available "${service}"
    log "Starting ${service}..."

    case "${service}" in
        llm-server)
            local model_path
            model_path="$(resolve_llm_model_path)"
            local cuda_library_path=""
            cuda_library_path="$(llm_cuda_library_path "${venv_dir}" 2>/dev/null || true)"
            (
                export PYTHONUNBUFFERED=1
                export HOST="127.0.0.1"
                export PORT="${LLM_SERVER_PORT}"
                export LLM_BACKEND="${LLM_BACKEND}"
                export GGUF_MODEL_PATH="${model_path}"
                export N_GPU_LAYERS="${N_GPU_LAYERS}"
                export N_CTX="${N_CTX}"
                export N_BATCH="${N_BATCH}"
                export N_THREADS="${N_THREADS}"
                export CHAT_WORKERS="${CHAT_WORKERS}"
                export CHAT_FORMAT="${CHAT_FORMAT}"
                export MAX_NEW_TOKENS="${MAX_NEW_TOKENS}"
                export MAX_GENERATION_SECONDS="${MAX_GENERATION_SECONDS}"
                export ENABLE_STREAMING="${ENABLE_STREAMING}"
                export ENABLE_WARMUP="${ENABLE_WARMUP}"
                export LOG_LEVEL="${LLM_SERVER_LOG_LEVEL}"
                export VERBOSE_LLAMACPP="${VERBOSE_LLAMACPP}"
                export HF_HOME="${HF_HOME}"
                if [[ -n "${cuda_library_path}" ]]; then
                    export LD_LIBRARY_PATH="${cuda_library_path}:${LD_LIBRARY_PATH:-}"
                fi
                cd "${service_dir}"
                launch_detached_process "${log_file}" \
                    "${venv_dir}/bin/python" -m uvicorn app.main:app --host 127.0.0.1 --port "${LLM_SERVER_PORT}" \
                    >"${pid_file}"
            )
            ;;
        embedding-service)
            (
                export PYTHONUNBUFFERED=1
                export HOST="127.0.0.1"
                export PORT="${EMBEDDING_SERVICE_PORT}"
                export MODEL_NAME="${EMBEDDING_MODEL_NAME}"
                export CACHE_SIZE="${EMBEDDING_CACHE_SIZE}"
                export MAX_BATCH_SIZE="${EMBEDDING_MAX_BATCH_SIZE}"
                export MAX_REQUEST_TEXTS="${EMBEDDING_MAX_REQUEST_TEXTS}"
                export MAX_TEXT_LENGTH="${EMBEDDING_MAX_TEXT_LENGTH}"
                export LOG_LEVEL="${EMBEDDING_LOG_LEVEL}"
                export HF_HOME="${HF_HOME}"
                export SENTENCE_TRANSFORMERS_HOME="${SENTENCE_TRANSFORMERS_HOME}"
                export TOKENIZERS_PARALLELISM="false"
                cd "${service_dir}"
                launch_detached_process "${log_file}" \
                    "${venv_dir}/bin/python" -m uvicorn app.main:app --host 127.0.0.1 --port "${EMBEDDING_SERVICE_PORT}" \
                    >"${pid_file}"
            )
            ;;
        *)
            fail "Unsupported Python service: ${service}"
            ;;
    esac
}
