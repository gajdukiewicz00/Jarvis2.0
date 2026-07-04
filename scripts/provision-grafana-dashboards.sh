#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
SECRETS_FILE="${JARVIS_OBS_SECRETS_FILE:-${JARVIS_HOME}/secrets/secrets.env}"
CA_CERT="${JARVIS_TLS_CA_CERT:-${JARVIS_HOME}/tls/jarvis-ca.crt}"
GRAFANA_URL="${JARVIS_GRAFANA_URL:-https://grafana.jarvis.local}"
GRAFANA_USER="${JARVIS_GRAFANA_ADMIN_USER:-}"
GRAFANA_PASSWORD="${JARVIS_GRAFANA_ADMIN_PASSWORD:-}"
DASHBOARD_DIR="${JARVIS_GRAFANA_DASHBOARD_DIR:-${PROJECT_DIR}/config/grafana-dashboards}"
FOLDER_UID="jarvis-observability"
FOLDER_TITLE="Jarvis"
GRAFANA_SECRET_NAME="${JARVIS_OBS_SECRET_NAME:-jarvis-secrets}"
GRAFANA_SECRET_USER_KEY="${JARVIS_OBS_GRAFANA_USER_KEY:-GRAFANA_ADMIN_USER}"
GRAFANA_SECRET_PASSWORD_KEY="${JARVIS_OBS_GRAFANA_PASSWORD_KEY:-GRAFANA_ADMIN_PASSWORD}"
GRAFANA_ADMIN_SYNC_SCRIPT="${PROJECT_DIR}/scripts/sync-grafana-admin.sh"
INGRESS_IP=""

log() {
    printf '[grafana-provision] %s\n' "$*"
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

detect_kubeconfig() {
    if [[ -n "${KUBECONFIG:-}" ]]; then
        return 0
    fi
    if [[ -r "${JARVIS_HOME}/kubeconfig" ]]; then
        export KUBECONFIG="${JARVIS_HOME}/kubeconfig"
        return 0
    fi
    if [[ -r /etc/rancher/k3s/k3s.yaml ]]; then
        export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
        return 0
    fi
    return 1
}

read_env_file_value() {
    local file_path="$1"
    local key="$2"
    [[ -f "${file_path}" ]] || return 0

    python3 - "${file_path}" "${key}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
target = sys.argv[2]

for raw in path.read_text(encoding="utf-8").splitlines():
    stripped = raw.strip()
    if not stripped or stripped.startswith("#") or "=" not in raw:
        continue
    key, value = raw.split("=", 1)
    if key.strip() != target:
        continue
    value = value.strip().strip('"').strip("'")
    print(value)
    break
PY
}

secret_key_value() {
    local key="$1"
    local encoded
    encoded="$(kubectl get secret "${GRAFANA_SECRET_NAME}" -n "${NAMESPACE}" -o "jsonpath={.data.${key}}" 2>/dev/null || true)"
    [[ -n "${encoded}" ]] || return 0
    printf '%s' "${encoded}" | base64 -d 2>/dev/null || true
}

load_grafana_credentials() {
    if [[ -z "${GRAFANA_USER}" && -f "${SECRETS_FILE}" ]]; then
        GRAFANA_USER="$(read_env_file_value "${SECRETS_FILE}" "${GRAFANA_SECRET_USER_KEY}")"
    fi
    if [[ -z "${GRAFANA_PASSWORD}" && -f "${SECRETS_FILE}" ]]; then
        GRAFANA_PASSWORD="$(read_env_file_value "${SECRETS_FILE}" "${GRAFANA_SECRET_PASSWORD_KEY}")"
    fi

    if [[ -z "${GRAFANA_USER}" ]]; then
        GRAFANA_USER="$(secret_key_value "${GRAFANA_SECRET_USER_KEY}")"
    fi
    if [[ -z "${GRAFANA_PASSWORD}" ]]; then
        GRAFANA_PASSWORD="$(secret_key_value "${GRAFANA_SECRET_PASSWORD_KEY}")"
    fi

    [[ -n "${GRAFANA_USER}" ]] || fail "Grafana admin username is not configured in ${SECRETS_FILE} or secret/${GRAFANA_SECRET_NAME}"
    [[ -n "${GRAFANA_PASSWORD}" ]] || fail "Grafana admin password is not configured in ${SECRETS_FILE} or secret/${GRAFANA_SECRET_NAME}"
}

discover_ingress_ip() {
    local ip=""
    ip="$(kubectl get ingress -n "${NAMESPACE}" jarvis-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
    if [[ -z "${ip}" ]]; then
        ip="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || true)"
    fi
    if [[ -z "${ip}" ]]; then
        ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
    fi
    printf '%s' "${ip}"
}

curl_grafana() {
    local path="$1"
    shift

    local -a curl_args=(
        --silent
        --show-error
        --fail
        --connect-timeout 5
        --max-time 20
        --resolve "grafana.jarvis.local:443:${INGRESS_IP}"
    )

    if [[ -f "${CA_CERT}" ]]; then
        curl_args+=(--cacert "${CA_CERT}")
    fi

    curl "${curl_args[@]}" "$@" "${GRAFANA_URL}${path}"
}

wait_for_grafana_health() {
    local response=""
    local attempt
    for attempt in $(seq 1 24); do
        response="$(curl_grafana "/api/health" 2>/dev/null || true)"
        if [[ -n "${response}" ]] && python3 - "${response}" <<'PY' >/dev/null 2>&1
import json
import sys

payload = json.loads(sys.argv[1])
raise SystemExit(0 if payload.get("database") == "ok" else 1)
PY
        then
            return 0
        fi
        sleep 5
    done

    fail "Grafana health check failed at ${GRAFANA_URL}/api/health"
}

sync_grafana_admin_credentials() {
    [[ -x "${GRAFANA_ADMIN_SYNC_SCRIPT}" ]] || fail "Grafana admin sync script is missing: ${GRAFANA_ADMIN_SYNC_SCRIPT}"
    "${GRAFANA_ADMIN_SYNC_SCRIPT}" >/dev/null
}

ensure_folder() {
    local response
    response="$(curl_grafana "/api/folders" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}")"

    if python3 - "${response}" "${FOLDER_UID}" <<'PY' >/dev/null 2>&1
import json
import sys

folders = json.loads(sys.argv[1])
folder_uid = sys.argv[2]
raise SystemExit(0 if any(item.get("uid") == folder_uid for item in folders) else 1)
PY
    then
        return 0
    fi

    response="$(curl_grafana "/api/folders" \
        -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" \
        -H 'Content-Type: application/json' \
        -X POST \
        --data "{\"uid\":\"${FOLDER_UID}\",\"title\":\"${FOLDER_TITLE}\"}")"

    python3 - "${response}" "${FOLDER_UID}" "${FOLDER_TITLE}" <<'PY' >/dev/null
