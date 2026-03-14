#!/usr/bin/env bash

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "This script is meant to be sourced." >&2
    exit 1
fi

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
RUNTIME_DIR="${JARVIS_HOME}/run/local-runtime"
LOG_DIR="${JARVIS_HOME}/logs/local-runtime"
LOCAL_ENV_FILE="${RUNTIME_DIR}/local.env"
RUN_SUMMARY="${JARVIS_HOME}/run/last-run.json"
POSTGRES_CONTAINER="${JARVIS_LOCAL_POSTGRES_CONTAINER:-jarvis-local-postgres}"

CORE_SERVICES=(
    "security-service"
    "user-profile"
    "nlp-service"
    "orchestrator"
    "voice-gateway"
    "smart-home-service"
    "api-gateway"
    "planner-service"
)

OPTIONAL_SERVICES=(
    "llm-service"
)

mkdir -p "${RUNTIME_DIR}" "${LOG_DIR}" "${JARVIS_HOME}/run"

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

default_vosk_enabled() {
    local ru_path="${JARVIS_HOME}/models/vosk-model-small-ru-0.22"
    local en_path="${JARVIS_HOME}/models/vosk-model-small-en-us-0.15"
    if [[ -d "${ru_path}" || -d "${en_path}" ]]; then
        printf 'true'
    else
        printf 'false'
    fi
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
        JARVIS_VOSK_ENABLED
        JARVIS_VOSK_MODEL_PATH_RU
        JARVIS_VOSK_MODEL_PATH_EN
        LLM_SERVER_URL
        SMART_HOME_PROVIDER
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
        set -a
        # shellcheck disable=SC1090
        source "${LOCAL_ENV_FILE}"
        set +a
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
    : "${SMART_HOME_PROVIDER:=mock}"
    : "${JARVIS_VOSK_MODEL_PATH_RU:=${JARVIS_HOME}/models/vosk-model-small-ru-0.22}"
    : "${JARVIS_VOSK_MODEL_PATH_EN:=${JARVIS_HOME}/models/vosk-model-small-en-us-0.15}"
    : "${JARVIS_VOSK_ENABLED:=$(default_vosk_enabled)}"
    : "${LLM_SERVER_URL:=http://127.0.0.1:5000}"
    : "${MANAGEMENT_HEALTH_RABBIT_ENABLED:=false}"
    : "${MANAGEMENT_HEALTH_KAFKA_ENABLED:=false}"

    if ! is_truthy "${JARVIS_ALLOW_DISTINCT_LOCAL_SERVICE_JWT_SECRET:-false}"; then
        SERVICE_JWT_SECRET="${JWT_SECRET}"
    fi

    cat >"${LOCAL_ENV_FILE}" <<EOF
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}
SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME}
SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD}
POSTGRES_USER=${POSTGRES_USER}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
JWT_SECRET=${JWT_SECRET}
SERVICE_JWT_SECRET=${SERVICE_JWT_SECRET}
ENABLE_LLM=${ENABLE_LLM}
JARVIS_LLM_ENABLED=${JARVIS_LLM_ENABLED}
ENABLE_MEMORY=${ENABLE_MEMORY}
SMART_HOME_PROVIDER=${SMART_HOME_PROVIDER}
JARVIS_VOSK_ENABLED=${JARVIS_VOSK_ENABLED}
JARVIS_VOSK_MODEL_PATH_RU=${JARVIS_VOSK_MODEL_PATH_RU}
JARVIS_VOSK_MODEL_PATH_EN=${JARVIS_VOSK_MODEL_PATH_EN}
LLM_SERVER_URL=${LLM_SERVER_URL}
MANAGEMENT_HEALTH_RABBIT_ENABLED=${MANAGEMENT_HEALTH_RABBIT_ENABLED}
MANAGEMENT_HEALTH_KAFKA_ENABLED=${MANAGEMENT_HEALTH_KAFKA_ENABLED}
EOF

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
    export SMART_HOME_PROVIDER
    export JARVIS_VOSK_ENABLED
    export JARVIS_VOSK_MODEL_PATH_RU
    export JARVIS_VOSK_MODEL_PATH_EN
    export LLM_SERVER_URL
    export MANAGEMENT_HEALTH_RABBIT_ENABLED
    export MANAGEMENT_HEALTH_KAFKA_ENABLED
    export JARVIS_RUNTIME_MODE="local"
    export JARVIS_API_BASE_URL="http://127.0.0.1:8080"
    export JARVIS_USE_TLS="false"
    export VOICE_GATEWAY_URL="http://127.0.0.1:8081"
    export NLP_SERVICE_URL="http://127.0.0.1:8082"
    export ORCHESTRATOR_URL="http://127.0.0.1:8083"
    export SMART_HOME_URL="http://127.0.0.1:8086"
    export SECURITY_URL="http://127.0.0.1:8088"
    export USER_PROFILE_URL="http://127.0.0.1:8089"
    export API_GATEWAY_URL="http://127.0.0.1:8080"
    export LLM_SERVICE_URL="http://127.0.0.1:8091"
    export JARVIS_ORCHESTRATOR_URL="http://127.0.0.1:8083"
    export SPRING_RABBITMQ_USERNAME="${SPRING_RABBITMQ_USERNAME:-jarvis}"
    export SPRING_RABBITMQ_PASSWORD="${SPRING_RABBITMQ_PASSWORD:-jarvis-local-pass}"
    export SPRING_RABBITMQ_HOST="${SPRING_RABBITMQ_HOST:-127.0.0.1}"
    export SPRING_RABBITMQ_PORT="${SPRING_RABBITMQ_PORT:-5672}"
    export USER_PROFILE_ENABLED="true"
    export MEMORY_ENABLED="false"
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
        security-service) printf 'http://127.0.0.1:8088/actuator/health' ;;
        user-profile) printf 'http://127.0.0.1:8089/actuator/health' ;;
        nlp-service) printf 'http://127.0.0.1:8082/actuator/health' ;;
        orchestrator) printf 'http://127.0.0.1:8083/actuator/health' ;;
        voice-gateway) printf 'http://127.0.0.1:8081/actuator/health' ;;
        api-gateway) printf 'http://127.0.0.1:8080/actuator/health' ;;
        smart-home-service) printf 'http://127.0.0.1:8086/actuator/health' ;;
        planner-service) printf 'http://127.0.0.1:8092/actuator/health' ;;
        llm-service) printf 'http://127.0.0.1:8091/api/v1/llm/health' ;;
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
        "apps/smart-home-service"
        "apps/api-gateway"
        "apps/planner-service"
    )

    if is_truthy "${ENABLE_LLM:-false}"; then
        modules+=("apps/llm-service")
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

