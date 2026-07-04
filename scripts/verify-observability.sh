#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
RUN_DIR="${JARVIS_HOME}/run"
SUMMARY_FILE="${RUN_DIR}/observability-status.json"
SECRETS_FILE="${JARVIS_OBS_SECRETS_FILE:-${JARVIS_HOME}/secrets/secrets.env}"
CA_CERT="${JARVIS_TLS_CA_CERT:-${JARVIS_HOME}/tls/jarvis-ca.crt}"
GRAFANA_URL="${JARVIS_GRAFANA_URL:-https://grafana.jarvis.local}"
GRAFANA_USER="${JARVIS_GRAFANA_ADMIN_USER:-}"
GRAFANA_PASSWORD="${JARVIS_GRAFANA_ADMIN_PASSWORD:-}"
LOG_WAIT_SECONDS="${JARVIS_OBS_LOG_WAIT_SECONDS:-120}"
LOG_QUERY_LIMIT="${JARVIS_OBS_LOG_QUERY_LIMIT:-5}"
LOG_WINDOW_SECONDS="${JARVIS_OBS_LOG_WINDOW_SECONDS:-180}"
PROBE_APP="${JARVIS_OBS_PROBE_APP:-api-gateway}"
PROBE_PATH="${JARVIS_OBS_PROBE_PATH:-/actuator/health/readiness}"
GRAFANA_SECRET_NAME="${JARVIS_OBS_SECRET_NAME:-jarvis-secrets}"
GRAFANA_SECRET_USER_KEY="${JARVIS_OBS_GRAFANA_USER_KEY:-GRAFANA_ADMIN_USER}"
GRAFANA_SECRET_PASSWORD_KEY="${JARVIS_OBS_GRAFANA_PASSWORD_KEY:-GRAFANA_ADMIN_PASSWORD}"
GRAFANA_ADMIN_SYNC_SCRIPT="${PROJECT_DIR}/scripts/sync-grafana-admin.sh"

INGRESS_IP=""
PROMETHEUS_ACTIVE_TARGETS=0
MATCHED_APP=""
MATCHED_MESSAGE=""
MATCHED_TIMESTAMP_NS=""
LOKI_QUERY=""
PROBE_ID=""
PROBE_START_NS=""
COLLECTOR_DIAGNOSTIC=""

log() {
    printf '[observability] %s\n' "$*"
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

    [[ -n "${GRAFANA_USER}" ]] || fail_with_summary "Grafana admin username is not configured in ${SECRETS_FILE} or secret/${GRAFANA_SECRET_NAME}"
    [[ -n "${GRAFANA_PASSWORD}" ]] || fail_with_summary "Grafana admin password is not configured in ${SECRETS_FILE} or secret/${GRAFANA_SECRET_NAME}"
}

now_ns() {
    python3 - <<'PY'
import time
print(time.time_ns())
PY
}

write_summary() {
    local status="$1"
    local error_message="${2:-}"
    mkdir -p "${RUN_DIR}"
    python3 - "${SUMMARY_FILE}" "${status}" "${error_message}" "${GRAFANA_URL}" "${INGRESS_IP}" "${MATCHED_APP}" "${LOKI_QUERY}" "${MATCHED_MESSAGE}" "${PROMETHEUS_ACTIVE_TARGETS}" "${PROBE_APP}" "${PROBE_PATH}" "${PROBE_ID}" "${PROBE_START_NS}" "${MATCHED_TIMESTAMP_NS}" "${LOG_WINDOW_SECONDS}" "${COLLECTOR_DIAGNOSTIC}" <<'PY'
import json
import sys
from datetime import datetime, timezone

(
    path,
    status,
    error_message,
    grafana_url,
    ingress_ip,
    matched_app,
    loki_query,
    matched_message,
    prometheus_active_targets,
    probe_app,
    probe_path,
    probe_id,
    probe_start_ns,
    matched_timestamp_ns,
    log_window_seconds,
    collector_diagnostic,
) = sys.argv[1:]

payload = {
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "status": status,
    "error": error_message or "",
    "grafanaUrl": grafana_url,
    "ingressIp": ingress_ip or "",
    "datasources": ["prometheus", "loki", "tempo"],
    "dashboards": ["jarvis-overview", "jarvis-logs"],
    "prometheusActiveTargets": int(prometheus_active_targets or "0"),
    "lokiQuery": loki_query or "",
    "matchedApp": matched_app or "",
    "matchedMessage": matched_message or "",
    "probeApp": probe_app or "",
    "probePath": probe_path or "",
    "probeId": probe_id or "",
    "probeStartNs": probe_start_ns or "",
    "matchedTimestampNs": matched_timestamp_ns or "",
    "logWindowSeconds": int(log_window_seconds or "0"),
    "collectorDiagnostic": collector_diagnostic or "",
}

with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
}

fail_with_summary() {
    local message="$1"
    write_summary "error" "${message}"
    printf 'ERROR: %s\n' "${message}" >&2
    exit 1
}

