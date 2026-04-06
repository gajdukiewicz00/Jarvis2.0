#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

for arg in "$@"; do
  case "${arg}" in
    --help|-h)
      cat <<'EOF'
Usage: ./scripts/product/jarvis-generate-internal-tls-voice-gateway-smart-home-service.sh

Generates the narrow internal TLS trust material for:
  voice-gateway (HTTPS client) -> smart-home-service (existing HTTPS server on 8086)
EOF
      exit 0
      ;;
    *)
      echo "❌ Unknown argument: ${arg}" >&2
      exit 1
      ;;
  esac
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

require_cmd keytool

JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
TLS_ROOT="${JARVIS_HOME}/tls"
SLICE_DIR="${TLS_ROOT}/internal/voice-gateway-smart-home-service"
CA_CERT="${TLS_ROOT}/jarvis-ca.crt"
TRUSTSTORE="${SLICE_DIR}/jarvis-internal-truststore.jks"
CA_CERT_COPY="${SLICE_DIR}/ca.crt"
TRUSTSTORE_PASSWORD="${JARVIS_INTERNAL_TLS_VOICE_GATEWAY_SMART_HOME_SERVICE_TRUSTSTORE_PASSWORD:-changeit}"

mkdir -p "${SLICE_DIR}"
chmod 700 "${SLICE_DIR}"

if [[ ! -f "${CA_CERT}" ]]; then
  "${PROJECT_ROOT}/scripts/product/jarvis-generate-certs.sh"
fi

if [[ ! -f "${CA_CERT}" ]]; then
  echo "❌ Local Jarvis CA was not created" >&2
  exit 1
fi

cp "${CA_CERT}" "${CA_CERT_COPY}"
chmod 644 "${CA_CERT_COPY}"
rm -f "${TRUSTSTORE}"

keytool -importcert \
  -noprompt \
  -alias jarvis-ca \
  -file "${CA_CERT}" \
  -keystore "${TRUSTSTORE}" \
  -storepass "${TRUSTSTORE_PASSWORD}" >/dev/null 2>&1

chmod 600 "${TRUSTSTORE}"

echo "✅ Generated internal TLS trust material in ${SLICE_DIR}"
echo "   - ${TRUSTSTORE}"
echo "   - ${CA_CERT_COPY}"