ensure_local_postgres() {
    local host port db
    read -r host port db < <(jdbc_host_port) || fail "Unsupported SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}"

    if [[ "${host}" != "127.0.0.1" && "${host}" != "localhost" ]]; then
        log "Using external PostgreSQL at ${host}:${port}/${db}"
        return 0
    fi

    if managed_postgres_matches_target "${host}" "${port}" && ! is_truthy "${JARVIS_KEEP_LOCAL_POSTGRES_DATA:-false}"; then
        log "Refreshing managed PostgreSQL container ${POSTGRES_CONTAINER} for a clean local schema..."
        docker rm -f "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
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
                postgres:16-alpine >/dev/null
        fi
    else
        log "Starting local PostgreSQL container ${POSTGRES_CONTAINER}..."
        docker run -d \
            --name "${POSTGRES_CONTAINER}" \
            -e POSTGRES_DB="${db}" \
            -e POSTGRES_USER="${POSTGRES_USER}" \
            -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
            -p "${port}:5432" \
            postgres:16-alpine >/dev/null
    fi

    local deadline=$((SECONDS + 60))
    while (( SECONDS < deadline )); do
        if docker exec "${POSTGRES_CONTAINER}" pg_isready -U "${POSTGRES_USER}" -d "${db}" >/dev/null 2>&1; then
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

    log "Starting ${service}..."
    (
        export JARVIS_RUNTIME_MODE
        export JARVIS_API_BASE_URL
        export JARVIS_USE_TLS
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
        export JARVIS_VOSK_ENABLED
        export JARVIS_VOSK_MODEL_PATH_RU
        export JARVIS_VOSK_MODEL_PATH_EN
        export LLM_SERVER_URL
        export MANAGEMENT_HEALTH_RABBIT_ENABLED
        export MANAGEMENT_HEALTH_KAFKA_ENABLED

        case "${service}" in
            voice-gateway)
                export JARVIS_ORCHESTRATOR_URL="http://127.0.0.1:8083"
                ;;
            llm-service)
                export JARVIS_LLM_ENABLED="true"
                export USER_PROFILE_ENABLED="true"
                export MEMORY_ENABLED="false"
                ;;
            *)
                :
                ;;
        esac

        exec java -jar "${jar}"
    ) >"${log_file}" 2>&1 &

    local pid=$!
    printf '%s\n' "${pid}" >"${pid_file}"
}

wait_for_service() {
    local service="$1"
    local url
    url="$(service_health_url "${service}")" || fail "No health URL configured for ${service}"
    local timeout="${2:-90}"
    local deadline=$((SECONDS + timeout))

    while (( SECONDS < deadline )); do
        if curl -fsS "${url}" >/dev/null 2>&1; then
            log "${service} is healthy."
            return 0
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
  "apiUrl": "http://127.0.0.1:8080",
  "voiceUrl": "ws://127.0.0.1:8080/ws/voice",
  "enableLlm": "${ENABLE_LLM}",
  "enableMemory": "${ENABLE_MEMORY}",
  "runtimeMode": "local"
}
EOF
}
