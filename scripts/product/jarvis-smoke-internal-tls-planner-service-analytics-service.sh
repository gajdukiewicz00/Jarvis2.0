#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
PLANNER_SERVICE_PORT="${JARVIS_TLS_SLICE_PLANNER_PORT:-18092}"
ANALYTICS_SERVICE_PORT="${JARVIS_TLS_SLICE_ANALYTICS_PORT:-18087}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-planner-service-analytics-service}"
VERIFIED_USER_ID="${JARVIS_TLS_SLICE_VERIFIED_USER_ID:-denis}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-planner-service-analytics-service.sh [--namespace=jarvis-prod]

Validates the next internal TLS slice:
  1. analytics-service responds over HTTPS on 8087
  2. planner-service habit analysis reaches analytics-service over HTTPS
  3. traced planner and analytics logs prove both migrated HTTPS calls
  4. planner-service returns at least one concrete upstream analytics metric
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
  [[ -n "${analytics_pf_pid:-}" ]] && kill "${analytics_pf_pid}" >/dev/null 2>&1 || true
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

smoke_run_id="planner-analytics-tls-smoke-$(date +%s)"
service_token="$(
  python3 - <<'PY' "${service_jwt_secret}" "${VERIFIED_USER_ID}"
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
    "svc": "planner-analytics-tls-smoke",
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
kubectl -n "${NAMESPACE}" port-forward service/analytics-service \
  "${ANALYTICS_SERVICE_PORT}:8087" >"${tmp_dir}/analytics-port-forward.log" 2>&1 &
analytics_pf_pid=$!

wait_for_port_forward "https://127.0.0.1:${ANALYTICS_SERVICE_PORT}/actuator/health/readiness" -k

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

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${ANALYTICS_SERVICE_PORT}/actuator/health/readiness" >/dev/null
echo "✅ analytics-service responds over HTTPS on 8087"

response_file="${tmp_dir}/planner-habits-response.json"
curl --fail --silent --show-error \
  "${planner_curl_args[@]}" \
  -H "X-Service-Token: ${service_token}" \
  -H "X-Smoke-Run-Id: ${smoke_run_id}" \
  "${planner_url}/api/v1/planner/analytics/habits" \
  > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
sleep_block = payload.get("sleep") or {}
work_block = payload.get("work") or {}
if sleep_block.get("averageHours") is None and work_block.get("weeklyOvertime") is None:
    raise SystemExit(f"planner habits response did not return any concrete upstream analytics metric: {payload}")
print("✅ planner habits response returned at least one concrete analytics metric")
PY

planner_logs=""
for _ in $(seq 1 10); do
  planner_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=planner-service -o name 2>/dev/null)
  )"
  if printf '%s\n' "${planner_logs}" | rg -q "${smoke_run_id}" \
    && printf '%s\n' "${planner_logs}" | rg -q 'https://analytics-service\.jarvis\.svc\.cluster\.local:8087'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${planner_logs}" | rg -q "${smoke_run_id}"; then
  echo "❌ planner-service logs did not include the unique smokeRunId ${smoke_run_id}" >&2
  exit 1
fi
if ! printf '%s\n' "${planner_logs}" | rg -q 'https://analytics-service\.jarvis\.svc\.cluster\.local:8087'; then
  echo "❌ planner-service logs did not show HTTPS analytics-service routing on port 8087" >&2
  exit 1
fi

analytics_logs=""
for _ in $(seq 1 10); do
  analytics_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=analytics-service -o name 2>/dev/null)
  )"
  if printf '%s\n' "${analytics_logs}" | rg -q "${smoke_run_id}"; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${analytics_logs}" | rg -q "${smoke_run_id}"; then
  echo "❌ analytics-service logs did not include the unique smokeRunId ${smoke_run_id}" >&2
  exit 1
fi
if ! printf '%s\n' "${analytics_logs}" | rg -q 'Getting sleep summary'; then
  echo "❌ analytics-service logs did not show the traced sleep summary call" >&2
  exit 1
fi
if ! printf '%s\n' "${analytics_logs}" | rg -q 'Getting overtime summary'; then
  echo "❌ analytics-service logs did not show the traced overtime summary call" >&2
  exit 1
fi

echo "✅ planner-service routed the migrated call to https://analytics-service.jarvis-prod.svc.cluster.local:8087"
echo "✅ analytics-service handled the traced planner analytics request for smokeRunId ${smoke_run_id}"
