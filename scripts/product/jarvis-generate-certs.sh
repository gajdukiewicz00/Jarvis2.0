#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - TLS Certificate Generator (Local CA)
# =============================================================================
# Generates a local CA and a shared server certificate bundle for:
#   - external edge TLS: api.jarvis.local, voice.jarvis.local, grafana.jarvis.local
#   - optional local HTTPS runtime on 127.0.0.1 / localhost
#   - future internal TLS migration with service DNS SANs
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

JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
TLS_DIR="${JARVIS_HOME}/tls"

CA_KEY="${TLS_DIR}/jarvis-ca.key"
CA_CERT="${TLS_DIR}/jarvis-ca.crt"
CA_SERIAL="${TLS_DIR}/jarvis-ca.srl"

SERVER_KEY="${TLS_DIR}/jarvis.key"
SERVER_CSR="${TLS_DIR}/jarvis.csr"
SERVER_CERT="${TLS_DIR}/jarvis.crt"
SERVER_KEYSTORE="${TLS_DIR}/jarvis-keystore.p12"
JAVA_TRUSTSTORE="${TLS_DIR}/jarvis-cacerts.jks"
STORE_PASSWORD="${JARVIS_JAVA_TRUSTSTORE_PASSWORD:-changeit}"

SERVER_DNS_NAMES=(
    "api.jarvis.local"
    "voice.jarvis.local"
    "grafana.jarvis.local"
    "jarvis.local"
    "localhost"
    "*.jarvis.local"
    "api-gateway"
    "security-service"
    "user-profile"
    "nlp-service"
    "orchestrator"
    "voice-gateway"
    "smart-home-service"
    "life-tracker"
    "analytics-service"
    "planner-service"
    "memory-service"
    "pc-control"
    "embedding-service"
    "llm-service"
    "api-gateway.jarvis.svc.cluster.local"
    "security-service.jarvis.svc.cluster.local"
    "user-profile.jarvis.svc.cluster.local"
    "nlp-service.jarvis.svc.cluster.local"
    "orchestrator.jarvis.svc.cluster.local"
    "voice-gateway.jarvis.svc.cluster.local"
    "smart-home-service.jarvis.svc.cluster.local"
    "life-tracker.jarvis.svc.cluster.local"
    "analytics-service.jarvis.svc.cluster.local"
    "planner-service.jarvis.svc.cluster.local"
    "memory-service.jarvis.svc.cluster.local"
    "pc-control.jarvis.svc.cluster.local"
    "embedding-service.jarvis.svc.cluster.local"
    "llm-service.jarvis.svc.cluster.local"
    "*.jarvis.svc.cluster.local"
)

REQUIRED_SERVER_DNS_SANS=(
    "api.jarvis.local"
    "voice.jarvis.local"
    "grafana.jarvis.local"
    "*.jarvis.local"
    "api-gateway.jarvis.svc.cluster.local"
)

server_cert_has_required_sans() {
    local cert_path="$1"
    local cert_text
    local dns_name
    cert_text="$(openssl x509 -in "${cert_path}" -text -noout 2>/dev/null || true)"
    [[ -n "${cert_text}" ]] || return 1

    for dns_name in "${REQUIRED_SERVER_DNS_SANS[@]}"; do
        grep -Fq "DNS:${dns_name}" <<<"${cert_text}" || return 1
    done

    grep -Fq "IP Address:127.0.0.1" <<<"${cert_text}"
}

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
    rm -f \
        "${CA_KEY}" \
        "${CA_CERT}" \
        "${CA_SERIAL}" \
        "${SERVER_KEY}" \
        "${SERVER_CSR}" \
        "${SERVER_CERT}" \
        "${SERVER_KEYSTORE}" \
        "${JAVA_TRUSTSTORE}" || true
fi

