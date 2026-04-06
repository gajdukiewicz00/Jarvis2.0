#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Launcher Wrapper Script (Product)
# =============================================================================
# Wrapper for JavaFX launcher JAR.
# Ensures proper Java environment and logging.
# Used by desktop entry to launch Jarvis without terminal.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CURRENT_SCRIPT="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || printf '%s/%s' "${SCRIPT_DIR}" "$(basename "${BASH_SOURCE[0]}")")"
JARVIS_HOME="${HOME}/.jarvis"
JARVIS_APP="${JARVIS_HOME}/app"
RELEASE_SOURCE_FILE="${JARVIS_APP}/RELEASE_SOURCE"
LOG_DIR="${JARVIS_HOME}/logs"
LOG="${LOG_DIR}/launcher-start.log"

is_valid_repo_root() {
    local root="${1:-}"
    [[ -n "${root}" ]] || return 1
    [[ -f "${root}/pom.xml" ]] && [[ -d "${root}/apps" ]] && [[ -f "${root}/jarvis-launch.sh" ]]
}

candidate_repo_roots() {
    local script_root
    script_root="$(cd "${SCRIPT_DIR}/../.." && pwd)"

    if [[ -n "${JARVIS_PROJECT_ROOT:-}" ]]; then
        printf '%s\n' "${JARVIS_PROJECT_ROOT}"
    fi
    if [[ -f "${RELEASE_SOURCE_FILE}" ]]; then
        local release_source
        release_source="$(<"${RELEASE_SOURCE_FILE}")"
        if [[ -n "${release_source}" && "${release_source}" != "REPO" ]]; then
            printf '%s\n' "${release_source}"
        fi
    fi

    printf '%s\n' "${script_root}"
    printf '%s\n' "${HOME}/Jarvis/Jarvis2.0"
    printf '%s\n' "${HOME}/IdeaProjects/Jarvis2.0"
    printf '%s\n' "${HOME}/Projects/Jarvis2.0"
    printf '%s\n' "${HOME}/Jarvis2.0"
}

resolve_repo_source_root() {
    local candidate
    while IFS= read -r candidate; do
        [[ -n "${candidate}" ]] || continue
        if is_valid_repo_root "${candidate}"; then
            printf '%s' "${candidate}"
            return 0
        fi
    done < <(candidate_repo_roots | awk '!seen[$0]++')
    return 1
}

maybe_reexec_to_repo_wrapper() {
    [[ "${JARVIS_LAUNCHER_REEXEC_GUARD:-0}" == "1" ]] && return 0

    local repo_root repo_wrapper repo_wrapper_real
    repo_root="$(resolve_repo_source_root || true)"
    [[ -n "${repo_root}" ]] || return 0

    repo_wrapper="${repo_root}/scripts/product/jarvis-launcher.sh"
    [[ -f "${repo_wrapper}" ]] || return 0

    repo_wrapper_real="$(readlink -f "${repo_wrapper}" 2>/dev/null || printf '%s' "${repo_wrapper}")"
    [[ "${repo_wrapper_real}" == "${CURRENT_SCRIPT}" ]] && return 0

    export JARVIS_PROJECT_ROOT="${repo_root}"
    export JARVIS_LAUNCHER_REEXEC_GUARD=1
    exec "${repo_wrapper}" "$@"
}

maybe_reexec_to_repo_wrapper "$@"

# Lock file to prevent double launch
LOCK_FILE="${JARVIS_HOME}/run/launcher.lock"
mkdir -p "${JARVIS_HOME}/run" "${LOG_DIR}"

# Try to acquire lock (non-blocking)
exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
    # Another launcher is already running
    exit 0
fi
# Lock acquired, will be released on exit

# Logging function
log() {
    echo "[$(date -Is)] $*" >> "$LOG" 2>&1 || true
}

# Notification function (GUI error)
notify() {
    local msg="$1"
    log "NOTIFY: $msg"
    command -v notify-send >/dev/null 2>&1 && notify-send "Jarvis 2.0" "$msg" --icon=error 2>/dev/null || true
    command -v zenity >/dev/null 2>&1 && zenity --error --text="$msg" --title="Jarvis 2.0" 2>/dev/null || true
}