kubectl_raw() {
    kubectl get --raw "$1"
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
            log "Grafana health OK"
            return 0
        fi
        sleep 5
    done
    fail_with_summary "Grafana health check failed at ${GRAFANA_URL}/api/health"
}

sync_grafana_admin_credentials() {
    [[ -x "${GRAFANA_ADMIN_SYNC_SCRIPT}" ]] || fail_with_summary "Grafana admin sync script is missing: ${GRAFANA_ADMIN_SYNC_SCRIPT}"
    "${GRAFANA_ADMIN_SYNC_SCRIPT}" >/dev/null || fail_with_summary "Grafana admin credential sync failed"
}

verify_grafana_datasources() {
    local response
    local validation_error
    response="$(curl_grafana "/api/datasources" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" 2>/dev/null || true)"
    [[ -n "${response}" ]] || fail_with_summary "Grafana datasource API is not reachable"

    validation_error="$(python3 - "${response}" 2>&1 <<'PY'
import json
import sys

datasources = json.loads(sys.argv[1])
present = {(item.get("name"), item.get("uid")) for item in datasources}
required = {("Prometheus", "prometheus"), ("Loki", "loki"), ("Tempo", "tempo")}
missing = sorted(required - present)
if missing:
    raise SystemExit(f"Missing provisioned Grafana datasources: {missing}")
PY
)" || fail_with_summary "${validation_error}"
    log "Grafana datasources provisioned"
}

verify_grafana_dashboards() {
    local response
    local validation_error
    response="$(curl_grafana "/api/search?query=Jarvis%202.0" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" 2>/dev/null || true)"
    [[ -n "${response}" ]] || fail_with_summary "Grafana dashboard API is not reachable"

    validation_error="$(python3 - "${response}" 2>&1 <<'PY'
import json
import sys

items = json.loads(sys.argv[1])
uids = {item.get("uid") for item in items}
required = {"jarvis-overview", "jarvis-logs"}
missing = sorted(required - uids)
if missing:
    raise SystemExit(f"Missing provisioned Grafana dashboards: {missing}")
PY
)" || fail_with_summary "${validation_error}"

    for dashboard_uid in jarvis-overview jarvis-logs; do
        response="$(curl_grafana "/api/dashboards/uid/${dashboard_uid}" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" 2>/dev/null || true)"
        [[ -n "${response}" ]] || fail_with_summary "Grafana dashboard metadata is not reachable for ${dashboard_uid}"

        validation_error="$(python3 - "${response}" "${dashboard_uid}" 2>&1 <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
dashboard_uid = sys.argv[2]
meta = payload.get("meta", {})
folder_uid = meta.get("folderUid") or ""
folder_title = meta.get("folderTitle") or ""
if folder_uid != "jarvis-observability" or folder_title != "Jarvis":
    raise SystemExit(
        f"Dashboard {dashboard_uid} is not in the provisioned Jarvis folder "
        f"(folderUid={folder_uid!r}, folderTitle={folder_title!r})"
    )
PY
)" || fail_with_summary "${validation_error}"
    done

    log "Grafana dashboards provisioned"
}

verify_prometheus_targets() {
    local response
    response="$(kubectl_raw "/api/v1/namespaces/${NAMESPACE}/services/http:prometheus:9090/proxy/api/v1/targets?state=active" 2>/dev/null || true)"
    [[ -n "${response}" ]] || fail_with_summary "Prometheus targets API is not reachable"

    PROMETHEUS_ACTIVE_TARGETS="$(python3 - "${response}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
targets = payload.get("data", {}).get("activeTargets", [])
jarvis_targets = [target for target in targets if target.get("labels", {}).get("job") == "jarvis-actuator"]
print(len(jarvis_targets))
PY
)"

    [[ "${PROMETHEUS_ACTIVE_TARGETS}" =~ ^[0-9]+$ ]] || fail_with_summary "Prometheus returned an invalid active target count"
    if [[ "${PROMETHEUS_ACTIVE_TARGETS}" -lt 1 ]]; then
        fail_with_summary "Prometheus has no active jarvis-actuator targets"
    fi
    log "Prometheus active jarvis-actuator targets: ${PROMETHEUS_ACTIVE_TARGETS}"
}

verify_loki_ready() {
    local response
    response="$(kubectl_raw "/api/v1/namespaces/${NAMESPACE}/services/http:loki:3100/proxy/ready" 2>/dev/null || true)"
    [[ "${response}" == "ready" ]] || fail_with_summary "Loki readiness check failed"
    log "Loki ready"
}

verify_alloy_ready() {
    local response
    response="$(kubectl_raw "/api/v1/namespaces/${NAMESPACE}/services/http:alloy:12345/proxy/-/ready" 2>/dev/null || true)"
    [[ "${response}" == "Alloy is ready." ]] || fail_with_summary "Alloy readiness check failed"
    log "Alloy ready"
}