if [[ -f "${SERVER_CERT}" && -f "${SERVER_KEY}" ]] && ! server_cert_has_required_sans "${SERVER_CERT}"; then
    echo -e "${YELLOW}⚠️  Existing server certificate is missing required SANs for Grafana/internal TLS.${NC}"
    echo -e "${YELLOW}   Regenerating server certificate bundle with the current SAN set...${NC}"
    rm -f \
        "${SERVER_KEY}" \
        "${SERVER_CSR}" \
        "${SERVER_CERT}" \
        "${SERVER_KEYSTORE}" || true
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
    {
        cat <<'EOC'
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
EOC
        san_index=1
        for dns_name in "${SERVER_DNS_NAMES[@]}"; do
            printf 'DNS.%d = %s\n' "${san_index}" "${dns_name}"
            san_index=$((san_index + 1))
        done
        printf 'IP.1 = 127.0.0.1\n'
    } > "${SAN_CONFIG}"

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
    if ! server_cert_has_required_sans "${SERVER_CERT}"; then
        echo -e "${YELLOW}⚠️  Existing server certificate still does not match the required SAN set.${NC}"
        echo -e "${YELLOW}   Run ${PROJECT_ROOT}/scripts/product/jarvis-generate-certs.sh --force if regeneration keeps failing.${NC}"
    fi
fi

if [[ ! -f "${SERVER_KEYSTORE}" || "${SERVER_CERT}" -nt "${SERVER_KEYSTORE}" || "${SERVER_KEY}" -nt "${SERVER_KEYSTORE}" ]]; then
    echo -e "${YELLOW}→ Generating PKCS12 keystore for local gateway TLS...${NC}"
    openssl pkcs12 -export \
        -in "${SERVER_CERT}" \
        -inkey "${SERVER_KEY}" \
        -out "${SERVER_KEYSTORE}" \
        -name jarvis-local \
        -passout "pass:${STORE_PASSWORD}" >/dev/null 2>&1
    chmod 600 "${SERVER_KEYSTORE}"
    echo -e "${GREEN}✓${NC} PKCS12 keystore generated"
else
    echo -e "${GREEN}✓${NC} PKCS12 keystore already exists"
fi

if command -v keytool >/dev/null 2>&1; then
    if [[ ! -f "${JAVA_TRUSTSTORE}" || "${CA_CERT}" -nt "${JAVA_TRUSTSTORE}" ]]; then
        echo -e "${YELLOW}→ Generating Java truststore for local HTTPS/WSS clients...${NC}"
        rm -f "${JAVA_TRUSTSTORE}"
        keytool -importcert -noprompt \
            -alias jarvis-ca \
            -file "${CA_CERT}" \
            -keystore "${JAVA_TRUSTSTORE}" \
            -storepass "${STORE_PASSWORD}" >/dev/null 2>&1
        chmod 600 "${JAVA_TRUSTSTORE}"
        echo -e "${GREEN}✓${NC} Java truststore generated"
    else
        echo -e "${GREEN}✓${NC} Java truststore already exists"
    fi
else
    echo -e "${YELLOW}⚠️  keytool not found; skipping Java truststore generation${NC}"
fi

echo ""
echo "Generated files:"
echo "  - ${CA_CERT}"
echo "  - ${CA_KEY}"
echo "  - ${SERVER_CERT}"
echo "  - ${SERVER_KEY}"
echo "  - ${SERVER_KEYSTORE}"
if [[ -f "${JAVA_TRUSTSTORE}" ]]; then
    echo "  - ${JAVA_TRUSTSTORE}"
fi
echo ""
echo "Next steps:"
echo "  1) Optional but recommended: trust the CA system-wide: sudo ${PROJECT_ROOT}/scripts/product/jarvis-install-tls.sh"
echo "  2) Update /etc/hosts for edge HTTPS/WSS: sudo ${PROJECT_ROOT}/scripts/product/jarvis-setup-hosts.sh"
echo "  3) Local runtime HTTPS/WSS can use:"
echo "     JARVIS_USE_TLS=true ${PROJECT_ROOT}/scripts/runtime-up.sh"
echo ""