resolve_maven_bin() {
    local mvn_bin="${MAVEN_BIN:-}"
    if [[ -n "${mvn_bin}" && -x "${mvn_bin}" ]]; then
        printf '%s' "${mvn_bin}"
        return 0
    fi

    mvn_bin="$(command -v mvn 2>/dev/null || true)"
    if [[ -n "${mvn_bin}" && -x "${mvn_bin}" ]]; then
        printf '%s' "${mvn_bin}"
        return 0
    fi

    for candidate in /usr/bin/mvn /usr/local/bin/mvn /opt/maven/bin/mvn; do
        if [[ -x "${candidate}" ]]; then
            printf '%s' "${candidate}"
            return 0
        fi
    done

    return 1
}

sync_file_if_needed() {
    local source="$1"
    local target="$2"
    [[ -f "${source}" ]] || return 0

    local source_real target_real
    source_real="$(readlink -f "${source}" 2>/dev/null || printf '%s' "${source}")"
    target_real="$(readlink -f "${target}" 2>/dev/null || printf '%s' "${target}")"
    [[ "${source_real}" == "${target_real}" ]] && return 0

    mkdir -p "$(dirname "${target}")"
    if [[ ! -f "${target}" ]] || ! cmp -s "${source}" "${target}"; then
        cp "${source}" "${target}"
        log "Synced $(basename "${target}") from ${source}"
    fi
}

sync_executable_if_needed() {
    local source="$1"
    local target="$2"
    sync_file_if_needed "${source}" "${target}"
    [[ -f "${target}" ]] && chmod +x "${target}" 2>/dev/null || true
}

jar_is_stale() {
    local jar="$1"
    shift

    [[ -f "${jar}" ]] || return 0

    local path
    for path in "$@"; do
        [[ -e "${path}" ]] || continue
        if [[ -d "${path}" ]]; then
            if find "${path}" -type f -newer "${jar}" -print -quit 2>/dev/null | grep -q .; then
                return 0
            fi
        elif [[ "${path}" -nt "${jar}" ]]; then
            return 0
        fi
    done

    return 1
}

resolve_last_run_api_url() {
    local summary="${HOME}/.jarvis/run/last-run.json"
    [[ -f "${summary}" ]] || return 1

    local runtime_mode api_url
    runtime_mode="$(grep -oE '"runtimeMode"[[:space:]]*:[[:space:]]*"[^"]+"' "${summary}" | sed -E 's/.*"([^"]+)"/\1/' | head -1)"
    api_url="$(grep -oE '"apiUrl"[[:space:]]*:[[:space:]]*"[^"]+"' "${summary}" | sed -E 's/.*"([^"]+)"/\1/' | head -1)"

    [[ "${runtime_mode}" == "local" ]] || return 1
    [[ -n "${api_url}" ]] || return 1

    printf '%s' "${api_url%/}"
}

resolve_local_runtime_api_url_from_env_file() {
    local env_file="${HOME}/.jarvis/run/local-runtime/local.env"
    [[ -f "${env_file}" ]] || return 1

    (
        set -a
        # shellcheck disable=SC1090
        source "${env_file}" >/dev/null 2>&1 || exit 1
        local scheme="http"
        if [[ "${JARVIS_USE_TLS:-false}" == "true" ]]; then
            scheme="https"
        fi
        printf '%s://127.0.0.1:%s' "${scheme}" "${JARVIS_API_GATEWAY_PORT:-8080}"
    )
}

resolve_local_runtime_api_url() {
    resolve_local_runtime_api_url_from_env_file || \
        resolve_last_run_api_url || \
        printf 'http://127.0.0.1:8080'
}

