#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
ANALYTICS_SERVICE_PORT="${JARVIS_TLS_SLICE_ANALYTICS_PORT:-18087}"
LIFE_TRACKER_SERVICE_PORT="${JARVIS_TLS_SLICE_LIFE_TRACKER_PORT:-18085}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-analytics-service-life-tracker}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-analytics-service-life-tracker.sh [--namespace=jarvis-prod]

Validates the next internal TLS slice:
  1. life-tracker responds over HTTPS on 8085
  2. life-tracker rejects plain HTTP on 8085
  3. analytics-service overview reaches life-tracker over HTTPS without partial-error fallback
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
  [[ -n "${analytics_pf_pid:-}" ]] && kill "${analytics_pf_pid}" >/dev/null 2>&1 || true
  [[ -n "${life_tracker_pf_pid:-}" ]] && kill "${life_tracker_pf_pid}" >/dev/null 2>&1 || true
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

smoke_subject="analytics-life-tracker-tls-smoke-$(date +%s)"
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
    "svc": "analytics-life-tracker-tls-smoke",
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

kubectl -n "${NAMESPACE}" port-forward service/analytics-service \
  "${ANALYTICS_SERVICE_PORT}:8087" >"${tmp_dir}/analytics-port-forward.log" 2>&1 &
analytics_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/life-tracker \
  "${LIFE_TRACKER_SERVICE_PORT}:8085" >"${tmp_dir}/life-tracker-port-forward.log" 2>&1 &
life_tracker_pf_pid=$!

wait_for_port_forward "https://127.0.0.1:${LIFE_TRACKER_SERVICE_PORT}/actuator/health/readiness" -k
wait_for_port_forward "https://127.0.0.1:${ANALYTICS_SERVICE_PORT}/actuator/health/readiness" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${LIFE_TRACKER_SERVICE_PORT}/actuator/health/readiness" >/dev/null
echo "✅ life-tracker responds over HTTPS on 8085"

if curl --fail --silent --show-error \
  "http://127.0.0.1:${LIFE_TRACKER_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ life-tracker accepted plain HTTP on 8085" >&2
  exit 1
fi
echo "✅ life-tracker rejects plain HTTP on 8085"

response_file="${tmp_dir}/analytics-overview-response.json"
curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  -H "X-Service-Token: ${service_token}" \
  -H "X-User-Id: ${smoke_subject}" \
  -H "X-Smoke-Run-Id: ${smoke_subject}" \
  "https://127.0.0.1:${ANALYTICS_SERVICE_PORT}/api/v1/analytics/overview" \
  > "${response_file}"

python3 - <<'PY' "${response_file}"
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text())
if payload.get("expensesError"):
    raise SystemExit(f"analytics overview unexpectedly reported expensesError: {payload}")
if payload.get("timeError"):
    raise SystemExit(f"analytics overview unexpectedly reported timeError: {payload}")
if "expenseCount" not in payload or "timeRecordCount" not in payload:
    raise SystemExit(f"analytics overview missing expected counters: {payload}")
print("✅ analytics overview returned without partial-error fallback")
PY

analytics_logs=""
for _ in $(seq 1 10); do
  analytics_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=analytics-service -o name 2>/dev/null)
  )"
  if printf '%s\n' "${analytics_logs}" | rg -q "${smoke_subject}" \
    && printf '%s\n' "${analytics_logs}" | rg -q 'https://life-tracker\.jarvis\.svc\.cluster\.local:8085'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${analytics_logs}" | rg -q "${smoke_subject}"; then
  echo "❌ analytics-service logs did not include the unique smoke subject ${smoke_subject}" >&2
  exit 1
fi
if ! printf '%s\n' "${analytics_logs}" | rg -q 'https://life-tracker\.jarvis\.svc\.cluster\.local:8085'; then
  echo "❌ analytics-service logs did not show HTTPS life-tracker routing on port 8085" >&2
  exit 1
fi

echo "✅ analytics-service routed the migrated call to https://life-tracker.jarvis-prod.svc.cluster.local:8085"
