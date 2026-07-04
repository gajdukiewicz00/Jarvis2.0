#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck disable=SC1091
source "${ROOT_DIR}/scripts/lib/k8s-common.sh"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
WAIT_TIMEOUT="${JARVIS_SMOKE_TIMEOUT:-300s}"
API_HOST="${JARVIS_API_HOST:-api.jarvis.local}"
API_PATH="${JARVIS_API_READINESS_PATH:-/actuator/health/readiness}"
INGRESS_IP="${JARVIS_INGRESS_IP:-}"

REQUIRED_DEPLOYMENTS=(
  api-gateway
  security-service
  orchestrator
  memory-service
  voice-gateway
  embedding-service
  prometheus
  grafana
  loki
  tempo
  alloy
)

REQUIRED_STATEFULSETS=(
  postgres
  postgres-pgvector
  kafka
  rabbitmq
)

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s-smoke.sh [options]

Options:
  --namespace=NAME  Kubernetes namespace, default: jarvis-prod
  --timeout=TIME    kubectl wait timeout, default: 300s
  --help, -h        Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)
      NAMESPACE="${arg#*=}"
      ;;
    --timeout=*)
      WAIT_TIMEOUT="${arg#*=}"
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

require_cmd curl
require_cmd base64
jarvis_require_kubectl >/dev/null

if ! jarvis_cluster_reachable; then
  echo "❌ Kubernetes cluster is not reachable for smoke validation" >&2
  exit 1
fi

current_ingress_ip() {
  if [[ -n "${INGRESS_IP}" ]]; then
    printf '%s\n' "${INGRESS_IP}"
    return 0
  fi

  local detected_ip=""

  detected_ip="$(kubectl get svc -n ingress-nginx ingress-nginx-controller \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  if [[ -n "${detected_ip}" ]]; then
    printf '%s\n' "${detected_ip}"
    return 0
  fi

  detected_ip="$(kubectl get svc -n ingress nginx-ingress-microk8s-controller \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  if [[ -n "${detected_ip}" ]]; then
    printf '%s\n' "${detected_ip}"
    return 0
  fi

  kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}'
}

resolved_host_ip() {
  getent hosts "${API_HOST}" 2>/dev/null | awk 'NR == 1 { print $1 }'
}

secret_value() {
  local key="$1"
  kubectl -n "${NAMESPACE}" get secret jarvis-secrets -o "jsonpath={.data.${key}}" \
    | base64 --decode
}

assert_workload_exists() {
  local kind="$1"
  local name="$2"
  kubectl -n "${NAMESPACE}" get "${kind}" "${name}" >/dev/null
}

assert_no_ingress_host_conflicts() {
  local conflicts

  conflicts="$(
    kubectl get ingress -A \
      -o jsonpath='{range .items[*]}{.metadata.namespace}{"\t"}{.metadata.name}{"\t"}{range .spec.rules[*]}{.host}{" "}{end}{"\n"}{end}' \
      | awk -v target_ns="${NAMESPACE}" '$1 != target_ns && ($0 ~ /api\.jarvis\.local/ || $0 ~ /voice\.jarvis\.local/ || $0 ~ /grafana\.jarvis\.local/) { print }'
  )"

  if [[ -z "${conflicts}" ]]; then
    return 0
  fi

  echo "❌ Legacy ingress hosts are still active outside '${NAMESPACE}':" >&2
  printf '%s\n' "${conflicts}" >&2
  exit 1
}

assert_expected_ingress_host_ownership() {
  local host
  local ingress_rows
  local matching_rows

  ingress_rows="$(
    kubectl get ingress -A \
      -o jsonpath='{range .items[*]}{.metadata.namespace}{"\t"}{.metadata.name}{"\t"}{range .spec.rules[*]}{.host}{" "}{end}{"\n"}{end}'
  )"

  for host in api.jarvis.local voice.jarvis.local grafana.jarvis.local; do
    if ! printf '%s\n' "${ingress_rows}" | awk -v target_ns="${NAMESPACE}" -v target_host="${host}" '
      $1 == target_ns {
        for (i = 3; i <= NF; i++) {
          if ($i == target_host) {
            found = 1
          }
        }
      }
      END { exit(found ? 0 : 1) }
    '; then
      echo "❌ Expected ingress host '${host}' is not owned by namespace '${NAMESPACE}'" >&2
      printf '%s\n' "${ingress_rows}" >&2
      exit 1
    fi
  done

  matching_rows="$(printf '%s\n' "${ingress_rows}" | awk -v target_ns="${NAMESPACE}" '$1 == target_ns')"
  echo "✅ Ingress hosts are owned only by ${NAMESPACE}:"
  printf '%s\n' "${matching_rows}"
}