ensure_repo_gui_artifacts() {
    local repo_root="$1"
    [[ -n "${repo_root}" ]] || return 0
    is_valid_repo_root "${repo_root}" || return 0

    local launcher_target="${repo_root}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
    local desktop_shell_target="${repo_root}/apps/desktop-app-javafx/target/desktop-app-javafx-0.1.0-SNAPSHOT.jar"
    local desktop_legacy_target="${repo_root}/apps/desktop-client-javafx/target/desktop-client-javafx-0.1.0-SNAPSHOT.jar"
    local launcher_stale="false"
    local desktop_shell_stale="false"

    if jar_is_stale "${launcher_target}" \
        "${repo_root}/pom.xml" \
        "${repo_root}/apps/launcher-javafx/pom.xml" \
        "${repo_root}/apps/launcher-javafx/src"; then
        launcher_stale="true"
    fi

    if jar_is_stale "${desktop_shell_target}" \
        "${repo_root}/pom.xml" \
        "${repo_root}/apps/desktop-app-javafx/pom.xml" \
        "${repo_root}/apps/desktop-app-javafx/src" \
        "${repo_root}/apps/desktop-client-javafx/pom.xml" \
        "${repo_root}/apps/desktop-client-javafx/src"; then
        desktop_shell_stale="true"
    fi

    if [[ "${launcher_stale}" == "true" || "${desktop_shell_stale}" == "true" ]]; then
        local mvn_bin
        mvn_bin="$(resolve_maven_bin || true)"
        if [[ -z "${mvn_bin}" || ! -x "${mvn_bin}" ]]; then
            log "ERROR: Maven not found; cannot refresh GUI artifacts from ${repo_root}"
            notify "Не найден Maven. GUI launcher не может пересобрать актуальные launcher/desktop shell артефакты без терминала."
            exit 1
        fi

        log "GUI sources are newer than packaged artifacts; rebuilding launcher + desktop shell from ${repo_root}"
        if ! (
            cd "${repo_root}" &&
                "${mvn_bin}" -q -pl apps/launcher-javafx,apps/desktop-app-javafx -am -DskipTests package
        ) >>"${LOG}" 2>&1; then
            log "ERROR: GUI rebuild failed for ${repo_root}"
            notify "Jarvis не смог пересобрать актуальные launcher/desktop shell артефакты. Проверь ${LOG}."
            exit 1
        fi
    fi

    [[ -f "${launcher_target}" ]] || {
        log "ERROR: Missing launcher JAR after refresh: ${launcher_target}"
        notify "Не найден launcher JAR после обновления: ${launcher_target}"
        exit 1
    }
    [[ -f "${desktop_shell_target}" ]] || {
        log "ERROR: Missing desktop shell JAR after refresh: ${desktop_shell_target}"
        notify "Не найден desktop shell JAR после обновления: ${desktop_shell_target}"
        exit 1
    }

    sync_file_if_needed "${launcher_target}" "${JARVIS_APP}/launcher.jar"
    sync_file_if_needed "${desktop_shell_target}" "${JARVIS_APP}/desktop-app-javafx-0.1.0-SNAPSHOT.jar"
    if [[ -f "${desktop_legacy_target}" ]]; then
        sync_file_if_needed "${desktop_legacy_target}" "${JARVIS_APP}/desktop-client-javafx-0.1.0-SNAPSHOT.jar"
    fi
    sync_executable_if_needed "${repo_root}/scripts/product/jarvis-launcher.sh" "${JARVIS_APP}/bin/jarvis-launcher.sh"
    if [[ -f "${repo_root}/apps/launcher-javafx/src/main/resources/logback.xml" ]]; then
        sync_file_if_needed \
            "${repo_root}/apps/launcher-javafx/src/main/resources/logback.xml" \
            "${JARVIS_APP}/config/logback.xml"
    fi
}

log "=========================================="
log "Jarvis Launcher start requested"
log "User: $(whoami)"
log "Home: ${HOME}"
log "PWD: $(pwd)"

# Avoid spawning multiple launcher instances
LAUNCHER_PID_FILE="${JARVIS_HOME}/run/launcher.pid"
if [[ -f "${LAUNCHER_PID_FILE}" ]]; then
    existing_pid="$(cat "${LAUNCHER_PID_FILE}" 2>/dev/null || true)"
    if [[ -n "${existing_pid}" ]] && kill -0 "${existing_pid}" >/dev/null 2>&1; then
        log "Launcher already running (PID: ${existing_pid})"
        exit 0
    fi
    rm -f "${LAUNCHER_PID_FILE}" >/dev/null 2>&1 || true
fi

# 1) Find Java
JAVA_BIN="${JAVA_BIN:-}"
if [[ -z "${JAVA_BIN}" ]]; then
    JAVA_BIN="$(command -v java 2>/dev/null || true)"
fi

# Try common locations if not in PATH
if [[ -z "${JAVA_BIN}" ]] || [[ ! -x "${JAVA_BIN}" ]]; then
    for java_path in \
        "${JAVA_HOME:-}/bin/java" \
        "/usr/lib/jvm/java-21-openjdk-amd64/bin/java" \
        "/usr/lib/jvm/java-21-openjdk/bin/java" \
        "/usr/lib/jvm/default-java/bin/java" \
        "/opt/java/bin/java"; do
        if [[ -n "${java_path}" ]] && [[ -x "${java_path}" ]]; then
            JAVA_BIN="${java_path}"
            break
        fi
    done
