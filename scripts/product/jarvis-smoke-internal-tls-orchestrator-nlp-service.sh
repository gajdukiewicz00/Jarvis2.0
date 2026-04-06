#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="jarvis"
ORCHESTRATOR_SERVICE_PORT="${JARVIS_TLS_SLICE_ORCHESTRATOR_PORT:-18083}"
NLP_SERVICE_PORT="${JARVIS_TLS_SLICE_NLP_PORT:-18082}"
SERVICE_SECRET_NAME="${JARVIS_TLS_SLICE_SERVICE_SECRET_NAME:-jarvis-secrets}"
TLS_SECRET_NAME="${JARVIS_TLS_SLICE_TLS_SECRET_NAME:-jarvis-internal-tls-orchestrator-nlp-service}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-smoke-internal-tls-orchestrator-nlp-service.sh [--namespace=jarvis]

Validates the next internal TLS slice:
  1. nlp-service responds over HTTPS on 8082
  2. nlp-service rejects plain HTTP on 8082
  3. orchestrator text processing reaches nlp-service over HTTPS
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
  [[ -n "${orchestrator_pf_pid:-}" ]] && kill "${orchestrator_pf_pid}" >/dev/null 2>&1 || true
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

smoke_subject="orchestrator-nlp-tls-smoke-$(date +%s)"
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
    "svc": "orchestrator-nlp-tls-smoke",
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

kubectl -n "${NAMESPACE}" port-forward service/orchestrator \
  "${ORCHESTRATOR_SERVICE_PORT}:8083" >"${tmp_dir}/orchestrator-port-forward.log" 2>&1 &
orchestrator_pf_pid=$!
kubectl -n "${NAMESPACE}" port-forward service/nlp-service \
  "${NLP_SERVICE_PORT}:8082" >"${tmp_dir}/nlp-port-forward.log" 2>&1 &
nlp_pf_pid=$!

wait_for_port_forward "https://127.0.0.1:${NLP_SERVICE_PORT}/actuator/health/readiness" -k
wait_for_port_forward "https://127.0.0.1:${ORCHESTRATOR_SERVICE_PORT}/actuator/health/readiness" -k

curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  "https://127.0.0.1:${NLP_SERVICE_PORT}/actuator/health/readiness" >/dev/null
echo "✅ nlp-service responds over HTTPS on 8082"

if curl --fail --silent --show-error \
  "http://127.0.0.1:${NLP_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
  echo "❌ nlp-service accepted plain HTTP on 8082" >&2
  exit 1
fi
echo "✅ nlp-service rejects plain HTTP on 8082"

response_file="${tmp_dir}/orchestrator-text-response.txt"
curl --fail --silent --show-error \
  --cacert "${ca_file}" \
  -X POST \
  -H "X-Service-Token: ${service_token}" \
  -H 'Content-Type: application/json' \
  --data "{\"text\":\"${smoke_subject}\",\"language\":\"en\",\"correlationId\":\"${smoke_subject}\"}" \
  "https://127.0.0.1:${ORCHESTRATOR_SERVICE_PORT}/api/v1/orchestrator/execute" \
  > "${response_file}"

python3 - <<'PY' "${response_file}"
import pathlib
import sys

payload = pathlib.Path(sys.argv[1]).read_text().strip()
if not payload:
    raise SystemExit("orchestrator text response was empty")
print("✅ orchestrator text endpoint returned a non-empty response")
PY

orchestrator_logs=""
for _ in $(seq 1 10); do
  orchestrator_logs="$(
    while IFS= read -r pod_name; do
      kubectl -n "${NAMESPACE}" logs "${pod_name}" --since=3m 2>/dev/null || true
    done < <(kubectl -n "${NAMESPACE}" get pods -l app=orchestrator -o name 2>/dev/null)
  )"
  if printf '%s\n' "${orchestrator_logs}" | rg -q "${smoke_subject}" \
    && printf '%s\n' "${orchestrator_logs}" | rg -q 'https://nlp-service\.jarvis\.svc\.cluster\.local:8082' \
    && printf '%s\n' "${orchestrator_logs}" | rg -q 'NLP Result:'; then
    break
  fi
  sleep 1
done

if ! printf '%s\n' "${orchestrator_logs}" | rg -q "${smoke_subject}"; then
  echo "❌ orchestrator logs did not include the unique smoke subject ${smoke_subject}" >&2
  exit 1
fi
if ! printf '%s\n' "${orchestrator_logs}" | rg -q 'https://nlp-service\.jarvis\.svc\.cluster\.local:8082'; then
  echo "❌ orchestrator logs did not show HTTPS nlp-service routing on port 8082" >&2
  exit 1
fi
if ! printf '%s\n' "${orchestrator_logs}" | rg -q 'NLP Result:'; then
  echo "❌ orchestrator logs did not record an NLP result for the text path" >&2
  exit 1
fi

echo "✅ orchestrator routed the migrated text path to https://nlp-service.jarvis.svc.cluster.local:8082"
