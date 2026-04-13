#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARVIS_HOME="$(cd "$SCRIPT_DIR/../.." && pwd)"

LOG_DIR="$HOME/.jarvis/logs"
mkdir -p "$LOG_DIR"

resolve_truststore() {
  if [[ -n "${JARVIS_JAVA_TRUSTSTORE:-}" && -f "${JARVIS_JAVA_TRUSTSTORE}" ]]; then
    printf '%s' "${JARVIS_JAVA_TRUSTSTORE}"
    return 0
  fi

  local candidate="${HOME}/.jarvis/tls/jarvis-cacerts.jks"
  if [[ -f "$candidate" ]]; then
    printf '%s' "$candidate"
    return 0
  fi

  candidate="${HOME}/.jarvis/certs/jarvis-truststore.jks"
  if [[ -f "$candidate" ]]; then
    printf '%s' "$candidate"
    return 0
  fi

  return 1
}

TRUSTSTORE_PATH=""
if TRUSTSTORE_PATH="$(resolve_truststore)"; then
  TRUSTSTORE_PASS="${JARVIS_JAVA_TRUSTSTORE_PASSWORD:-changeit}"
  SSL_MAVEN_OPTS="-Djavax.net.ssl.trustStore=${TRUSTSTORE_PATH} -Djavax.net.ssl.trustStorePassword=${TRUSTSTORE_PASS}"
  if [[ -n "${MAVEN_OPTS:-}" ]]; then
    export MAVEN_OPTS="${MAVEN_OPTS} ${SSL_MAVEN_OPTS}"
  else
    export MAVEN_OPTS="${SSL_MAVEN_OPTS}"
  fi
fi

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
BACKEND_LOG="$LOG_DIR/backend_${TIMESTAMP}.log"
CLIENT_LOG="$LOG_DIR/desktop-ui_${TIMESTAMP}.log"

resolve_last_run_api_url() {
  local summary="${HOME}/.jarvis/run/last-run.json"
  [[ -f "${summary}" ]] || return 1

  local status runtime_mode api_url
  status="$(grep -oE '"status"[[:space:]]*:[[:space:]]*"[^"]+"' "${summary}" | sed -E 's/.*"([^"]+)"/\1/' | head -1)"
  runtime_mode="$(grep -oE '"runtimeMode"[[:space:]]*:[[:space:]]*"[^"]+"' "${summary}" | sed -E 's/.*"([^"]+)"/\1/' | head -1)"
  api_url="$(grep -oE '"apiUrl"[[:space:]]*:[[:space:]]*"[^"]+"' "${summary}" | sed -E 's/.*"([^"]+)"/\1/' | head -1)"

  [[ "${runtime_mode}" == "local" ]] || return 1
  [[ "${status}" != "stopped" ]] || return 1
  [[ -n "${api_url}" ]] || return 1

  printf '%s' "${api_url%/}"
}

detect_runtime_mode() {
  if [[ -n "${JARVIS_RUNTIME_MODE:-}" ]]; then
    printf '%s' "${JARVIS_RUNTIME_MODE}"
    return 0
  fi

  if [[ -f "${HOME}/.jarvis/run/local-runtime/local.env" ]]; then
    if curl -fsS "http://127.0.0.1:8080/actuator/health/readiness" >/dev/null 2>&1; then
      printf 'local'
      return 0
    fi
  fi

  printf 'k8s'
}

RUNTIME_MODE="$(detect_runtime_mode)"

backend_running=false
BACKEND_SCRIPT="./jarvis-launch.sh"

if [[ "${RUNTIME_MODE}" == "local" ]]; then
  LOCAL_RUNTIME_API_URL="$(resolve_last_run_api_url || true)"
  if [[ -z "${JARVIS_API_BASE_URL:-}" ]]; then
    if [[ -n "${LOCAL_RUNTIME_API_URL:-}" ]]; then
      export JARVIS_API_BASE_URL="${LOCAL_RUNTIME_API_URL}"
    else
      echo "WARNING: JARVIS_API_BASE_URL is not set and no active local runtime was detected; defaulting to http://127.0.0.1:8080" >&2
      export JARVIS_API_BASE_URL="http://127.0.0.1:8080"
    fi
  fi
  export JARVIS_USE_TLS="${JARVIS_USE_TLS:-$( [[ "${JARVIS_API_BASE_URL}" == https://* ]] && echo true || echo false )}"
  BACKEND_SCRIPT="./scripts/runtime-up.sh"
  CURL_ARGS=(-fsS)
  if [[ "${JARVIS_API_BASE_URL}" == https://* && -f "${HOME}/.jarvis/tls/jarvis-ca.crt" ]]; then
    CURL_ARGS+=(--cacert "${HOME}/.jarvis/tls/jarvis-ca.crt")
  fi
  if curl "${CURL_ARGS[@]}" "${JARVIS_API_BASE_URL}/actuator/health" >/dev/null 2>&1; then
    backend_running=true
  fi
else
  export JARVIS_API_BASE_URL="${JARVIS_API_BASE_URL:-https://api.jarvis.local}"
  export JARVIS_USE_TLS="${JARVIS_USE_TLS:-true}"
  if command -v kubectl >/dev/null 2>&1; then
    if kubectl get namespace jarvis >/dev/null 2>&1; then
      ready_replicas="$(kubectl get deployment api-gateway -n jarvis -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
      if [[ "$ready_replicas" =~ ^[1-9][0-9]*$ ]]; then
        backend_running=true
      fi
    fi
  fi
fi

if [[ "${RUNTIME_MODE}" == "local" ]]; then
  (
    cd "$JARVIS_HOME"
    ENABLE_LLM=true ENABLE_MEMORY=true "${BACKEND_SCRIPT}"
  ) >"$BACKEND_LOG" 2>&1
  echo "Refreshed local backend. Log: $BACKEND_LOG"
elif [ "$backend_running" = false ]; then
  (
    cd "$JARVIS_HOME"
    ENABLE_LLM=true ENABLE_MEMORY=true "${BACKEND_SCRIPT}"
  ) >"$BACKEND_LOG" 2>&1 &
  echo "Started backend. Log: $BACKEND_LOG"
else
  echo "Backend already running."
  echo "Backend log reserved at: $BACKEND_LOG"
fi

(
  cd "$JARVIS_HOME"
  mvn -q -pl apps/desktop-javafx -am -DskipTests install
  mvn -q -f apps/desktop-javafx/pom.xml -DskipTests org.openjfx:javafx-maven-plugin:0.0.8:run
) >"$CLIENT_LOG" 2>&1 &
echo "Started desktop UI. Log: $CLIENT_LOG"
if [[ -n "$TRUSTSTORE_PATH" ]]; then
  echo "Desktop truststore: $TRUSTSTORE_PATH"
else
  echo "Desktop truststore not found; TLS may fail until truststore is configured"
fi