fi

if [[ -z "${JAVA_BIN}" ]] || [[ ! -x "${JAVA_BIN}" ]]; then
    log "ERROR: Java not found"
    notify "Java не найден. Установи OpenJDK 21:\nsudo apt install openjdk-21-jre"
    exit 1
fi

log "Java found: ${JAVA_BIN}"
log "Java version: $("${JAVA_BIN}" -version 2>&1 | head -1 || echo 'unknown')"

# 2) Determine source repo/install roots and refresh GUI artifacts if needed.
SOURCE_REPO_ROOT="${JARVIS_PROJECT_ROOT:-}"
if [[ -n "${SOURCE_REPO_ROOT}" ]] && ! is_valid_repo_root "${SOURCE_REPO_ROOT}"; then
    SOURCE_REPO_ROOT=""
fi
if [[ -z "${SOURCE_REPO_ROOT}" ]]; then
    SOURCE_REPO_ROOT="$(resolve_repo_source_root || true)"
fi

ensure_repo_gui_artifacts "${SOURCE_REPO_ROOT}"

INSTALL_ROOT="${JARVIS_APP}"
PROJECT_ROOT="${SOURCE_REPO_ROOT:-${INSTALL_ROOT}}"

REPO_LAUNCHER_JAR=""
if [[ -n "${SOURCE_REPO_ROOT}" ]]; then
    REPO_LAUNCHER_JAR="${SOURCE_REPO_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
fi

if [[ -n "${REPO_LAUNCHER_JAR}" && -f "${REPO_LAUNCHER_JAR}" ]]; then
    JAR="${REPO_LAUNCHER_JAR}"
    log "Using source repo launcher JAR: ${JAR}"
else
    JAR="${JARVIS_APP}/launcher.jar"
    log "Using product install launcher JAR: ${JAR}"
fi

if [[ ! -f "${JAR}" ]]; then
    log "ERROR: Launcher JAR not found at ${JAR}"
    notify "Не найден Launcher JAR:\n${JAR}\n\nПроверь launcher build/install path."
    exit 1
fi

log "Launcher JAR: ${JAR}"
log "Source repo root: ${SOURCE_REPO_ROOT:-none}"
log "Install root: ${INSTALL_ROOT}"

# Ensure local Java truststore exists (no sudo needed)
ensure_java_truststore() {
    local tls_dir="${HOME}/.jarvis/tls"
    local ca_cert="${tls_dir}/jarvis-ca.crt"
    local jks="${tls_dir}/jarvis-cacerts.jks"
    local pass="${JARVIS_JAVA_TRUSTSTORE_PASSWORD:-changeit}"

    if [[ -f "${jks}" ]]; then
        return 0
    fi
    if [[ ! -f "${ca_cert}" ]]; then
        return 0
    fi
    if ! command -v keytool >/dev/null 2>&1; then
        log "WARNING: keytool not found; cannot create Java truststore"
        return 0
    fi
    keytool -importcert -noprompt \
        -alias jarvis-ca \
        -file "${ca_cert}" \
        -keystore "${jks}" \
        -storepass "${pass}" >/dev/null 2>&1 || true
    chmod 600 "${jks}" 2>/dev/null || true
    log "Java truststore created: ${jks}"
}

ensure_java_truststore || true

# 4) Set environment for launcher
log "Resolved project root: ${PROJECT_ROOT}"
export JARVIS_PROJECT_ROOT="${PROJECT_ROOT}"
export JARVIS_LOG_DIR="${LOG_DIR}"
export JARVIS_AUTO_START="${JARVIS_AUTO_START:-true}"
if [[ -z "${JARVIS_RUNTIME_MODE:-}" ]]; then
    export JARVIS_RUNTIME_MODE="$([[ -n "${SOURCE_REPO_ROOT}" ]] && echo "local" || echo "k8s")"
else
    export JARVIS_RUNTIME_MODE