echo "Smoke namespace: ${NAMESPACE}"
kubectl get namespace "${NAMESPACE}" >/dev/null
echo "✅ Namespace exists: ${NAMESPACE}"
assert_no_ingress_host_conflicts
assert_expected_ingress_host_ownership

for name in "${REQUIRED_STATEFULSETS[@]}"; do
  assert_workload_exists statefulset "${name}"
done
for name in "${REQUIRED_DEPLOYMENTS[@]}"; do
  assert_workload_exists deployment "${name}"
done
echo "✅ Required workloads exist in ${NAMESPACE}"

kubectl wait --for=condition=ready pod --all -n "${NAMESPACE}" --timeout="${WAIT_TIMEOUT}"
echo "✅ All pods report Ready in ${NAMESPACE}"

echo "Pods:"
kubectl get pods -n "${NAMESPACE}"

legacy_selector='app in (api-gateway,security-service,orchestrator,memory-service,voice-gateway,postgres,postgres-pgvector,kafka,rabbitmq,prometheus,grafana,loki,tempo,alloy)'
legacy_runtime_summary=""
for legacy_ns in jarvis jarvis-dev default; do
  if kubectl get namespace "${legacy_ns}" >/dev/null 2>&1; then
    legacy_count="$(
      kubectl -n "${legacy_ns}" get pods -l "${legacy_selector}" --no-headers 2>/dev/null \
        | awk 'END { print NR + 0 }'
    )"
    if [[ "${legacy_count}" != "0" ]]; then
      legacy_runtime_summary+="${legacy_ns}"$'\t'"${legacy_count}"$'\n'
    fi
  fi
done
if [[ -n "${legacy_runtime_summary}" ]]; then
  echo "INFO: rollback-safe legacy pods still exist, but no legacy ingress owns production hosts:"
  printf '%s' "${legacy_runtime_summary}"
else
  echo "✅ No core Jarvis runtime pods were found in jarvis/jarvis-dev/default"
fi

POSTGRES_USER="$(secret_value POSTGRES_USER)"
POSTGRES_PASSWORD="$(secret_value POSTGRES_PASSWORD)"

kubectl -n "${NAMESPACE}" exec statefulset/postgres-pgvector -- sh -ec \
  "PGPASSWORD='${POSTGRES_PASSWORD}' psql -U '${POSTGRES_USER}' -d jarvis_memory -tAc \"SELECT extname FROM pg_extension WHERE extname = 'vector';\"" \
  | grep -qx vector
echo "✅ pgvector extension exists in postgres-pgvector/jarvis_memory"

kubectl -n "${NAMESPACE}" exec statefulset/kafka -- sh -ec \
  "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null"
echo "✅ Kafka responds on localhost:9092 inside the broker pod"

kubectl -n "${NAMESPACE}" exec statefulset/rabbitmq -- rabbitmq-diagnostics -q ping >/dev/null
echo "✅ RabbitMQ diagnostics ping passed"

curl_args=(-k -fsS --connect-timeout "${JARVIS_CURL_CONNECT_TIMEOUT:-5}" --max-time "${JARVIS_CURL_MAX_TIME:-20}")
resolved_ip="$(resolved_host_ip || true)"
ingress_ip="$(current_ingress_ip || true)"
if [[ -z "${resolved_ip}" ]]; then
  if [[ -z "${ingress_ip}" ]]; then
    echo "❌ Could not resolve ${API_HOST} and could not determine an ingress IP." >&2
    exit 1
  fi
  curl_args+=(--resolve "${API_HOST}:443:${ingress_ip}")
elif [[ -n "${ingress_ip}" && "${resolved_ip}" != "${ingress_ip}" ]]; then
  curl_args+=(--resolve "${API_HOST}:443:${ingress_ip}")
fi

readiness_response="$(curl "${curl_args[@]}" "https://${API_HOST}${API_PATH}")"
printf '%s\n' "${readiness_response}" | grep -Eiq '"status"[[:space:]]*:[[:space:]]*"(UP|READY|HEALTHY|OK)"'
echo "✅ HTTPS ingress readiness succeeded for https://${API_HOST}${API_PATH}"

kubectl -n "${NAMESPACE}" get ingress
echo "✅ Smoke completed for namespace ${NAMESPACE}"
