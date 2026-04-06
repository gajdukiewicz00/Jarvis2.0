#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="jarvis"
PLANNER_SERVICE_PORT="${JARVIS_TLS_SLICE_PLANNER_PORT:-18092}"
VOICE_GATEWAY_SERVICE_PORT="${JARVIS_TLS_SLICE_VOICE_GATEWAY_PORT:-18081}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-planner-service-voice-gateway}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-planner-service-voice-gateway.sh [--namespace=jarvis]

Validates the next internal TLS slice:
  1. voice-gateway responds over HTTPS on 8081
  2. planner-service routes an internal voice notification request to voice-gateway over HTTPS
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
  [[ -n "${planner_pf_pid:-}" ]] && kill "${planner_pf_pid}" >/dev/null 2>&1 || true
  [[ -n "${voice_pf_pid:-}" ]] && kill "${voice_pf_pid}" >/dev/null 2>&1 || true
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

smoke_user="planner-voice-gateway-tls-$(date +%s)"
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
    "svc": "planner-voice-gateway-tls-smoke",
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

kubectl -n "${NAMESPACE}" port-forward service/planner-service \
  "${PLANNER_SERVICE_PORT}:8092" >"${tmp_dir}/planner-port-forward.log" 2>&1 &
planner_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/voice-gateway \
  "${VOICE_GATEWAY_SERVICE_PORT}:8081" >"${tmp_dir}/voice-port-forward.log" 2>&1 &
voice_pf_pid=$!

wait_for_port_forward "https://127.0.0.1:${PLANNER_SERVICE_PORT}/actuator/health/readiness" -k
wait_for_port_forward "https://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}/actuator/health/readiness" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}/actuator/health/readiness" >/dev/null
echo "✅ voice-gateway responds over HTTPS on 8081"

if curl --fail --silent --show-error \
  "http://127.0.0.1:${VOICE_GATEWAY_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ voice-gateway still answered plain HTTP on the migrated internal port" >&2
  exit 1
fi
echo "✅ voice-gateway rejects plain HTTP on 8081"

response_file="${tmp_dir}/planner-voice-response.json"
curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  -X POST \
  -H "X-Service-Token: ${service_token}" \
  -H 'Content-Type: application/json' \
  --data "{\"userId\":\"${smoke_user}\",\"message\":\"Внутренний TLS smoke\"}" \
  "https://127.0.0.1:${PLANNER_SERVICE_PORT}/internal/planner/voice-notify" \
  > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
if payload.get("status") not in {"delivered", "not_delivered"}:
    raise SystemExit(f"planner internal voice notification response unexpected: {payload}")
print("✅ planner internal voice notification endpoint returned a structured response")
PY

planner_logs=""
for _ in $(seq 1 10); do
  planner_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=planner-service -o name 2>/dev/null)
  )"
  if printf '%s\n' "${planner_logs}" | rg -q "${smoke_user}" \
    && printf '%s\n' "${planner_logs}" | rg -q 'https://voice-gateway\.jarvis\.svc\.cluster\.local:8081'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${planner_logs}" | rg -q "${smoke_user}"; then
  echo "❌ planner-service logs did not include the unique planner voice smoke user ${smoke_user}" >&2
  exit 1
fi
if ! printf '%s\n' "${planner_logs}" | rg -q 'https://voice-gateway\.jarvis\.svc\.cluster\.local:8081'; then
  echo "❌ planner-service logs did not show HTTPS voice-gateway routing on port 8081" >&2
  exit 1
fi

voice_logs=""
for _ in $(seq 1 10); do
  voice_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=voice-gateway -o name 2>/dev/null)
  )"
  if printf '%s\n' "${voice_logs}" | rg -q "${smoke_user}"; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${voice_logs}" | rg -q "${smoke_user}"; then
  echo "❌ voice-gateway logs did not include the unique planner voice smoke user ${smoke_user}" >&2
  exit 1
fi

echo "✅ planner-service routed the migrated call to https://voice-gateway.jarvis.svc.cluster.local:8081"
echo "✅ voice-gateway handled the traced planner notification request for user ${smoke_user}"
