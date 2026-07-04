#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
INGRESS_NAMESPACE="${JARVIS_INGRESS_NAMESPACE:-ingress-nginx}"
INGRESS_SERVICE="${JARVIS_INGRESS_SERVICE:-ingress-nginx-controller}"
INGRESS_HTTP_PORT="${JARVIS_TLS_INGRESS_HTTP_PORT:-19080}"
INGRESS_HTTPS_PORT="${JARVIS_TLS_INGRESS_HTTPS_PORT:-19443}"
TLS_CA_FILE="${JARVIS_TLS_CA_FILE:-$HOME/.jarvis/tls/jarvis-ca.crt}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh [--namespace=jarvis-prod]

Validates the ingress -> api-gateway internal TLS slice:
  1. jarvis-ingress uses HTTPS backend protocol to api-gateway:8443
  2. edge HTTPS still works through ingress
  3. edge WSS still upgrades successfully through ingress
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "❌ Unknown argument: ${arg}" >&2
      usage >&2
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

detect_kubeconfig() {
  if [[ -n "${KUBECONFIG:-}" ]]; then
    return 0
  fi
  if [[ -r "${HOME}/.jarvis/kubeconfig" ]]; then
    export KUBECONFIG="${HOME}/.jarvis/kubeconfig"
    return 0
  fi
  if [[ -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
  fi
}

wait_for_port_forward() {
  local url="$1"
  shift || true
  local curl_args=("$@")
  local attempts=0
  until curl --silent --output /dev/null "${curl_args[@]}" "${url}" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [[ "${attempts}" -ge 40 ]]; then
      echo "❌ Timed out waiting for port-forward: ${url}" >&2
      exit 1
    fi
    sleep 1
  done
}

curl_ingress() {
  local host="$1"
  local path="$2"
  shift 2
  curl --fail --silent --show-error \
    --cacert "${TLS_CA_FILE}" \
    --resolve "${host}:${INGRESS_HTTPS_PORT}:127.0.0.1" \
    "$@" \
    "https://${host}:${INGRESS_HTTPS_PORT}${path}"
}

require_cmd kubectl
require_cmd curl
require_cmd python3
require_cmd openssl
require_cmd timeout
detect_kubeconfig

[[ -r "${TLS_CA_FILE}" ]] || {
  echo "❌ Missing TLS CA file: ${TLS_CA_FILE}" >&2
  echo "   Set JARVIS_TLS_CA_FILE or generate local certs with ./scripts/product/jarvis-generate-certs.sh" >&2
  exit 1
}

tmp_dir="$(mktemp -d)"
cleanup() {
  [[ -n "${ingress_pf_pid:-}" ]] && kill "${ingress_pf_pid}" >/dev/null 2>&1 || true
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT

backend_protocol="$(kubectl -n "${NAMESPACE}" get ingress jarvis-ingress -o jsonpath='{.metadata.annotations.nginx\.ingress\.kubernetes\.io/backend-protocol}' 2>/dev/null || true)"
if [[ "${backend_protocol}" != "HTTPS" ]]; then
  echo "❌ jarvis-ingress backend protocol is not HTTPS (actual: '${backend_protocol:-<empty>}')" >&2
  exit 1
fi
echo "✅ jarvis-ingress backend protocol is HTTPS"

backend_ports="$(
  kubectl -n "${NAMESPACE}" get ingress jarvis-ingress \
    -o jsonpath='{range .spec.rules[*].http.paths[*]}{.backend.service.name}:{.backend.service.port.number}{"\n"}{end}'
)"
if printf '%s\n' "${backend_ports}" | grep -v '^api-gateway:8443$' >/dev/null; then
  echo "❌ jarvis-ingress contains non-migrated backend ports:" >&2
  printf '%s\n' "${backend_ports}" >&2
  exit 1
fi
echo "✅ jarvis-ingress routes all api-gateway backends to port 8443"

service_ports="$(kubectl -n "${NAMESPACE}" get service api-gateway -o jsonpath='{range .spec.ports[*]}{.port}{"\n"}{end}')"
if ! printf '%s\n' "${service_ports}" | grep -qx '8443'; then
  echo "❌ api-gateway service does not expose port 8443" >&2
  exit 1
fi
echo "✅ api-gateway service exposes internal HTTPS port 8443"

kubectl -n "${INGRESS_NAMESPACE}" port-forward "service/${INGRESS_SERVICE}" \
  "${INGRESS_HTTP_PORT}:80" \
  "${INGRESS_HTTPS_PORT}:443" >"${tmp_dir}/ingress-port-forward.log" 2>&1 &
ingress_pf_pid=$!

wait_for_port_forward "http://127.0.0.1:${INGRESS_HTTP_PORT}/" -H 'Host: api.jarvis.local'
wait_for_port_forward "https://127.0.0.1:${INGRESS_HTTPS_PORT}/actuator/health/readiness" -k -H 'Host: api.jarvis.local'

redirect_headers_file="${tmp_dir}/redirect-headers.txt"
curl --silent --show-error -I \
  --resolve "api.jarvis.local:${INGRESS_HTTP_PORT}:127.0.0.1" \
  "http://api.jarvis.local:${INGRESS_HTTP_PORT}/actuator/health" \
  > "${redirect_headers_file}"
if ! grep -Eq '^HTTP/.* 30[178]' "${redirect_headers_file}"; then
  echo "❌ ingress did not redirect HTTP to HTTPS" >&2
  cat "${redirect_headers_file}" >&2
  exit 1
fi
if ! grep -Eiq '^location: https://api\.jarvis\.local' "${redirect_headers_file}"; then
  echo "❌ ingress redirect location is not HTTPS api.jarvis.local" >&2
  cat "${redirect_headers_file}" >&2
  exit 1
fi
echo "✅ ingress still redirects HTTP to HTTPS"

curl_ingress "api.jarvis.local" "/actuator/health/readiness" >/dev/null
echo "✅ edge HTTPS still reaches api-gateway readiness through ingress"

smoke_username="ingress-tls-smoke-$(date +%s)"
register_response="$(
  curl_ingress "api.jarvis.local" "/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${smoke_username}\",\"password\":\"IngressTlsSmoke!123\",\"role\":\"USER\"}"
)"
access_token="$(python3 - <<'PY' "${register_response}"
import json
import sys
print(json.loads(sys.argv[1])["accessToken"])
PY
)"
user_me_response="$(
  curl_ingress "api.jarvis.local" "/api/v1/security/auth/me" \
    -H "Authorization: Bearer ${access_token}"
)"
user_id="$(python3 - <<'PY' "${user_me_response}"
import json
import sys
print(json.loads(sys.argv[1])["id"])
PY
)"

ws_key="$(openssl rand -base64 16 | tr -d '\n')"
ws_response_file="${tmp_dir}/voice-wss-response.txt"
timeout 10s bash -lc "
  printf 'GET /ws/voice HTTP/1.1\r\nHost: voice.jarvis.local\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${ws_key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer ${access_token}\r\nX-User-Id: ${user_id}\r\nX-Username: ${smoke_username}\r\nX-User-Roles: USER\r\nOrigin: https://voice.jarvis.local\r\n\r\n' \
  | openssl s_client -quiet -connect 127.0.0.1:${INGRESS_HTTPS_PORT} -servername voice.jarvis.local -CAfile '${TLS_CA_FILE}' 2>/dev/null
" | tr -d '\r' > "${ws_response_file}" || true

if ! grep -q '^HTTP/1\.1 101' "${ws_response_file}"; then
  echo "❌ edge WSS handshake did not upgrade successfully through ingress" >&2
  cat "${ws_response_file}" >&2
  exit 1
fi
echo "✅ edge WSS still upgrades successfully through ingress"
