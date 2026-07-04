#!/usr/bin/env bash
# =============================================================================
# Phase 1 acceptance: Postgres / Kafka / RabbitMQ survive a StatefulSet restart.
# =============================================================================
# Strategy: write durable data into each, rollout restart the StatefulSet,
# verify the data is still there once the new pod is Ready.
#
# Requires:
#   - kubectl with access to the target cluster
#   - Namespace and `jarvis-secrets` Secret already applied
#   - Pods already Ready (run after `jarvis-deploy-microk8s-prod.sh` + smoke)
#
# Env knobs:
#   JARVIS_NAMESPACE      default: jarvis-prod
#   JARVIS_ROLLOUT_TIMEOUT default: 300s
#   JARVIS_RABBIT_VHOST   default: jarvis
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck disable=SC1091
source "${ROOT_DIR}/scripts/lib/k8s-common.sh"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
ROLLOUT_TIMEOUT="${JARVIS_ROLLOUT_TIMEOUT:-300s}"
RABBIT_VHOST="${JARVIS_RABBIT_VHOST:-jarvis}"
MARKER="phase1-$(date -u +%Y%m%dT%H%M%SZ)"
TEST_QUEUE="jarvis.phase1.persistence"
TEST_TOPIC="jarvis.phase1.persistence"
TEST_TABLE="phase1_persistence"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/microk8s/persistence-test.sh [options]

Options:
  --namespace=NAME       Kubernetes namespace, default: jarvis-prod
  --rollout-timeout=T    kubectl wait timeout, default: 300s
  --help, -h             Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*)         NAMESPACE="${arg#*=}" ;;
    --rollout-timeout=*)   ROLLOUT_TIMEOUT="${arg#*=}" ;;
    --help|-h)             usage; exit 0 ;;
    *) echo "❌ Unknown argument: ${arg}" >&2; usage >&2; exit 1 ;;
  esac
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "❌ Missing dependency: $1" >&2; exit 1; }
}

require_cmd kubectl
require_cmd base64
jarvis_require_kubectl >/dev/null

if ! jarvis_cluster_reachable; then
  echo "❌ Kubernetes cluster is not reachable" >&2
  exit 1
fi

ns_kubectl() { kubectl -n "${NAMESPACE}" "$@"; }

ns_kubectl get namespace "${NAMESPACE}" >/dev/null
echo "Phase 1 persistence test starting in namespace: ${NAMESPACE}"
echo "Marker: ${MARKER}"
echo ""

secret_value() {
  ns_kubectl get secret jarvis-secrets -o "jsonpath={.data.$1}" | base64 --decode
}

POSTGRES_USER="$(secret_value POSTGRES_USER)"
POSTGRES_PASSWORD="$(secret_value POSTGRES_PASSWORD)"
RABBIT_USER="$(secret_value RABBITMQ_DEFAULT_USER)"
RABBIT_PASS="$(secret_value RABBITMQ_DEFAULT_PASS)"

