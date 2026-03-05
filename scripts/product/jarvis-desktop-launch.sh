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
CLIENT_LOG="$LOG_DIR/desktop-client_${TIMESTAMP}.log"

backend_running=false
if command -v kubectl >/dev/null 2>&1; then
  if kubectl get namespace jarvis >/dev/null 2>&1; then
    ready_replicas="$(kubectl get deployment api-gateway -n jarvis -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
    if [[ "$ready_replicas" =~ ^[1-9][0-9]*$ ]]; then
      backend_running=true
    fi
  fi
fi

if [ "$backend_running" = false ]; then
  (
    cd "$JARVIS_HOME"
    ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
  ) >"$BACKEND_LOG" 2>&1 &
  echo "Started backend. Log: $BACKEND_LOG"
else
  echo "Backend already running in namespace 'jarvis' (api-gateway readyReplicas > 0)."
  echo "Backend log reserved at: $BACKEND_LOG"
fi

(
  cd "$JARVIS_HOME"
  mvn -q -pl apps/desktop-client-javafx -DskipTests javafx:run
) >"$CLIENT_LOG" 2>&1 &
echo "Started desktop client. Log: $CLIENT_LOG"
if [[ -n "$TRUSTSTORE_PATH" ]]; then
  echo "Desktop truststore: $TRUSTSTORE_PATH"
else
  echo "Desktop truststore not found; TLS may fail until truststore is configured"
fi
