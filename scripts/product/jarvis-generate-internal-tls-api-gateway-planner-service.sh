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
Usage: ./scripts/product/jarvis-generate-internal-tls-api-gateway-planner-service.sh [--force]

Generates the narrow internal TLS material for the seventeenth migrated hop:
  api-gateway (HTTPS client) -> planner-service (HTTPS server)
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

cert_matches_key() {
  local cert_path="$1"
  local key_path="$2"
  [[ -f "${cert_path}" && -f "${key_path}" ]] || return 1
  local cert_modulus
  local key_modulus
  cert_modulus="$(openssl x509 -noout -modulus -in "${cert_path}" 2>/dev/null || true)"
  key_modulus="$(openssl rsa -noout -modulus -in "${key_path}" 2>/dev/null || true)"
  [[ -n "${cert_modulus}" && "${cert_modulus}" == "${key_modulus}" ]]
}

require_cmd openssl

JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
TLS_ROOT="${JARVIS_HOME}/tls"
SLICE_DIR="${TLS_ROOT}/internal/api-gateway-planner-service"
CA_KEY="${TLS_ROOT}/jarvis-ca.key"
CA_CERT="${TLS_ROOT}/jarvis-ca.crt"

SERVER_KEY="${SLICE_DIR}/planner-service.key"
SERVER_CSR="${SLICE_DIR}/planner-service.csr"
SERVER_CERT="${SLICE_DIR}/planner-service.crt"
SERVER_KEYSTORE="${SLICE_DIR}/planner-service-keystore.p12"
CA_CERT_COPY="${SLICE_DIR}/ca.crt"

KEYSTORE_PASSWORD="${JARVIS_INTERNAL_TLS_API_GATEWAY_PLANNER_SERVICE_KEYSTORE_PASSWORD:-changeit}"

mkdir -p "${SLICE_DIR}"
chmod 700 "${SLICE_DIR}"
umask 077

if [[ ! -f "${CA_KEY}" || ! -f "${CA_CERT}" ]]; then
  "${PROJECT_ROOT}/scripts/product/jarvis-generate-certs.sh"
fi

if [[ ! -f "${CA_KEY}" || ! -f "${CA_CERT}" ]]; then
  echo "❌ Local Jarvis CA was not created" >&2
  exit 1
fi

if [[ "${FORCE}" == "true" ]]; then
  rm -f "${SERVER_KEY}" "${SERVER_CSR}" "${SERVER_CERT}" "${SERVER_KEYSTORE}" "${CA_CERT_COPY}"
fi

cat > "${CA_CERT_COPY}" < "${CA_CERT}"
chmod 644 "${CA_CERT_COPY}"

if ! cert_matches_key "${SERVER_CERT}" "${SERVER_KEY}"; then
  rm -f "${SERVER_KEY}" "${SERVER_CSR}" "${SERVER_CERT}" "${SERVER_KEYSTORE}"
  SAN_CONFIG="$(mktemp)"
  cat > "${SAN_CONFIG}" <<'EOF'
[ req ]
default_bits       = 2048
prompt             = no
default_md         = sha256
distinguished_name = dn
req_extensions     = req_ext

[ dn ]
CN = planner-service.jarvis-prod.svc.cluster.local
O  = Jarvis

[ req_ext ]
subjectAltName = @alt_names
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[ alt_names ]
DNS.1 = planner-service
DNS.2 = planner-service.jarvis-prod.svc.cluster.local
DNS.3 = localhost
IP.1 = 127.0.0.1
EOF

  openssl req -new -nodes \
    -keyout "${SERVER_KEY}" \
    -out "${SERVER_CSR}" \
    -config "${SAN_CONFIG}" >/dev/null 2>&1

  openssl x509 -req \
    -in "${SERVER_CSR}" \
    -CA "${CA_CERT}" \
    -CAkey "${CA_KEY}" \
    -CAcreateserial \
    -out "${SERVER_CERT}" \
    -days 825 \
    -sha256 \
    -extfile "${SAN_CONFIG}" \
    -extensions req_ext >/dev/null 2>&1

  rm -f "${SAN_CONFIG}" "${SERVER_CSR}"
  chmod 600 "${SERVER_KEY}"
  chmod 644 "${SERVER_CERT}"
fi

if [[ ! -f "${SERVER_KEYSTORE}" || "${SERVER_CERT}" -nt "${SERVER_KEYSTORE}" || "${SERVER_KEY}" -nt "${SERVER_KEYSTORE}" ]]; then
  openssl pkcs12 -export \
    -in "${SERVER_CERT}" \
    -inkey "${SERVER_KEY}" \
    -out "${SERVER_KEYSTORE}" \
    -name planner-service \
    -passout "pass:${KEYSTORE_PASSWORD}" >/dev/null 2>&1
  chmod 600 "${SERVER_KEYSTORE}"
fi

echo "✅ Generated internal TLS slice material in ${SLICE_DIR}"
echo "   - ${SERVER_CERT}"
echo "   - ${SERVER_KEYSTORE}"
echo "   - ${CA_CERT_COPY}"
