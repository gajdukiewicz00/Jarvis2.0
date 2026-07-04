#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/scripts/lib/k8s-common.sh"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-bootstrap-postgres.sh [--namespace=NAME]
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

jarvis_require_kubectl >/dev/null

secret_value() {
  local key="$1"
  kubectl -n "${NAMESPACE}" get secret jarvis-secrets -o "jsonpath={.data.${key}}" | base64 -d
}

POSTGRES_USER="$(secret_value POSTGRES_USER)"
POSTGRES_PASSWORD="$(secret_value POSTGRES_PASSWORD)"

echo "🔎 Waiting for PostgreSQL statefulsets in ${NAMESPACE}"
kubectl wait --for=condition=ready pod -l app=postgres -n "${NAMESPACE}" --timeout=240s >/dev/null
kubectl wait --for=condition=ready pod -l app=postgres-pgvector -n "${NAMESPACE}" --timeout=240s >/dev/null

echo "🧱 Ensuring core PostgreSQL databases exist"
kubectl exec -n "${NAMESPACE}" statefulset/postgres -- env \
  POSTGRES_USER="${POSTGRES_USER}" \
  POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
  sh -ec '
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d jarvis <<'"'"'EOSQL'"'"'
      SELECT '"'"'CREATE DATABASE jarvis_security'"'"'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '"'"'jarvis_security'"'"')\gexec
      SELECT '"'"'CREATE DATABASE jarvis_user_profile'"'"'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '"'"'jarvis_user_profile'"'"')\gexec
      SELECT '"'"'CREATE DATABASE jarvis_memory'"'"'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '"'"'jarvis_memory'"'"')\gexec
      SELECT '"'"'CREATE DATABASE jarvis_db'"'"'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '"'"'jarvis_db'"'"')\gexec
EOSQL
  '

echo "🧠 Ensuring pgvector database and extension exist"
kubectl exec -n "${NAMESPACE}" statefulset/postgres-pgvector -- env \
  POSTGRES_USER="${POSTGRES_USER}" \
  POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
  sh -ec '
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d jarvis <<'"'"'EOSQL'"'"'
      SELECT '"'"'CREATE DATABASE jarvis_memory'"'"'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '"'"'jarvis_memory'"'"')\gexec
EOSQL
    psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d jarvis_memory <<'"'"'EOSQL'"'"'
      CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
  '

echo "📋 Current core databases"
kubectl exec -n "${NAMESPACE}" statefulset/postgres -- env \
  POSTGRES_USER="${POSTGRES_USER}" \
  POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
  sh -ec '
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    psql -U "${POSTGRES_USER}" -d jarvis <<'"'"'EOSQL'"'"'
      SELECT datname FROM pg_database WHERE datname LIKE '"'"'jarvis%'"'"' ORDER BY 1;
EOSQL
  '

echo "✅ PostgreSQL bootstrap complete in namespace ${NAMESPACE}"
