#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Launcher Wrapper Script (Product)
# =============================================================================
# Wrapper for JavaFX launcher JAR.
# Ensures proper Java environment and logging.
# Used by .desktop file to launch Jarvis without terminal.
# =============================================================================

set -euo pipefail

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
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [[ "$SCRIPT_DIR" == "${JARVIS_APP}/bin"* ]]; then
        REPO_ROOT="${JARVIS_APP}"
        JAR="${JARVIS_APP}/launcher.jar"
        log "Using product install (via wrapper): ${JAR}"
    else
        # Development mode: find repo root from script location
        SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
        JAR="${REPO_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
        log "Using development mode: ${JAR}"
    fi
else
    # Development mode: find repo root from script location
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
    JAR="${REPO_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
    log "Using development mode: ${JAR}"
fi

# 3) Check if JAR exists
if [[ ! -f "$JAR" ]]; then
    log "ERROR: Launcher JAR not found at $JAR"
    notify "Не найден Launcher JAR:\n$JAR\n\nДля разработки: собери проект:\nmvn -pl apps/launcher-javafx -DskipTests clean package\n\nДля продукта: установи в ~/.jarvis/app/"
    exit 1
fi

log "Launcher JAR: ${JAR}"
log "Project root: ${REPO_ROOT}"

# 4) Set environment for launcher
export JARVIS_PROJECT_ROOT="${REPO_ROOT}"
export JARVIS_LOG_DIR="${LOG_DIR}"

# 5) Launch JavaFX application (detached, no terminal)
log "Starting launcher..."

# Determine logback config path
if [[ -f "${REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml" ]]; then
    LOGBACK_CONFIG="${REPO_ROOT}/apps/launcher-javafx/src/main/resources/logback.xml"
elif [[ -f "${JARVIS_APP}/config/logback.xml" ]]; then
    LOGBACK_CONFIG="${JARVIS_APP}/config/logback.xml"
else
    LOGBACK_CONFIG=""
    log "WARNING: logback.xml not found, using defaults"
fi

# Launch with nohup (detached from terminal)
if [[ -n "$LOGBACK_CONFIG" ]]; then
    nohup "${JAVA_BIN}" \
        -Dlogback.configurationFile="${LOGBACK_CONFIG}" \
        -Dfile.encoding=UTF-8 \
        -jar "$JAR" \
        >> "${LOG}" 2>&1 &
else
    nohup "${JAVA_BIN}" \
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
