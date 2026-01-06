#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - TLS/CA Trust Store Installation
# =============================================================================
# Iteration 1.5 (Stage 7): Install self-signed CA certificate into system trust store
# =============================================================================

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CERTS_DIR="${PROJECT_ROOT}/docker/certs"
CA_CERT="${CERTS_DIR}/jarvis.crt"  # Using server cert as CA for self-signed
TRUST_STORE="/usr/local/share/ca-certificates/jarvis-ca.crt"

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
    echo -e "${RED}❌ Certificate not found: $CA_CERT${NC}"
    echo "Please run jarvis-launch.sh first to generate certificates."
    exit 1
fi

# Check if running as root (required for trust store installation)
if [ "$EUID" -ne 0 ]; then
    echo -e "${YELLOW}⚠️  This script requires sudo privileges to install CA certificate.${NC}"
    echo "Please run: sudo $0"
    exit 1
fi

# Copy CA certificate to trust store
echo -e "${YELLOW}Installing CA certificate to system trust store...${NC}"
cp "$CA_CERT" "$TRUST_STORE"
chmod 644 "$TRUST_STORE"

# Update CA certificates
echo -e "${YELLOW}Updating CA certificates...${NC}"
update-ca-certificates

echo ""
echo -e "${GREEN}✅ CA certificate installed successfully${NC}"
echo ""
echo "Certificate location: $TRUST_STORE"
echo ""
echo "Verification:"
echo "  openssl s_client -connect api.jarvis.local:443 -CAfile $TRUST_STORE < /dev/null 2>/dev/null | grep 'Verify return code'"
echo "  Expected: Verify return code: 0 (ok)"
echo ""

