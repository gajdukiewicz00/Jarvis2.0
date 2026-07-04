#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
API_GATEWAY_PORT="${JARVIS_TLS_SLICE_API_GATEWAY_PORT:-18080}"
NLP_SERVICE_PORT="${JARVIS_TLS_SLICE_NLP_PORT:-18082}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-api-gateway-nlp}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh [--namespace=jarvis-prod]

Validates the first internal TLS slice:
  1. nlp-service only answers on HTTPS
  2. api-gateway can still proxy /api/v1/nlp/analyze successfully
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
detect_kubeconfig

tmp_dir="$(mktemp -d)"
cleanup() {
  [[ -n "${api_gateway_pf_pid:-}" ]] && kill "${api_gateway_pf_pid}" >/dev/null 2>&1 || true
  [[ -n "${nlp_pf_pid:-}" ]] && kill "${nlp_pf_pid}" >/dev/null 2>&1 || true
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

service_token="$(
  python3 - <<'PY' "${service_jwt_secret}"
import base64
import hashlib
import hmac
import json
import sys
import time

secret = sys.argv[1].encode()

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

header = {"alg": "HS256", "typ": "JWT"}
now = int(time.time())
payload = {
    "sub": "tls-smoke",
    "iss": "jarvis-internal",
    "aud": "jarvis-services",
    "iat": now,
    "exp": now + 300,
    "token_type": "service",
    "svc": "tls-smoke",
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

kubectl -n "${NAMESPACE}" port-forward service/nlp-service "${NLP_SERVICE_PORT}:8082" >"${tmp_dir}/nlp-port-forward.log" 2>&1 &
nlp_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/api-gateway "${API_GATEWAY_PORT}:8080" >"${tmp_dir}/api-gateway-port-forward.log" 2>&1 &
api_gateway_pf_pid=$!

wait_for_port_forward "http://127.0.0.1:${API_GATEWAY_PORT}/actuator/health"
wait_for_port_forward "https://127.0.0.1:${NLP_SERVICE_PORT}/actuator/health" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${NLP_SERVICE_PORT}/actuator/health/readiness" >/dev/null
echo "✅ nlp-service readiness responded over HTTPS"

if curl --fail --silent --show-error "http://127.0.0.1:${NLP_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ nlp-service still answered plain HTTP on the internal TLS slice" >&2
  exit 1
fi
echo "✅ nlp-service rejected plain HTTP on the migrated internal port"

response_file="${tmp_dir}/gateway-nlp-response.json"
curl --fail --silent --show-error \
  -H "Content-Type: application/json" \
  -H "X-Service-Token: ${service_token}" \
  -d '{"text":"turn on the kitchen light","locale":"en"}' \
  "http://127.0.0.1:${API_GATEWAY_PORT}/api/v1/nlp/analyze" > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
intent = payload.get("intent")
if not intent:
    raise SystemExit("gateway NLP response did not contain an intent")
print(f"✅ gateway -> nlp internal TLS slice returned intent={intent}")
PY
