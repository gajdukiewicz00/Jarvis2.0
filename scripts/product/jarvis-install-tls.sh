#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - TLS/CA Trust Store Installation
# =============================================================================
# Iteration 1.5 (Stage 7): Install self-signed CA certificate into system trust store
# =============================================================================

set -euo pipefail

JARVIS_HOME="${JARVIS_HOME:-}"
if [[ -z "${JARVIS_HOME}" ]]; then
    if [[ "${EUID}" -eq 0 && -n "${SUDO_USER:-}" ]]; then
        USER_HOME=$(getent passwd "${SUDO_USER}" | cut -d: -f6 || true)
        if [[ -n "${USER_HOME}" ]]; then
            JARVIS_HOME="${USER_HOME}/.jarvis"
        else
            JARVIS_HOME="${HOME}/.jarvis"
        fi
    elif [[ "${EUID}" -eq 0 && -n "${PKEXEC_UID:-}" ]]; then
        USER_HOME=$(getent passwd "${PKEXEC_UID}" | cut -d: -f6 || true)
        if [[ -n "${USER_HOME}" ]]; then
            JARVIS_HOME="${USER_HOME}/.jarvis"
        else
            JARVIS_HOME="${HOME}/.jarvis"
        fi
    else
        JARVIS_HOME="${HOME}/.jarvis"
    fi
fi
TLS_DIR="${JARVIS_HOME}/tls"
CA_CERT="${TLS_DIR}/jarvis-ca.crt"
TRUST_STORE="/usr/local/share/ca-certificates/jarvis-ca.crt"
JAVA_TRUSTSTORE="${TLS_DIR}/jarvis-cacerts.jks"
JAVA_TRUSTSTORE_PASS="${JARVIS_JAVA_TRUSTSTORE_PASSWORD:-changeit}"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo "=========================================="
echo "Jarvis 2.0 - TLS CA Trust Store Installation"
echo "=========================================="
echo ""

# Check if cert exists
if [ ! -f "$CA_CERT" ]; then
    echo -e "${RED}❌ CA certificate not found: $CA_CERT${NC}"
    echo "Generate certificates first:"
    echo "  ./scripts/product/jarvis-generate-certs.sh"
    exit 1
fi

update_java_truststore() {
    local keytool_bin=""
    if command -v keytool >/dev/null 2>&1; then
        keytool_bin="keytool"
    elif command -v java >/dev/null 2>&1; then
        local java_home
        java_home=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
        if [[ -x "${java_home}/bin/keytool" ]]; then
            keytool_bin="${java_home}/bin/keytool"
        fi
    fi

    if [[ -z "${keytool_bin}" ]]; then
        echo -e "${YELLOW}⚠️  keytool not found; skipping Java trust store update${NC}"
        return 0
    fi

    echo -e "${YELLOW}Updating Java trust stores...${NC}"

    # User-level truststore for launcher/desktop
    if ! "${keytool_bin}" -list -keystore "${JAVA_TRUSTSTORE}" -storepass "${JAVA_TRUSTSTORE_PASS}" -alias jarvis-ca >/dev/null 2>&1; then
        "${keytool_bin}" -importcert -noprompt \
            -alias jarvis-ca \
            -file "${CA_CERT}" \
            -keystore "${JAVA_TRUSTSTORE}" \
            -storepass "${JAVA_TRUSTSTORE_PASS}" >/dev/null 2>&1 || true
    fi
    chmod 600 "${JAVA_TRUSTSTORE}" || true
    if [[ "${EUID}" -eq 0 && -n "${SUDO_USER:-}" ]]; then
        chown "${SUDO_USER}:${SUDO_USER}" "${JAVA_TRUSTSTORE}" >/dev/null 2>&1 || true
    elif [[ "${EUID}" -eq 0 && -n "${PKEXEC_UID:-}" ]]; then
        local owner
        owner=$(getent passwd "${PKEXEC_UID}" | cut -d: -f1 || true)
        if [[ -n "${owner}" ]]; then
            chown "${owner}:${owner}" "${JAVA_TRUSTSTORE}" >/dev/null 2>&1 || true
        fi
    fi

    # System Java truststore (best-effort)
    local system_cacerts=""
    if [[ -n "${JAVA_HOME:-}" && -f "${JAVA_HOME}/lib/security/cacerts" ]]; then
        system_cacerts="${JAVA_HOME}/lib/security/cacerts"
    elif command -v java >/dev/null 2>&1; then
        local java_home
        java_home=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
        if [[ -f "${java_home}/lib/security/cacerts" ]]; then
            system_cacerts="${java_home}/lib/security/cacerts"
        fi
    fi
    if [[ -z "${system_cacerts}" && -f "/etc/ssl/certs/java/cacerts" ]]; then
        system_cacerts="/etc/ssl/certs/java/cacerts"
    fi

    if [[ -n "${system_cacerts}" ]]; then
        if ! "${keytool_bin}" -list -keystore "${system_cacerts}" -storepass changeit -alias jarvis-ca >/dev/null 2>&1; then
            "${keytool_bin}" -importcert -noprompt \
                -alias jarvis-ca \
                -file "${CA_CERT}" \
                -keystore "${system_cacerts}" \
                -storepass changeit >/dev/null 2>&1 || true
        fi
    else
        echo -e "${YELLOW}⚠️  System Java cacerts not found; launcher will use ${JAVA_TRUSTSTORE}${NC}"
    fi
}

if [ "$EUID" -ne 0 ]; then
    update_java_truststore
    echo ""
    echo -e "${YELLOW}⚠️  Run with sudo to install CA into system trust store.${NC}"
    echo "  sudo $0"
    exit 0
fi

# Copy CA certificate to trust store
echo -e "${YELLOW}Installing CA certificate to system trust store...${NC}"
cp "$CA_CERT" "$TRUST_STORE"
chmod 644 "$TRUST_STORE"

# Update CA certificates
echo -e "${YELLOW}Updating CA certificates...${NC}"
update-ca-certificates

update_java_truststore

echo ""
echo -e "${GREEN}✅ CA certificate installed successfully${NC}"
echo ""
echo "Certificate location: $TRUST_STORE"
echo "Java truststore: ${JAVA_TRUSTSTORE}"
echo ""
echo "Verification:"
echo "  openssl s_client -connect api.jarvis.local:443 -CAfile $TRUST_STORE < /dev/null 2>/dev/null | grep 'Verify return code'"
echo "  Expected: Verify return code: 0 (ok)"
echo ""

