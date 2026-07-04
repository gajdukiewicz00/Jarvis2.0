#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
API_GATEWAY_PORT="${JARVIS_TLS_SLICE_API_GATEWAY_PORT:-18080}"
ORCHESTRATOR_PORT="${JARVIS_TLS_SLICE_ORCHESTRATOR_PORT:-18083}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-api-gateway-orchestrator}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-api-gateway-orchestrator.sh [--namespace=jarvis-prod]

Validates the eleventh internal TLS hop:
  1. orchestrator only answers on HTTPS
  2. api-gateway completes a real orchestrator execute flow against orchestrator over HTTPS
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
  [[ -n "${orchestrator_pf_pid:-}" ]] && kill "${orchestrator_pf_pid}" >/dev/null 2>&1 || true
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT

kubectl -n "${NAMESPACE}" get secret "${TLS_SECRET_NAME}" >/dev/null

ca_file="${tmp_dir}/ca.crt"
kubectl -n "${NAMESPACE}" get secret "${TLS_SECRET_NAME}" -o jsonpath='{.data.ca\.crt}' | base64 -d > "${ca_file}"

kubectl -n "${NAMESPACE}" port-forward service/orchestrator "${ORCHESTRATOR_PORT}:8083" >"${tmp_dir}/orchestrator-port-forward.log" 2>&1 &
orchestrator_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/api-gateway "${API_GATEWAY_PORT}:8080" >"${tmp_dir}/api-gateway-port-forward.log" 2>&1 &
api_gateway_pf_pid=$!

wait_for_port_forward "http://127.0.0.1:${API_GATEWAY_PORT}/actuator/health"
wait_for_port_forward "https://127.0.0.1:${ORCHESTRATOR_PORT}/actuator/health" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${ORCHESTRATOR_PORT}/actuator/health/readiness" >/dev/null
echo "✅ orchestrator readiness responded over HTTPS"

if curl --fail --silent --show-error "http://127.0.0.1:${ORCHESTRATOR_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ orchestrator still answered plain HTTP on the internal TLS slice" >&2
  exit 1
fi
echo "✅ orchestrator rejected plain HTTP on the migrated internal port"

smoke_username="gateway-orchestrator-tls-$(date +%s)"
auth_response_file="${tmp_dir}/gateway-auth-response.json"
curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${smoke_username}\",\"password\":\"GatewayOrchestratorTls!123\",\"role\":\"USER\"}" \
  "http://127.0.0.1:${API_GATEWAY_PORT}/auth/register" > "${auth_response_file}"

access_token="$(python3 - <<'PY' "${auth_response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
token = payload.get("accessToken")
if not token:
    raise SystemExit(f"gateway auth response did not contain accessToken: {payload}")
print(token)
PY
)"
echo "✅ api-gateway auth proxy returned accessToken for orchestrator smoke"

smoke_run_id="orchestrator-hop-$(date +%s)"
orchestrator_response_file="${tmp_dir}/gateway-orchestrator-response.txt"
curl --fail --silent --show-error \
  -H "Authorization: Bearer ${access_token}" \
  -H "X-Smoke-Run-Id: ${smoke_run_id}" \
  -H 'Content-Type: application/json' \
  -d "{\"intent\":\"hello\",\"language\":\"en\",\"correlationId\":\"${smoke_run_id}\",\"originalText\":\"hello from tls smoke\"}" \
  "http://127.0.0.1:${API_GATEWAY_PORT}/api/v1/orchestrator/execute" > "${orchestrator_response_file}"

python3 - <<'PY' "${orchestrator_response_file}"
import pathlib
import sys

payload = pathlib.Path(sys.argv[1]).read_text().strip()
if not payload:
    raise SystemExit("orchestrator response was empty")
print("✅ api-gateway orchestrator proxy returned a non-empty execute response")
PY

api_gateway_logs=""
for _ in $(seq 1 10); do
  api_gateway_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=api-gateway -o name 2>/dev/null)
  )"
  if printf '%s\n' "${api_gateway_logs}" | rg -q "${smoke_run_id}" \
    && printf '%s\n' "${api_gateway_logs}" | rg -q 'https://orchestrator\.jarvis\.svc\.cluster\.local:8083'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${api_gateway_logs}" | rg -q "${smoke_run_id}"; then
  echo "❌ api-gateway logs did not include the unique smoke run id ${smoke_run_id}" >&2
  exit 1
fi
if ! printf '%s\n' "${api_gateway_logs}" | rg -q 'https://orchestrator\.jarvis\.svc\.cluster\.local:8083'; then
  echo "❌ api-gateway logs did not show HTTPS orchestrator routing on port 8083" >&2
  exit 1
fi

orchestrator_logs=""
for _ in $(seq 1 10); do
  orchestrator_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=orchestrator -o name 2>/dev/null)
  )"
  if printf '%s\n' "${orchestrator_logs}" | rg -q "${smoke_run_id}"; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${orchestrator_logs}" | rg -q "${smoke_run_id}"; then
  echo "❌ orchestrator logs did not include the unique smoke correlation id ${smoke_run_id}" >&2
  exit 1
fi
echo "✅ api-gateway routed the migrated orchestrator call to https://orchestrator.jarvis-prod.svc.cluster.local:8083"
