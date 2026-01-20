#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - TLS Certificate Generator (Local CA)
# =============================================================================
# Generates a local CA and server certificate for:
#   - api.jarvis.local
#   - voice.jarvis.local
# Stores output in ~/.jarvis/tls
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

FORCE=false
for arg in "$@"; do
    case "$arg" in
        --force)
            FORCE=true
            ;;
        --help|-h)
            echo "Usage: $0 [--force]"
            exit 0
            ;;
    esac
done

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

if ! command -v openssl >/dev/null 2>&1; then
    echo -e "${RED}❌ openssl not found${NC}"
    echo "Install: sudo apt install openssl"
    exit 1
fi

JARVIS_HOME="${HOME}/.jarvis"
TLS_DIR="${JARVIS_HOME}/tls"

CA_KEY="${TLS_DIR}/jarvis-ca.key"
CA_CERT="${TLS_DIR}/jarvis-ca.crt"
CA_SERIAL="${TLS_DIR}/jarvis-ca.srl"

SERVER_KEY="${TLS_DIR}/jarvis.key"
SERVER_CSR="${TLS_DIR}/jarvis.csr"
SERVER_CERT="${TLS_DIR}/jarvis.crt"

mkdir -p "${TLS_DIR}"
chmod 700 "${TLS_DIR}"

umask 077

echo "=========================================="
echo "Jarvis 2.0 - TLS Certificate Generator"
echo "=========================================="
echo ""
echo "Output directory: ${TLS_DIR}"
echo ""

if [[ "$FORCE" == "true" ]]; then
    echo -e "${YELLOW}⚠️  --force enabled: regenerating certificates${NC}"
    rm -f "${CA_KEY}" "${CA_CERT}" "${CA_SERIAL}" "${SERVER_KEY}" "${SERVER_CSR}" "${SERVER_CERT}" || true
fi

# Generate CA if missing
if [[ ! -f "${CA_KEY}" || ! -f "${CA_CERT}" ]]; then
    echo -e "${YELLOW}→ Generating local CA...${NC}"
    CA_CONFIG=$(mktemp)
    cat > "${CA_CONFIG}" <<'EOC'
[ req ]
default_bits       = 4096
prompt             = no
default_md         = sha256
distinguished_name = dn
x509_extensions    = v3_ca

[ dn ]
CN = Jarvis Local CA
O  = Jarvis

[ v3_ca ]
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always,issuer
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
EOC

    openssl req -x509 -new -nodes \
        -keyout "${CA_KEY}" \
        -out "${CA_CERT}" \
        -days 3650 \
        -config "${CA_CONFIG}" >/dev/null 2>&1

    rm -f "${CA_CONFIG}"
    chmod 600 "${CA_KEY}"
    chmod 644 "${CA_CERT}"
    echo -e "${GREEN}✓${NC} CA generated"
else
    echo -e "${GREEN}✓${NC} CA already exists"
fi

# Generate server cert if missing
if [[ ! -f "${SERVER_KEY}" || ! -f "${SERVER_CERT}" ]]; then
    echo -e "${YELLOW}→ Generating server certificate...${NC}"
    SAN_CONFIG=$(mktemp)
    cat > "${SAN_CONFIG}" <<'EOC'
[ req ]
default_bits       = 2048
prompt             = no
default_md         = sha256
distinguished_name = dn
req_extensions     = req_ext

[ dn ]
CN = api.jarvis.local
O  = Jarvis

[ req_ext ]
subjectAltName = @alt_names
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[ alt_names ]
DNS.1 = api.jarvis.local
DNS.2 = voice.jarvis.local
DNS.3 = jarvis.local
DNS.4 = localhost
IP.1  = 127.0.0.1
EOC

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
    echo -e "${GREEN}✓${NC} Server certificate generated"
else
    echo -e "${GREEN}✓${NC} Server certificate already exists"
fi

echo ""
echo "Generated files:"
echo "  - ${CA_CERT}"
echo "  - ${CA_KEY}"
echo "  - ${SERVER_CERT}"
echo "  - ${SERVER_KEY}"
echo ""
echo "Next steps:"
echo "  1) Trust the CA: sudo ${PROJECT_ROOT}/scripts/product/jarvis-install-tls.sh"
echo "  2) Update /etc/hosts: sudo ${PROJECT_ROOT}/scripts/product/jarvis-setup-hosts.sh"
echo ""
