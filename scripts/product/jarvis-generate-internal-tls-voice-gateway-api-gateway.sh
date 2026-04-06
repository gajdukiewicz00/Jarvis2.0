#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

FORCE=false
for arg in "$@"; do
  case "${arg}" in
    --force)
      FORCE=true
      ;;
    --help|-h)
      cat <<'EOF'
Usage: ./scripts/product/jarvis-generate-internal-tls-voice-gateway-api-gateway.sh [--force]

Generates the dedicated trust material for the fourth migrated slice:
  voice-gateway (HTTPS client) -> api-gateway (existing HTTPS server on 8443)
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
SOURCE_DIR="${TLS_ROOT}/internal/planner-api-gateway"
SOURCE_CA_CERT="${SOURCE_DIR}/ca.crt"
SLICE_DIR="${TLS_ROOT}/internal/voice-gateway-api-gateway"
TRUSTSTORE="${SLICE_DIR}/jarvis-internal-truststore.jks"
CA_CERT_COPY="${SLICE_DIR}/ca.crt"
TRUSTSTORE_PASSWORD="${JARVIS_INTERNAL_TLS_VOICE_GATEWAY_API_GATEWAY_TRUSTSTORE_PASSWORD:-changeit}"

mkdir -p "${SLICE_DIR}"
chmod 700 "${SLICE_DIR}"
umask 077

if [[ ! -f "${SOURCE_CA_CERT}" ]]; then
  "${PROJECT_ROOT}/scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh"
fi

if [[ ! -f "${SOURCE_CA_CERT}" ]]; then
  echo "❌ Missing source CA for api-gateway internal HTTPS listener: ${SOURCE_CA_CERT}" >&2
  exit 1
fi

if [[ "${FORCE}" == "true" ]]; then
  rm -f "${TRUSTSTORE}" "${CA_CERT_COPY}"
fi

cat > "${CA_CERT_COPY}" < "${SOURCE_CA_CERT}"
chmod 644 "${CA_CERT_COPY}"

if [[ ! -f "${TRUSTSTORE}" || "${CA_CERT_COPY}" -nt "${TRUSTSTORE}" ]]; then
  rm -f "${TRUSTSTORE}"
  keytool -importcert -noprompt \
    -alias jarvis-api-gateway-ca \
    -file "${CA_CERT_COPY}" \
    -keystore "${TRUSTSTORE}" \
    -storepass "${TRUSTSTORE_PASSWORD}" >/dev/null 2>&1
  chmod 600 "${TRUSTSTORE}"
fi

echo "✅ Generated internal TLS trust material in ${SLICE_DIR}"
echo "   - ${TRUSTSTORE}"
echo "   - ${CA_CERT_COPY}"
