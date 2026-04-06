#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

VOSK_MODEL_RU_URL="${VOSK_MODEL_RU_URL:-https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip}"
VOSK_MODEL_EN_URL="${VOSK_MODEL_EN_URL:-https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip}"

ESPEAK_PREFIX_DIR="${JARVIS_TOOLS_DIR}/espeak-ng"
ESPEAK_ROOT_DIR="${ESPEAK_PREFIX_DIR}/root"
ESPEAK_WRAPPER_PATH="$(runtime_local_espeak_binary)"

export JARVIS_MODELS_DIR="$(runtime_local_models_dir)"
export JARVIS_TOOLS_DIR="$(runtime_local_tools_dir)"
export JARVIS_STT_PROVIDER="vosk"
export TTS_ENABLED="true"
export TTS_PROVIDER="espeak"
export JARVIS_VOSK_MODEL_PATH_RU="$(runtime_local_vosk_model_path ru-RU)"
export JARVIS_VOSK_MODEL_PATH_EN="$(runtime_local_vosk_model_path en-US)"
export JARVIS_VOICE_WHISPER_MODEL_PATH="$(runtime_local_whisper_model_path)"
export JARVIS_TTS_ESPEAK_BINARY="${ESPEAK_WRAPPER_PATH}"

ensure_local_env

require_command() {
    local command_name="$1"
    command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
}

ensure_parent_dir() {
    local target="$1"
    mkdir -p "$(dirname "${target}")"
}

