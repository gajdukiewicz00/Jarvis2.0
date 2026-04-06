#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARVIS_HOME="$(cd "$SCRIPT_DIR/../.." && pwd)"
RUNTIME_MODE="${JARVIS_RUNTIME_MODE:-local}"

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

backend_running=false
BACKEND_SCRIPT="./jarvis-launch.sh"

if [[ "${RUNTIME_MODE}" == "local" ]]; then
  LOCAL_RUNTIME_API_URL="$(resolve_last_run_api_url || true)"
  export JARVIS_API_BASE_URL="${JARVIS_API_BASE_URL:-${LOCAL_RUNTIME_API_URL:-https://127.0.0.1:18080}}"
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

if [ "$backend_running" = false ]; then
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
  if [[ -f "apps/desktop-app-javafx/pom.xml" ]]; then
    mvn -q -pl apps/desktop-app-javafx -am -DskipTests javafx:run
  else
    mvn -q -pl apps/desktop-client-javafx -DskipTests javafx:run
  fi
) >"$CLIENT_LOG" 2>&1 &
echo "Started desktop UI. Log: $CLIENT_LOG"
if [[ -n "$TRUSTSTORE_PATH" ]]; then
  echo "Desktop truststore: $TRUSTSTORE_PATH"
else
  echo "Desktop truststore not found; TLS may fail until truststore is configured"
fi
