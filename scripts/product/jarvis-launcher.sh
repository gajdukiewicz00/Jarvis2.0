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

# Lock file to prevent double launch
JARVIS_HOME="${HOME}/.jarvis"
LOCK_FILE="${JARVIS_HOME}/run/launcher.lock"
mkdir -p "${JARVIS_HOME}/run"

# Try to acquire lock (non-blocking)
exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
    # Another launcher is already running
    exit 0
fi
# Lock acquired, will be released on exit

# Logs directory
LOG_DIR="${JARVIS_HOME}/logs"
mkdir -p "$LOG_DIR"
LOG="${LOG_DIR}/launcher-start.log"

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

# 2) Determine launcher JAR location
# Priority: ~/.jarvis/app/ (product install) > repo (development)
JARVIS_APP="${HOME}/.jarvis/app"
REPO_ROOT=""

# Check product install location first
if [[ -f "${JARVIS_APP}/launcher.jar" ]]; then
    JAR="${JARVIS_APP}/launcher.jar"
    REPO_ROOT="${JARVIS_APP}"
    log "Using product install: ${JAR}"
elif [[ -f "${JARVIS_APP}/bin/jarvis-launcher.sh" ]]; then
    # If wrapper is in product install, use repo from there
    if [[ "$SCRIPT_DIR" == "${JARVIS_APP}/bin"* ]]; then
        REPO_ROOT="${JARVIS_APP}"
        JAR="${JARVIS_APP}/launcher.jar"
        log "Using product install (via wrapper): ${JAR}"
    else
        # Development mode: find repo root from script location
        REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
        JAR="${REPO_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
        log "Using development mode: ${JAR}"
    fi
else
    # Development mode: find repo root from script location
    REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
    JAR="${REPO_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
    log "Using development mode: ${JAR}"
fi

# 3) Check if JAR exists
if [[ ! -f "$JAR" ]]; then
    log "Launcher JAR not found at $JAR, attempting build..."
    if command -v mvn >/dev/null 2>&1 && [[ -n "${REPO_ROOT}" ]] && [[ -f "${REPO_ROOT}/pom.xml" ]]; then
        (cd "${REPO_ROOT}" && mvn -pl apps/launcher-javafx -DskipTests clean package >/dev/null 2>&1) || true
    fi
    if [[ ! -f "$JAR" ]]; then
        log "ERROR: Launcher JAR not found at $JAR"
        notify "Не найден Launcher JAR:\n$JAR\n\nДля разработки: собери проект:\nmvn -pl apps/launcher-javafx -DskipTests clean package\n\nДля продукта: установи в ~/.jarvis/app/"
        exit 1
    fi
fi

log "Launcher JAR: ${JAR}"
log "Install root: ${REPO_ROOT}"

# Resolve project root for backend scripts (prefer repo with sources)
is_valid_repo_root() {
    local root="$1"
    [[ -f "${root}/pom.xml" ]] && [[ -d "${root}/apps" ]] && [[ -f "${root}/jarvis-launch.sh" ]]
}

PROJECT_ROOT="${JARVIS_PROJECT_ROOT:-}"
if [[ -n "${PROJECT_ROOT}" ]] && ! is_valid_repo_root "${PROJECT_ROOT}"; then
    PROJECT_ROOT=""
fi
if [[ -z "${PROJECT_ROOT}" ]] && is_valid_repo_root "${REPO_ROOT}"; then
    PROJECT_ROOT="${REPO_ROOT}"
fi
if [[ -z "${PROJECT_ROOT}" ]]; then
    for candidate in \
        "${HOME}/Jarvis/Jarvis2.0" \
        "${HOME}/IdeaProjects/Jarvis2.0" \
        "${HOME}/Projects/Jarvis2.0" \
        "${HOME}/Jarvis2.0"; do
        if is_valid_repo_root "${candidate}"; then
            PROJECT_ROOT="${candidate}"
            break
        fi
    done
fi
if [[ -z "${PROJECT_ROOT}" ]]; then
    PROJECT_ROOT="${REPO_ROOT}"
fi

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

# If repo has a newer launcher JAR, sync it into product install for one-click stability
REPO_LAUNCHER_JAR="${PROJECT_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
if [[ -f "${REPO_LAUNCHER_JAR}" ]]; then
    mkdir -p "${JARVIS_APP}"
    if [[ ! -f "${JARVIS_APP}/launcher.jar" || "${REPO_LAUNCHER_JAR}" -nt "${JARVIS_APP}/launcher.jar" ]]; then
        cp "${REPO_LAUNCHER_JAR}" "${JARVIS_APP}/launcher.jar" 2>/dev/null || true
        log "Updated product launcher JAR from repo: ${REPO_LAUNCHER_JAR}"
        JAR="${JARVIS_APP}/launcher.jar"
    fi
fi

# 4) Set environment for launcher
log "Resolved project root: ${PROJECT_ROOT}"
export JARVIS_PROJECT_ROOT="${PROJECT_ROOT}"
export JARVIS_LOG_DIR="${LOG_DIR}"
export JARVIS_AUTO_START="${JARVIS_AUTO_START:-true}"
if [[ -z "${JARVIS_RUNTIME_MODE:-}" ]]; then
    if [[ "${REPO_ROOT}" == "${JARVIS_APP}" ]]; then
        export JARVIS_RUNTIME_MODE="k8s"
    else
        export JARVIS_RUNTIME_MODE="local"
    fi
else
    export JARVIS_RUNTIME_MODE
fi
if [[ "${JARVIS_RUNTIME_MODE}" == "local" ]]; then
    export JARVIS_AUTO_BOOTSTRAP="${JARVIS_AUTO_BOOTSTRAP:-false}"
    export JARVIS_API_BASE_URL="${JARVIS_API_BASE_URL:-http://127.0.0.1:8080}"
    export JARVIS_USE_TLS="${JARVIS_USE_TLS:-false}"
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

# Determine logback config path
if [[ -f "${PROJECT_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml" ]]; then
    LOGBACK_CONFIG="${PROJECT_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml"
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
