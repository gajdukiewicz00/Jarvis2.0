#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="jarvis"
API_GATEWAY_PORT="${JARVIS_TLS_SLICE_API_GATEWAY_PORT:-18080}"
SECURITY_SERVICE_PORT="${JARVIS_TLS_SLICE_SECURITY_PORT:-18088}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-api-gateway-security-service}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh [--namespace=jarvis]

Validates the sixth internal TLS hop:
  1. security-service only answers on HTTPS
  2. api-gateway completes a real auth flow against security-service over HTTPS
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
require_cmd rg
require_cmd base64
detect_kubeconfig

tmp_dir="$(mktemp -d)"
cleanup() {
  [[ -n "${api_gateway_pf_pid:-}" ]] && kill "${api_gateway_pf_pid}" >/dev/null 2>&1 || true
  [[ -n "${security_pf_pid:-}" ]] && kill "${security_pf_pid}" >/dev/null 2>&1 || true
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT

kubectl -n "${NAMESPACE}" get secret "${TLS_SECRET_NAME}" >/dev/null

ca_file="${tmp_dir}/ca.crt"
kubectl -n "${NAMESPACE}" get secret "${TLS_SECRET_NAME}" -o jsonpath='{.data.ca\.crt}' | base64 -d > "${ca_file}"

kubectl -n "${NAMESPACE}" port-forward service/security-service "${SECURITY_SERVICE_PORT}:8088" >"${tmp_dir}/security-port-forward.log" 2>&1 &
security_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/api-gateway "${API_GATEWAY_PORT}:8080" >"${tmp_dir}/api-gateway-port-forward.log" 2>&1 &
api_gateway_pf_pid=$!

wait_for_port_forward "http://127.0.0.1:${API_GATEWAY_PORT}/actuator/health"
wait_for_port_forward "https://127.0.0.1:${SECURITY_SERVICE_PORT}/actuator/health" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${SECURITY_SERVICE_PORT}/actuator/health/readiness" >/dev/null
echo "✅ security-service readiness responded over HTTPS"

if curl --fail --silent --show-error "http://127.0.0.1:${SECURITY_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ security-service still answered plain HTTP on the internal TLS slice" >&2
  exit 1
fi
echo "✅ security-service rejected plain HTTP on the migrated internal port"

smoke_username="gateway-security-tls-$(date +%s)"
response_file="${tmp_dir}/gateway-security-response.json"
curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${smoke_username}\",\"password\":\"GatewaySecurityTls!123\",\"role\":\"USER\"}" \
  "http://127.0.0.1:${API_GATEWAY_PORT}/auth/register" > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
if not payload.get("accessToken"):
    raise SystemExit(f"gateway auth response did not contain accessToken: {payload}")
print("✅ api-gateway auth proxy returned accessToken from security-service")
PY

api_gateway_logs=""
for _ in $(seq 1 10); do
  api_gateway_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=api-gateway -o name 2>/dev/null)
  )"
  if printf '%s\n' "${api_gateway_logs}" | rg -q "${smoke_username}" \
    && printf '%s\n' "${api_gateway_logs}" | rg -q 'https://security-service\.jarvis\.svc\.cluster\.local:8088'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${api_gateway_logs}" | rg -q "${smoke_username}"; then
  echo "❌ api-gateway logs did not include the unique smoke username ${smoke_username}" >&2
  exit 1
fi
if ! printf '%s\n' "${api_gateway_logs}" | rg -q 'https://security-service\.jarvis\.svc\.cluster\.local:8088'; then
  echo "❌ api-gateway logs did not show HTTPS security-service routing on port 8088" >&2
  exit 1
fi
echo "✅ api-gateway routed the migrated auth call to https://security-service.jarvis.svc.cluster.local:8088"