import json
import sys

payload = json.loads(sys.argv[1])
folder_uid = sys.argv[2]
folder_title = sys.argv[3]
if payload.get("uid") != folder_uid or payload.get("title") != folder_title:
    raise SystemExit(f"Unexpected folder response: {payload}")
PY
}

import_dashboard() {
    local dashboard_path="$1"
    local payload response

    payload="$(python3 - "${dashboard_path}" "${FOLDER_UID}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    dashboard = json.load(handle)

print(json.dumps({
    "dashboard": dashboard,
    "folderUid": sys.argv[2],
    "overwrite": True,
}))
PY
)"

    response="$(curl_grafana "/api/dashboards/db" \
        -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" \
        -H 'Content-Type: application/json' \
        -X POST \
        --data "${payload}")"

    python3 - "${response}" "${dashboard_path}" <<'PY' >/dev/null
import json
import os
import sys

payload = json.loads(sys.argv[1])
dashboard_path = sys.argv[2]
status = payload.get("status")
if status not in {"success", "updated"}:
    raise SystemExit(f"Grafana dashboard import failed for {os.path.basename(dashboard_path)}: {payload}")
PY
}

main() {
    detect_kubeconfig || fail "KUBECONFIG is not configured for Grafana dashboard provisioning"
    kubectl cluster-info >/dev/null 2>&1 || fail "Kubernetes cluster is not reachable"
    [[ -d "${DASHBOARD_DIR}" ]] || fail "Dashboard directory not found: ${DASHBOARD_DIR}"
    load_grafana_credentials

    INGRESS_IP="$(discover_ingress_ip)"
    [[ -n "${INGRESS_IP}" ]] || fail "Could not resolve the Jarvis ingress IP"

    wait_for_grafana_health
    sync_grafana_admin_credentials
    ensure_folder

    import_dashboard "${DASHBOARD_DIR}/jarvis-overview.json"
    import_dashboard "${DASHBOARD_DIR}/jarvis-logs.json"

    log "Grafana dashboards imported into folder ${FOLDER_TITLE}"
}

main "$@"
