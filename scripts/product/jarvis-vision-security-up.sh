#!/usr/bin/env bash
# =============================================================================
# Start the workstation-local vision-security-service Java process.
# =============================================================================
# vision-security-service is local-only by design (needs camera + screen
# access) and never runs as a normal pod. This script:
#   1. Packages the JAR if missing (mvn -pl apps/vision-security-service -am)
#   2. Starts the Spring Boot process bound to JARVIS_VISION_SECURITY_PORT
#      (default 8094) on the host
#   3. Writes PID/log under ~/.jarvis/run/local-runtime and ~/.jarvis/logs
#   4. Waits for /actuator/health/readiness to report UP
#
# Used by jarvis-launch.sh --full to wire the k8s host bridge that lets
# api-gateway pods proxy /api/v1/vision-security/** to this local process via
# vision-security-service.jarvis-prod.svc.cluster.local:8094 — see
# infra/k8s/base/vision-security-service/ and
# infra/scripts/microk8s/apply-vision-security-host-endpoints.sh.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# shellcheck disable=SC1091
source "${ROOT_DIR}/scripts/runtime/common.sh"

SERVICE="vision-security-service"

usage() {
    cat <<EOF
Usage: ./scripts/product/jarvis-vision-security-up.sh [options]

Options:
  --skip-build       Skip mvn package even when JAR is missing
  --bind=ADDR        Bind address for the Java process (default: 0.0.0.0
                     so pods inside k8s can reach the host port via the
                     selectorless Service)
  --port=PORT        Override TCP port (default: JARVIS_VISION_SECURITY_PORT
                     or 8094)
  --help, -h         Show this help

The launched process keeps running in the background until you call:
  ./scripts/product/jarvis-vision-security-down.sh
EOF
}

SKIP_BUILD="${JARVIS_SKIP_BUILD:-false}"
BIND_ADDR="${JARVIS_VISION_SECURITY_BIND:-0.0.0.0}"
HOST_PORT="${JARVIS_VISION_SECURITY_PORT:-8094}"

for arg in "$@"; do
    case "${arg}" in
        --skip-build) SKIP_BUILD="true" ;;
        --bind=*)     BIND_ADDR="${arg#*=}" ;;
        --port=*)     HOST_PORT="${arg#*=}" ;;
        --help|-h)    usage; exit 0 ;;
        *)            echo "❌ Unknown argument: ${arg}" >&2; usage >&2; exit 1 ;;
    esac
done

export JARVIS_VISION_SECURITY_PORT="${HOST_PORT}"

VERSION="$(project_version)"
JAR_PATH="${ROOT_DIR}/apps/${SERVICE}/target/${SERVICE}-${VERSION}.jar"

if [[ ! -f "${JAR_PATH}" ]]; then
    if is_truthy "${SKIP_BUILD}"; then
        fail "Missing JAR for ${SERVICE} and --skip-build is set: ${JAR_PATH}"
    fi
    log "Packaging ${SERVICE}..."
    (
        cd "${ROOT_DIR}"
        mvn -q -pl "apps/${SERVICE}" -am -DskipTests package
    )
fi
[[ -f "${JAR_PATH}" ]] || fail "Failed to produce JAR for ${SERVICE}: ${JAR_PATH}"

PID_FILE="$(service_pid_file "${SERVICE}")"
LOG_FILE="$(service_log_file "${SERVICE}")"
mkdir -p "$(dirname "${PID_FILE}")" "$(dirname "${LOG_FILE}")"

if service_is_running "${SERVICE}"; then
    log "${SERVICE} is already running (PID $(cat "${PID_FILE}"))."
else
    if port_is_listening "${HOST_PORT}"; then
        fail "Port ${HOST_PORT} is already in use; refusing to start ${SERVICE}. Stop the other listener or override --port=PORT."
    fi

    # Pull JWT secrets from the canonical local secrets file so the
    # vision-security-service can construct ServiceJwtProvider (jarvis-common
    # requires service.jwt.secret or jwt.secret). We do NOT read these from
    # the in-cluster Secret because vision-security runs on the host.
    SECRETS_FILE="${HOME}/.jarvis/secrets/secrets.env"
    if [[ -f "${SECRETS_FILE}" ]]; then
        # shellcheck disable=SC1090
        set -a; source "${SECRETS_FILE}"; set +a
    fi
    if [[ -z "${JWT_SECRET:-}" ]]; then
        fail "JWT_SECRET is required to start ${SERVICE}. Add it to ${SECRETS_FILE} or export it before launching."
    fi
    : "${SERVICE_JWT_SECRET:=${JWT_SECRET}}"
    : "${SERVICE_JWT_ALLOW_SHARED_SECRET:=true}"

    log "Starting ${SERVICE} on ${BIND_ADDR}:${HOST_PORT}..."

    JAVA_TOOL_OPTIONS_LOCAL=""
    if [[ -n "${JAVA_TOOL_OPTIONS:-}" ]]; then
        JAVA_TOOL_OPTIONS_LOCAL="${JAVA_TOOL_OPTIONS}"
    fi

    (
        export JARVIS_VISION_SECURITY_PORT
        export SERVER_ADDRESS="${BIND_ADDR}"
        export JWT_SECRET
        export SERVICE_JWT_SECRET
        export SERVICE_JWT_ALLOW_SHARED_SECRET
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS_LOCAL}"
        launch_detached_process "${LOG_FILE}" java \
            -Dserver.port="${HOST_PORT}" \
            -Dserver.address="${BIND_ADDR}" \
            -jar "${JAR_PATH}" >"${PID_FILE}"
    )
fi

# Health probe — use 127.0.0.1 because the readiness check runs on the same host.
HEALTH_URL="http://127.0.0.1:${HOST_PORT}/actuator/health/readiness"
TIMEOUT="${JARVIS_VISION_SECURITY_READY_TIMEOUT:-90}"
deadline=$((SECONDS + TIMEOUT))
while (( SECONDS < deadline )); do
    body="$(curl -fsS --max-time 3 "${HEALTH_URL}" 2>/dev/null || true)"
    if [[ -n "${body}" && "${body}" =~ \"status\"[[:space:]]*:[[:space:]]*\"UP\" ]]; then
        log "${SERVICE} is healthy at ${HEALTH_URL}"
        exit 0
    fi

    if ! service_is_running "${SERVICE}"; then
        warn "${SERVICE} exited before becoming healthy. Tail of log:"
        tail -n 60 "${LOG_FILE}" >&2 || true
        exit 1
    fi
    sleep 2
done

warn "${SERVICE} did not become healthy within ${TIMEOUT}s. Tail of log:"
tail -n 60 "${LOG_FILE}" >&2 || true
exit 1
