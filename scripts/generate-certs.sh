#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Self-Signed Certificate Generator
# =============================================================================
# Генерирует self-signed сертификат для jarvis.local
# Использование: ./scripts/generate-certs.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
CERTS_DIR="$PROJECT_ROOT/docker/certs"

# Создаём директорию для сертификатов
mkdir -p "$CERTS_DIR"

echo "=== Jarvis 2.0: Generating self-signed certificates ==="
echo "Output directory: $CERTS_DIR"

# Генерируем приватный ключ и сертификат
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout "$CERTS_DIR/jarvis.key" \
  -out "$CERTS_DIR/jarvis.crt" \
  -subj "/CN=jarvis.local/O=Jarvis/C=RU" \
  -addext "subjectAltName=DNS:jarvis.local,DNS:*.jarvis.local,DNS:localhost,IP:127.0.0.1"

# Устанавливаем правильные права
chmod 600 "$CERTS_DIR/jarvis.key"
chmod 644 "$CERTS_DIR/jarvis.crt"

echo ""
echo "=== Certificates generated successfully ==="
echo ""
echo "Files created:"
echo "  - $CERTS_DIR/jarvis.crt (certificate)"
echo "  - $CERTS_DIR/jarvis.key (private key)"
echo ""
echo "=== Next steps ==="
echo ""
echo "1. Add to /etc/hosts:"
echo "   127.0.0.1 jarvis.local"
echo ""
echo "2. Trust the certificate (Linux/Chrome):"
echo "   sudo cp $CERTS_DIR/jarvis.crt /usr/local/share/ca-certificates/"
echo "   sudo update-ca-certificates"
echo ""
echo "3. Trust the certificate (Firefox):"
echo "   Open Firefox → Settings → Privacy & Security → Certificates → View Certificates"
echo "   Import $CERTS_DIR/jarvis.crt"
echo ""
echo "4. Start the stack:"
echo "   docker-compose -f docker-compose-full.yml up -d"
echo ""
echo "5. Access Jarvis:"
echo "   https://jarvis.local"
echo ""

# Также создаём сертификат для Kubernetes (base64)
echo "=== Creating Kubernetes TLS secret ==="
BASE64_CRT=$(base64 -w 0 "$CERTS_DIR/jarvis.crt")
BASE64_KEY=$(base64 -w 0 "$CERTS_DIR/jarvis.key")

cat > "$PROJECT_ROOT/k8s/base/tls-secret-generated.yaml" << EOF
# =============================================================================
# Jarvis 2.0 - TLS Secret (Generated)
# =============================================================================
# Сгенерировано: $(date)
# Применить: kubectl apply -f k8s/base/tls-secret-generated.yaml
# =============================================================================
apiVersion: v1
kind: Secret
metadata:
  name: jarvis-tls
  namespace: jarvis
  labels:
    app.kubernetes.io/name: jarvis-tls
    app.kubernetes.io/part-of: jarvis
type: kubernetes.io/tls
data:
  tls.crt: $BASE64_CRT
  tls.key: $BASE64_KEY
EOF

echo "Kubernetes TLS secret created: k8s/base/tls-secret-generated.yaml"
echo ""
echo "=== Done ==="