# -----------------------------------------------------------------------------
# Postgres
# -----------------------------------------------------------------------------
test_postgres() {
  local sts="$1"
  local db="$2"
  echo "── Postgres persistence: ${sts} / ${db} ──"

  ns_kubectl exec -i "statefulset/${sts}" -- env PGPASSWORD="${POSTGRES_PASSWORD}" \
    psql -U "${POSTGRES_USER}" -d "${db}" -v ON_ERROR_STOP=1 <<EOSQL >/dev/null
CREATE TABLE IF NOT EXISTS ${TEST_TABLE} (
  id SERIAL PRIMARY KEY,
  marker TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO ${TEST_TABLE}(marker) VALUES ('${MARKER}');
EOSQL
  echo "  wrote marker into ${db}.${TEST_TABLE}"

  ns_kubectl rollout restart "statefulset/${sts}"
  ns_kubectl rollout status "statefulset/${sts}" --timeout="${ROLLOUT_TIMEOUT}"

  local count
  count="$(ns_kubectl exec "statefulset/${sts}" -- env PGPASSWORD="${POSTGRES_PASSWORD}" \
    psql -U "${POSTGRES_USER}" -d "${db}" -tAc \
    "SELECT COUNT(*) FROM ${TEST_TABLE} WHERE marker = '${MARKER}'" | tr -d '[:space:]')"

  if [[ "${count}" =~ ^[0-9]+$ ]] && (( count >= 1 )); then
    echo "✅ ${sts}/${db}: marker survived restart (rows=${count})"
  else
    echo "❌ ${sts}/${db}: marker missing after restart (count='${count}')"
    return 1
  fi
}

# -----------------------------------------------------------------------------
# Kafka
# -----------------------------------------------------------------------------
test_kafka() {
  echo "── Kafka persistence ──"

  ns_kubectl exec statefulset/kafka -- /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --delete --topic "${TEST_TOPIC}" --if-exists >/dev/null 2>&1 || true
  ns_kubectl exec statefulset/kafka -- /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --topic "${TEST_TOPIC}" --partitions 1 --replication-factor 1 \
    --if-not-exists >/dev/null
  echo "  topic ${TEST_TOPIC} ensured (recreated for fresh marker)"

  printf '%s\n' "${MARKER}" \
    | ns_kubectl exec -i statefulset/kafka -- /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server localhost:9092 --topic "${TEST_TOPIC}" >/dev/null
  echo "  produced marker into ${TEST_TOPIC}"

  ns_kubectl rollout restart statefulset/kafka
  ns_kubectl rollout status statefulset/kafka --timeout="${ROLLOUT_TIMEOUT}"

  if ! ns_kubectl exec statefulset/kafka -- /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 --list | grep -qx "${TEST_TOPIC}"; then
    echo "❌ Kafka: topic ${TEST_TOPIC} missing after restart"
    return 1
  fi
  echo "  topic ${TEST_TOPIC} present"

  local consumed
  consumed="$(ns_kubectl exec statefulset/kafka -- /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server localhost:9092 --topic "${TEST_TOPIC}" \
        --from-beginning --max-messages 1 --timeout-ms 30000 2>/dev/null \
      | tr -d '\r' | head -n1)"

  if [[ "${consumed}" == "${MARKER}" ]]; then
    echo "✅ Kafka: message survived restart"
  else
    echo "❌ Kafka: expected '${MARKER}', got '${consumed}'"
    return 1
  fi
}

# -----------------------------------------------------------------------------
# RabbitMQ
# -----------------------------------------------------------------------------
declare_durable_queue() {
  # Try rabbitmqadmin (bundled with management plugin), fall back to HTTP API.
  if ns_kubectl exec statefulset/rabbitmq -- bash -lc \
       'command -v rabbitmqadmin >/dev/null 2>&1' 2>/dev/null; then
    ns_kubectl exec statefulset/rabbitmq -- rabbitmqadmin \
      -u "${RABBIT_USER}" -p "${RABBIT_PASS}" -V "${RABBIT_VHOST}" \
      declare queue "name=${TEST_QUEUE}" durable=true >/dev/null
    return 0
  fi

  if ns_kubectl exec statefulset/rabbitmq -- bash -lc \
       'command -v curl >/dev/null 2>&1' 2>/dev/null; then
    ns_kubectl exec statefulset/rabbitmq -- curl -fsS -u "${RABBIT_USER}:${RABBIT_PASS}" \
      -H 'content-type: application/json' \
      -X PUT "http://localhost:15672/api/queues/${RABBIT_VHOST}/${TEST_QUEUE}" \
      -d '{"durable":true,"auto_delete":false}' >/dev/null
    return 0
  fi

  echo "❌ neither rabbitmqadmin nor curl is available inside rabbitmq pod" >&2
  return 1
}

test_rabbitmq() {
  echo "── RabbitMQ persistence ──"

  declare_durable_queue
  echo "  declared durable queue ${TEST_QUEUE} on vhost ${RABBIT_VHOST}"

  ns_kubectl rollout restart statefulset/rabbitmq
  ns_kubectl rollout status statefulset/rabbitmq --timeout="${ROLLOUT_TIMEOUT}"

  if ns_kubectl exec statefulset/rabbitmq -- rabbitmqctl --vhost="${RABBIT_VHOST}" \
        list_queues name 2>/dev/null | awk '{print $1}' | grep -qx "${TEST_QUEUE}"; then
    echo "✅ RabbitMQ: durable queue ${TEST_QUEUE} survived restart on vhost ${RABBIT_VHOST}"
  else
    echo "❌ RabbitMQ: durable queue ${TEST_QUEUE} missing after restart"
    return 1
  fi
}

# -----------------------------------------------------------------------------
# main
# -----------------------------------------------------------------------------
test_postgres postgres jarvis
echo ""
test_postgres postgres-pgvector jarvis_memory
echo ""
test_kafka
echo ""
test_rabbitmq

echo ""
echo "✅ Phase 1 persistence test passed (marker=${MARKER})"