emit_fresh_log_probe() {
    PROBE_ID="jarvis-obs-$(date -u +%Y%m%dT%H%M%SZ)-$$"
    PROBE_START_NS="$(now_ns)"

    if ! kubectl_raw "/api/v1/namespaces/${NAMESPACE}/services/http:${PROBE_APP}:8080/proxy${PROBE_PATH}?jarvis_obs_probe=${PROBE_ID}" >/dev/null 2>&1; then
        fail_with_summary "Failed to emit fresh log probe via service/${PROBE_APP}${PROBE_PATH}"
    fi

    log "Triggered fresh log probe for app=${PROBE_APP} path=${PROBE_PATH}"
}

urlencode() {
    python3 - "$1" <<'PY'
import sys
import urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=""))
PY
}

extract_loki_match() {
    local response="$1"
    python3 - "${response}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
streams = payload.get("data", {}).get("result", [])
if not streams:
    raise SystemExit(1)

stream = streams[0].get("stream", {})
values = streams[0].get("values", [])
if not values:
    raise SystemExit(1)

message = values[0][1].strip().replace("\n", " ")
message = message[:240]
print(stream.get("app", ""))
print(values[0][0])
print(message)
PY
}

wait_for_loki_logs() {
    local max_attempts
    max_attempts=$(( LOG_WAIT_SECONDS / 5 ))
    (( max_attempts > 0 )) || max_attempts=1

    local attempt query encoded response parsed query_time_ns
    query="{app=\"${PROBE_APP}\"} |= \"${PROBE_PATH}\""
    encoded="$(urlencode "${query}")"
    LOKI_QUERY="${query}"

    for attempt in $(seq 1 "${max_attempts}"); do
        query_time_ns="$(now_ns)"
        response="$(kubectl_raw "/api/v1/namespaces/${NAMESPACE}/services/http:loki:3100/proxy/loki/api/v1/query?query=${encoded}&limit=${LOG_QUERY_LIMIT}&direction=backward&time=${query_time_ns}" 2>/dev/null || true)"
        [[ -n "${response}" ]] || {
            sleep 5
            continue
        }

        if ! parsed="$(extract_loki_match "${response}" 2>/dev/null)"; then
            sleep 5
            continue
        fi

        MATCHED_APP="$(sed -n '1p' <<<"${parsed}")"
        MATCHED_TIMESTAMP_NS="$(sed -n '2p' <<<"${parsed}")"
        MATCHED_MESSAGE="$(sed -n '3p' <<<"${parsed}")"

        if python3 - "${MATCHED_TIMESTAMP_NS}" "${PROBE_START_NS}" <<'PY' >/dev/null 2>&1
import sys
matched = int(sys.argv[1])
probe_start = int(sys.argv[2])
raise SystemExit(0 if matched >= probe_start else 1)
PY
        then
            log "Loki log ingestion verified for app=${MATCHED_APP} after probe start ${PROBE_START_NS}"
            return 0
        fi

        sleep 5
    done

    local diagnostic_message=""
    COLLECTOR_DIAGNOSTIC="$(kubectl logs -n "${NAMESPACE}" deploy/alloy --since=5m 2>/dev/null | python3 -c '
import sys

patterns = (
    "failed to list *v1.Pod",
    "failed to watch",
    "connect: connection refused",
)

for raw in sys.stdin:
    line = raw.strip()
    if any(pattern in line for pattern in patterns):
        print(line[:240])
        break
')"
    if [[ -n "${COLLECTOR_DIAGNOSTIC}" ]]; then
        diagnostic_message=" Collector signal: ${COLLECTOR_DIAGNOSTIC}"
    fi

    fail_with_summary "Loki did not return fresh ${PROBE_APP} logs after probe start ${PROBE_START_NS} within ${LOG_WAIT_SECONDS}s.${diagnostic_message}"
}

main() {
    detect_kubeconfig || fail_with_summary "KUBECONFIG is not configured for observability verification"
    kubectl cluster-info >/dev/null 2>&1 || fail_with_summary "Kubernetes cluster is not reachable"
    load_grafana_credentials

    INGRESS_IP="$(discover_ingress_ip)"
    [[ -n "${INGRESS_IP}" ]] || fail_with_summary "Could not resolve the Jarvis ingress IP"

    wait_for_grafana_health
    sync_grafana_admin_credentials
    verify_grafana_datasources
    verify_grafana_dashboards
    verify_prometheus_targets
    verify_loki_ready
    verify_alloy_ready
    emit_fresh_log_probe
    wait_for_loki_logs

    write_summary "ready" ""

    cat <<EOF
Grafana: ${GRAFANA_URL}
Prometheus active targets: ${PROMETHEUS_ACTIVE_TARGETS}
Loki query: ${LOKI_QUERY}
Matched app: ${MATCHED_APP}
Sample log: ${MATCHED_MESSAGE}
EOF
}

main "$@"
