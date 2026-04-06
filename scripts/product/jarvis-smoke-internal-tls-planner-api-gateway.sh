#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="jarvis"
API_GATEWAY_HTTP_PORT="${JARVIS_TLS_SLICE_API_GATEWAY_HTTP_PORT:-18080}"
API_GATEWAY_HTTPS_PORT="${JARVIS_TLS_SLICE_API_GATEWAY_HTTPS_PORT:-18443}"
PLANNER_SERVICE_PORT="${JARVIS_TLS_SLICE_PLANNER_PORT:-18092}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-planner-api-gateway}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh [--namespace=jarvis]

Validates the second internal TLS slice:
  1. api-gateway keeps HTTP on 8080 for non-migrated callers
  2. api-gateway adds internal HTTPS on 8443
  3. planner-service reaches api-gateway over HTTPS on the migrated path
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
  [[ -n "${planner_pf_pid:-}" ]] && kill "${planner_pf_pid}" >/dev/null 2>&1 || true
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

smoke_subject="planner-tls-smoke-$(date +%s)"
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
    "svc": "planner-tls-smoke",
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
  "${API_GATEWAY_HTTP_PORT}:8080" \
  "${API_GATEWAY_HTTPS_PORT}:8443" >"${tmp_dir}/api-gateway-port-forward.log" 2>&1 &
api_gateway_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/planner-service \
  "${PLANNER_SERVICE_PORT}:8092" >"${tmp_dir}/planner-port-forward.log" 2>&1 &
planner_pf_pid=$!

wait_for_port_forward "http://127.0.0.1:${API_GATEWAY_HTTP_PORT}/actuator/health/readiness"
wait_for_port_forward "https://127.0.0.1:${API_GATEWAY_HTTPS_PORT}/actuator/health/readiness" -k

curl --fail --silent --show-error \
  "http://127.0.0.1:${API_GATEWAY_HTTP_PORT}/actuator/health/readiness" >/dev/null
echo "✅ api-gateway legacy HTTP listener still responds on 8080"

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

planner_url="http://127.0.0.1:${PLANNER_SERVICE_PORT}"
planner_curl_args=()
if curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${PLANNER_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  planner_url="https://127.0.0.1:${PLANNER_SERVICE_PORT}"
  planner_curl_args+=(--cacert "${ca_file}")
  echo "✅ planner-service responds over HTTPS on 8092"
else
  wait_for_port_forward "http://127.0.0.1:${PLANNER_SERVICE_PORT}/actuator/health/readiness"
  echo "✅ planner-service responds over HTTP on 8092"
fi

response_file="${tmp_dir}/planner-focus-response.json"
curl --fail --silent --show-error \
  "${planner_curl_args[@]}" \
  -X POST \
  -H "X-Service-Token: ${service_token}" \
  "${planner_url}/api/v1/planner/actions/focus-mode?mode=WORK" \
  > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
if payload.get("status") != "activated":
    raise SystemExit(f"planner focus-mode response unexpected: {payload}")
print("✅ planner-service action endpoint returned status=activated")
PY

planner_logs="$(kubectl -n "${NAMESPACE}" logs deploy/planner-service --since=2m 2>/dev/null || true)"
if ! printf '%s\n' "${planner_logs}" | rg -q "${smoke_subject}"; then
  echo "❌ planner-service logs did not include the unique smoke subject ${smoke_subject}" >&2
  exit 1
fi
if ! printf '%s\n' "${planner_logs}" | rg -q 'https://api-gateway\.jarvis\.svc\.cluster\.local:8443'; then
  echo "❌ planner-service logs did not show HTTPS api-gateway routing on port 8443" >&2
  exit 1
fi

echo "✅ planner-service routed the migrated call to https://api-gateway.jarvis.svc.cluster.local:8443"