fi
if [[ "${JARVIS_RUNTIME_MODE}" == "local" ]]; then
    export JARVIS_AUTO_BOOTSTRAP="${JARVIS_AUTO_BOOTSTRAP:-false}"
    LOCAL_RUNTIME_API_URL="$(resolve_local_runtime_api_url)"
    export JARVIS_API_BASE_URL="${JARVIS_API_BASE_URL:-${LOCAL_RUNTIME_API_URL}}"
    export JARVIS_USE_TLS="${JARVIS_USE_TLS:-$( [[ "${JARVIS_API_BASE_URL}" == https://* ]] && echo true || echo false )}"
else
    export JARVIS_AUTO_BOOTSTRAP="${JARVIS_AUTO_BOOTSTRAP:-true}"
    export JARVIS_API_BASE_URL="${JARVIS_API_BASE_URL:-https://api.jarvis.local}"
    export JARVIS_USE_TLS="${JARVIS_USE_TLS:-true}"
fi
export JARVIS_AUTO_INSTALL_DEPS="${JARVIS_AUTO_INSTALL_DEPS:-true}"
if [[ -n "${JARVIS_ENABLE_LLM:-}" ]]; then
    export JARVIS_ENABLE_LLM
fi
if [[ -n "${JARVIS_ENABLE_MEMORY:-}" ]]; then
    export JARVIS_ENABLE_MEMORY
fi
if [[ -n "${JARVIS_ENABLE_GPU:-}" ]]; then
    export JARVIS_ENABLE_GPU
fi

# Prefer local k3s kubeconfig for diagnostics and helper commands
if [[ -f "${HOME}/.jarvis/kubeconfig" ]]; then
    export KUBECONFIG="${HOME}/.jarvis/kubeconfig"
fi

# Java trust store (used by launcher and desktop)
JARVIS_TRUSTSTORE="${HOME}/.jarvis/tls/jarvis-cacerts.jks"
if [[ -f "${JARVIS_TRUSTSTORE}" ]]; then
    export JARVIS_JAVA_TRUSTSTORE="${JARVIS_TRUSTSTORE}"
    export JARVIS_JAVA_TRUSTSTORE_PASSWORD="${JARVIS_JAVA_TRUSTSTORE_PASSWORD:-changeit}"
fi

# 5) Launch JavaFX application (detached, no terminal)
log "Starting launcher..."
log "Launch mode: runtime=${JARVIS_RUNTIME_MODE}, api=${JARVIS_API_BASE_URL}, projectRoot=${PROJECT_ROOT}, launcherJar=${JAR}"

# Determine logback config path
if [[ -n "${SOURCE_REPO_ROOT}" && -f "${SOURCE_REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml" ]]; then
    LOGBACK_CONFIG="${SOURCE_REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml"
elif [[ -f "${JARVIS_APP}/config/logback.xml" ]]; then
    LOGBACK_CONFIG="${JARVIS_APP}/config/logback.xml"
else
    LOGBACK_CONFIG=""
    log "WARNING: logback.xml not found, using defaults"
fi

# Launch with nohup (detached from terminal)
JAVA_SSL_OPTS=()
if [[ -n "${JARVIS_JAVA_TRUSTSTORE:-}" ]]; then
    JAVA_SSL_OPTS+=("-Djavax.net.ssl.trustStore=${JARVIS_JAVA_TRUSTSTORE}")
    JAVA_SSL_OPTS+=("-Djavax.net.ssl.trustStorePassword=${JARVIS_JAVA_TRUSTSTORE_PASSWORD:-changeit}")
fi
if [[ -n "$LOGBACK_CONFIG" ]]; then
    nohup "${JAVA_BIN}" \
        "${JAVA_SSL_OPTS[@]}" \
        -Dlogback.configurationFile="${LOGBACK_CONFIG}" \
        -Dfile.encoding=UTF-8 \
        -jar "$JAR" \
        >> "${LOG}" 2>&1 &
else
    nohup "${JAVA_BIN}" \
        "${JAVA_SSL_OPTS[@]}" \
        -Dfile.encoding=UTF-8 \
        -jar "$JAR" \
        >> "${LOG}" 2>&1 &
fi

LAUNCHER_PID=$!
log "Launcher started with PID: ${LAUNCHER_PID}"

# Store launcher PID
mkdir -p "${HOME}/.jarvis/run"
echo "${LAUNCHER_PID}" > "${HOME}/.jarvis/run/launcher.pid" 2>/dev/null || true

log "Launcher wrapper completed successfully"
exit 0