maybe_link_legacy_path() {
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

download_and_extract_zip() {
    local url="$1"
    local target_dir="$2"
    local label="$3"

    local tmp_dir archive extract_dir extracted_root
    tmp_dir="$(mktemp -d)"
    archive="${tmp_dir}/$(basename "${url}")"
    extract_dir="${tmp_dir}/extract"
    mkdir -p "${extract_dir}"

    log "Downloading ${label} from ${url}"
    curl -fsSL "${url}" -o "${archive}"

    python3 - "${archive}" "${extract_dir}" <<'PY'
import sys
import zipfile

archive, extract_dir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(archive) as zf:
    zf.extractall(extract_dir)
PY

    extracted_root="$(find "${extract_dir}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    [[ -n "${extracted_root}" ]] || fail "Downloaded archive for ${label} did not contain an extractable model directory"

    rm -rf "${target_dir}"
    ensure_parent_dir "${target_dir}"
    mv "${extracted_root}" "${target_dir}"
    rm -rf "${tmp_dir}"
    log "Installed ${label} at ${target_dir}"
}

ensure_vosk_model() {
    local label="$1"
    local url="$2"
    local target_dir="$3"

    if [[ -f "${target_dir}/conf/model.conf" ]]; then
        log "${label} already present at ${target_dir}"
        return 0
    fi

    download_and_extract_zip "${url}" "${target_dir}" "${label}"
}

download_and_extract_deb() {
    local package_name="$1"
    local target_root="$2"

    local tmp_dir deb_file
    tmp_dir="$(mktemp -d)"
    (
        cd "${tmp_dir}"
        apt download "${package_name}" >/dev/null 2>&1
    )
    deb_file="$(find "${tmp_dir}" -maxdepth 1 -name '*.deb' | head -n 1)"
    [[ -n "${deb_file}" ]] || fail "Failed to download ${package_name} with apt"
    dpkg-deb -x "${deb_file}" "${target_root}"
    rm -rf "${tmp_dir}"
}

ensure_rootless_espeak_bundle() {
    mkdir -p "${ESPEAK_ROOT_DIR}"
    local package_name marker_path
    while IFS='|' read -r package_name marker_path; do
        if [[ -e "${marker_path}" ]]; then
            log "${package_name} already present under ${ESPEAK_ROOT_DIR}"
            continue
        fi
        log "Installing ${package_name} into ${ESPEAK_ROOT_DIR}"
        download_and_extract_deb "${package_name}" "${ESPEAK_ROOT_DIR}"
    done <<EOF
espeak-ng|${ESPEAK_ROOT_DIR}/usr/bin/espeak-ng
espeak-ng-data|${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu/espeak-ng-data
libespeak-ng1|${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu/libespeak-ng.so.1
libpcaudio0|${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu/libpcaudio.so.0
libsonic0|${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu/libsonic.so.0
EOF

    while IFS='|' read -r optional_pkg optional_marker; do
        if dpkg-query -W -f='${Status}\n' "${optional_pkg}" 2>/dev/null | grep -q 'install ok installed'; then
            continue
        fi
        if [[ -e "${optional_marker}" ]]; then
            continue
        fi
        log "Installing optional runtime library ${optional_pkg} into ${ESPEAK_ROOT_DIR}"
        download_and_extract_deb "${optional_pkg}" "${ESPEAK_ROOT_DIR}"
    done <<EOF
libpulse0|${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu/libpulse.so.0
libasound2t64|${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu/libasound.so.2
EOF
}

write_espeak_wrapper() {
    local wrapper_path="${ESPEAK_WRAPPER_PATH}"
    local wrapper_dir
    wrapper_dir="$(dirname "${wrapper_path}")"
    mkdir -p "${wrapper_dir}"

    cat >"${wrapper_path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
ESPEAK_ROOT_DIR="${ESPEAK_ROOT_DIR}"
BIN_PATH="\${ESPEAK_ROOT_DIR}/usr/bin/espeak-ng"
LIB_DIR="\${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu"
DATA_DIR="\${ESPEAK_ROOT_DIR}/usr/lib/x86_64-linux-gnu/espeak-ng-data"

if [[ -d "\${LIB_DIR}" ]]; then
    export LD_LIBRARY_PATH="\${LIB_DIR}\${LD_LIBRARY_PATH:+:\${LD_LIBRARY_PATH}}"
fi
if [[ -d "\${DATA_DIR}" ]]; then
    export ESPEAK_DATA_PATH="\${DATA_DIR}"
fi

exec "\${BIN_PATH}" "\$@"
EOF
    chmod +x "${wrapper_path}"
}

verify_espeak_wrapper() {
    [[ -x "${ESPEAK_WRAPPER_PATH}" ]] || fail "Expected executable wrapper at ${ESPEAK_WRAPPER_PATH}"
    "${ESPEAK_WRAPPER_PATH}" --version >/dev/null
}

require_command curl
require_command python3
require_command apt
require_command dpkg-deb

mkdir -p "${JARVIS_MODELS_DIR}/stt/vosk" "${JARVIS_TOOLS_DIR}"

maybe_link_legacy_path "${JARVIS_HOME}/models/vosk/vosk-model-small-ru-0.22" "${JARVIS_VOSK_MODEL_PATH_RU}" "Vosk RU model"
maybe_link_legacy_path "${JARVIS_HOME}/models/vosk/vosk-model-small-en-us-0.15" "${JARVIS_VOSK_MODEL_PATH_EN}" "Vosk EN model"
maybe_link_legacy_path "${JARVIS_HOME}/models/whisper/ggml-small.bin" "${JARVIS_VOICE_WHISPER_MODEL_PATH}" "Whisper model"

ensure_vosk_model "Vosk RU model" "${VOSK_MODEL_RU_URL}" "${JARVIS_VOSK_MODEL_PATH_RU}"
ensure_vosk_model "Vosk EN model" "${VOSK_MODEL_EN_URL}" "${JARVIS_VOSK_MODEL_PATH_EN}"
ensure_rootless_espeak_bundle
write_espeak_wrapper
verify_espeak_wrapper
ensure_local_env

printf 'Canonical voice stack: %s\n' "$(canonical_local_voice_stack_id)"
printf 'STT provider: %s\n' "${JARVIS_STT_PROVIDER}"
printf 'TTS provider: %s\n' "${TTS_PROVIDER}"
printf 'Vosk RU model: %s\n' "${JARVIS_VOSK_MODEL_PATH_RU}"
printf 'Vosk EN model: %s\n' "${JARVIS_VOSK_MODEL_PATH_EN}"
printf 'eSpeak binary: %s\n' "${JARVIS_TTS_ESPEAK_BINARY}"
printf 'Voice readiness: %s\n' "$(voice_readiness_status)"

if [[ "$(voice_readiness_status)" != "full-audio ready" ]]; then
    fail "Local voice stack is still not ready after setup"
fi

log "Canonical local voice stack is ready."
