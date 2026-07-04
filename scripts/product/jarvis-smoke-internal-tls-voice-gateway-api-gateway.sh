#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
API_GATEWAY_HTTPS_PORT="${JARVIS_TLS_SLICE_API_GATEWAY_HTTPS_PORT:-18443}"
VOICE_GATEWAY_SERVICE_PORT="${JARVIS_TLS_SLICE_VOICE_GATEWAY_PORT:-18081}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-voice-gateway-api-gateway}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh [--namespace=jarvis-prod]

Validates the fourth internal TLS slice:
  1. api-gateway internal HTTPS listener on 8443 is reachable
  2. voice-gateway routes a direct PC action to api-gateway over HTTPS
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

require_cmd kubectl
require_cmd curl
require_cmd python3
require_cmd base64
require_cmd rg
detect_kubeconfig

tmp_dir="$(mktemp -d)"
cleanup() {
  [[ -n "${api_gateway_pf_pid:-}" ]] && kill "${api_gateway_pf_pid}" >/dev/null 2>&1 || true
  [[ -n "${voice_gateway_pf_pid:-}" ]] && kill "${voice_gateway_pf_pid}" >/dev/null 2>&1 || true
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT

kubectl -n "${NAMESPACE}" get secret "${SERVICE_SECRET_NAME}" >/dev/null
kubectl -n "${NAMESPACE}" get secret "${TLS_SECRET_NAME}" >/dev/null

service_jwt_secret="$(
  kubectl -n "${NAMESPACE}" get secret "${SERVICE_SECRET_NAME}" -o jsonpath='{.data.SERVICE_JWT_SECRET}' | base64 -d
)"
ca_file="${tmp_dir}/ca.crt"
kubectl -n "${NAMESPACE}" get secret "${TLS_SECRET_NAME}" -o jsonpath='{.data.ca\.crt}' | base64 -d > "${ca_file}"

smoke_subject="voice-gateway-tls-smoke-$(date +%s)"
service_token="$(
  python3 - <<'PY' "${service_jwt_secret}" "${smoke_subject}"
import base64
import hashlib
import hmac
import json
import sys
import time

secret = sys.argv[1].encode()
subject = sys.argv[2]

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

header = {"alg": "HS256", "typ": "JWT"}
now = int(time.time())
payload = {
    "sub": subject,
    "iss": "jarvis-internal",
    "aud": "jarvis-services",
    "iat": now,
    "exp": now + 300,
    "token_type": "service",
    "svc": "voice-gateway-tls-smoke",
    "roles": ["SVC_INTERNAL"],
}
segments = [
    b64url(json.dumps(header, separators=(",", ":")).encode()),
    b64url(json.dumps(payload, separators=(",", ":")).encode()),
]
signing_input = ".".join(segments).encode()
signature = hmac.new(secret, signing_input, hashlib.sha256).digest()
print(".".join([segments[0], segments[1], b64url(signature)]))
PY
)"

kubectl -n "${NAMESPACE}" port-forward service/api-gateway \
  "${API_GATEWAY_HTTPS_PORT}:8443" >"${tmp_dir}/api-gateway-port-forward.log" 2>&1 &
api_gateway_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/voice-gateway \
  "${VOICE_GATEWAY_SERVICE_PORT}:8081" >"${tmp_dir}/voice-gateway-port-forward.log" 2>&1 &
voice_gateway_pf_pid=$!

wait_for_port_forward "https://127.0.0.1:${API_GATEWAY_HTTPS_PORT}/actuator/health/readiness" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${API_GATEWAY_HTTPS_PORT}/actuator/health/readiness" >/dev/null
echo "✅ api-gateway internal HTTPS listener responds on 8443"

if curl --fail --silent --show-error \
  "http://127.0.0.1:${API_GATEWAY_HTTPS_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ api-gateway internal HTTPS listener accepted plain HTTP on 8443" >&2
  exit 1
fi
echo "✅ api-gateway internal HTTPS listener rejects plain HTTP on 8443"

voice_gateway_url="http://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}"
voice_gateway_curl_args=()
if curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  voice_gateway_url="https://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}"
  voice_gateway_curl_args+=(--cacert "${ca_file}")
  echo "✅ voice-gateway service responds over HTTPS on 8081"
else
  wait_for_port_forward "http://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}/actuator/health/readiness"
  echo "✅ voice-gateway service responds over HTTP on 8081"
fi

response_file="${tmp_dir}/voice-gateway-pc-action-response.json"
curl --fail --silent --show-error \
  "${voice_gateway_curl_args[@]}" \
  -X POST \
  -H "X-Service-Token: ${service_token}" \
  -H 'Content-Type: application/json' \
  --data "{\"action\":\"VOLUME_UP\",\"params\":{\"delta\":13},\"userId\":\"${smoke_subject}\"}" \
  "${voice_gateway_url}/internal/voice/pc-action" \
  > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
if payload.get("status") != "dispatched":
    raise SystemExit(f"voice-gateway internal pc-action response unexpected: {payload}")
print("✅ voice-gateway internal pc-action endpoint returned status=dispatched")
PY

voice_logs=""
for _ in $(seq 1 10); do
  voice_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=voice-gateway -o name 2>/dev/null)
  )"
  if printf '%s\n' "${voice_logs}" | rg -q "${smoke_subject}" \
    && printf '%s\n' "${voice_logs}" | rg -q 'https://api-gateway\.jarvis\.svc\.cluster\.local:8443' \
    && printf '%s\n' "${voice_logs}" | rg -q 'Voice gateway PC action routed'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${voice_logs}" | rg -q "${smoke_subject}"; then
  echo "❌ voice-gateway logs did not include the unique smoke subject ${smoke_subject}" >&2
  exit 1
fi
if ! printf '%s\n' "${voice_logs}" | rg -q 'https://api-gateway\.jarvis\.svc\.cluster\.local:8443'; then
  echo "❌ voice-gateway logs did not show HTTPS api-gateway routing on port 8443" >&2
  exit 1
fi
if ! printf '%s\n' "${voice_logs}" | rg -q 'Voice gateway PC action routed'; then
  echo "❌ voice-gateway logs did not record successful API Gateway PC action routing" >&2
  exit 1
fi

echo "✅ voice-gateway routed the migrated call to https://api-gateway.jarvis-prod.svc.cluster.local:8443"
