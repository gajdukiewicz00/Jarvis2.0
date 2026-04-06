#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="jarvis"
VOICE_GATEWAY_SERVICE_PORT="${JARVIS_TLS_SLICE_VOICE_GATEWAY_PORT:-18081}"
SMART_HOME_SERVICE_PORT="${JARVIS_TLS_SLICE_SMART_HOME_PORT:-18086}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-voice-gateway-smart-home-service}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-voice-gateway-smart-home-service.sh [--namespace=jarvis]

Validates the narrow internal TLS slice:
  1. smart-home-service responds over HTTPS on 8086
  2. voice-gateway routes an internal smart-home action to smart-home-service over HTTPS
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
  [[ -n "${voice_pf_pid:-}" ]] && kill "${voice_pf_pid}" >/dev/null 2>&1 || true
  [[ -n "${smart_home_pf_pid:-}" ]] && kill "${smart_home_pf_pid}" >/dev/null 2>&1 || true
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

smoke_user="voice-smart-home-tls-$(date +%s)"
service_token="$(
  python3 - <<'PY' "${service_jwt_secret}" "${smoke_user}"
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
    "svc": "voice-gateway-smart-home-tls-smoke",
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

kubectl -n "${NAMESPACE}" port-forward service/voice-gateway \
  "${VOICE_GATEWAY_SERVICE_PORT}:8081" >"${tmp_dir}/voice-port-forward.log" 2>&1 &
voice_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/smart-home-service \
  "${SMART_HOME_SERVICE_PORT}:8086" >"${tmp_dir}/smart-home-port-forward.log" 2>&1 &
smart_home_pf_pid=$!

wait_for_port_forward "https://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}/actuator/health/readiness" -k
wait_for_port_forward "https://127.0.0.1:${SMART_HOME_SERVICE_PORT}/actuator/health/readiness" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${SMART_HOME_SERVICE_PORT}/actuator/health/readiness" >/dev/null
echo "✅ smart-home-service responds over HTTPS on 8086"

if curl --fail --silent --show-error \
  "http://127.0.0.1:${SMART_HOME_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ smart-home-service still answered plain HTTP on the migrated internal port" >&2
  exit 1
fi
echo "✅ smart-home-service rejects plain HTTP on 8086"

response_file="${tmp_dir}/voice-smart-home-response.json"
curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  -X POST \
  -H "X-Service-Token: ${service_token}" \
  -H 'Content-Type: application/json' \
  --data "{\"userId\":\"${smoke_user}\",\"deviceId\":\"kitchen_light\",\"action\":\"TURN_ON\",\"payload\":\"warm_white\"}" \
  "https://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}/internal/voice/smart-home-action" \
  > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
if payload.get("status") != "dispatched":
    raise SystemExit(f"voice-gateway smart-home response unexpected: {payload}")
print("✅ voice-gateway internal smart-home endpoint returned status=dispatched")
PY

voice_logs=""
for _ in $(seq 1 10); do
  voice_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=voice-gateway -o name 2>/dev/null)
  )"
  if printf '%s\n' "${voice_logs}" | rg -q "${smoke_user}" \
    && printf '%s\n' "${voice_logs}" | rg -q 'https://smart-home-service\.jarvis\.svc\.cluster\.local:8086'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${voice_logs}" | rg -q "${smoke_user}"; then
  echo "❌ voice-gateway logs did not include the unique smart-home smoke user ${smoke_user}" >&2
  exit 1
fi
if ! printf '%s\n' "${voice_logs}" | rg -q 'https://smart-home-service\.jarvis\.svc\.cluster\.local:8086'; then
  echo "❌ voice-gateway logs did not show HTTPS smart-home routing on port 8086" >&2
  exit 1
fi

smart_home_logs=""
for _ in $(seq 1 10); do
  smart_home_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=smart-home-service -o name 2>/dev/null)
  )"
  if printf '%s\n' "${smart_home_logs}" | rg -q "${smoke_user}" \
    && printf '%s\n' "${smart_home_logs}" | rg -q 'kitchen_light'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${smart_home_logs}" | rg -q "${smoke_user}"; then
  echo "❌ smart-home-service logs did not include the unique smart-home smoke user ${smoke_user}" >&2
  exit 1
fi
if ! printf '%s\n' "${smart_home_logs}" | rg -q 'kitchen_light'; then
  echo "❌ smart-home-service logs did not include the traced device kitchen_light" >&2
  exit 1
fi

echo "✅ voice-gateway routed the migrated call to https://smart-home-service.jarvis.svc.cluster.local:8086"
echo "✅ smart-home-service handled the traced voice-gateway action for user ${smoke_user}"
