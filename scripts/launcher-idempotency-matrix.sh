#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RUNS="${JARVIS_IDEMPOTENCY_RUNS:-3}"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
RUN_DIR="${JARVIS_HOME}/run"
OUTPUT_FILE="${RUN_DIR}/launcher-idempotency-matrix.json"
SECRETS_FILE="${JARVIS_HOME}/secrets/secrets.env"
CA_CERT="${JARVIS_HOME}/tls/jarvis-ca.crt"
GRAFANA_URL="${JARVIS_GRAFANA_URL:-https://grafana.jarvis.local}"
NAMESPACE="${JARVIS_OBS_NAMESPACE:-jarvis}"
GRAFANA_SECRET_NAME="${JARVIS_OBS_SECRET_NAME:-jarvis-secrets}"
GRAFANA_SECRET_USER_KEY="${JARVIS_OBS_GRAFANA_USER_KEY:-GRAFANA_ADMIN_USER}"
GRAFANA_SECRET_PASSWORD_KEY="${JARVIS_OBS_GRAFANA_PASSWORD_KEY:-GRAFANA_ADMIN_PASSWORD}"
TMP_DIR=""

log() {
    printf '[launcher-idempotency] %s\n' "$*" >&2
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

cleanup() {
    [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]] && rm -rf "${TMP_DIR}"
}

trap cleanup EXIT

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
    GRAFANA_USER="${JARVIS_GRAFANA_ADMIN_USER:-}"
    GRAFANA_PASSWORD="${JARVIS_GRAFANA_ADMIN_PASSWORD:-}"

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
    local ingress_ip="$1"
    local path="$2"
    shift 2

    local -a curl_args=(
        --silent
        --show-error
        --fail
        --connect-timeout 5
        --max-time 20
        --resolve "grafana.jarvis.local:443:${ingress_ip}"
    )

    if [[ -f "${CA_CERT}" ]]; then
        curl_args+=(--cacert "${CA_CERT}")
    fi

    curl "${curl_args[@]}" "$@" "${GRAFANA_URL}${path}"
}

grafana_state_json() {
    local ingress_ip="$1"
    local datasources dashboards overview logs

    if [[ -z "${GRAFANA_USER:-}" || -z "${GRAFANA_PASSWORD:-}" ]]; then
        printf '{}'
        return 0
    fi

    datasources="$(curl_grafana "${ingress_ip}" "/api/datasources" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" 2>/dev/null || true)"
    dashboards="$(curl_grafana "${ingress_ip}" "/api/search?query=Jarvis%202.0" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" 2>/dev/null || true)"
    overview="$(curl_grafana "${ingress_ip}" "/api/dashboards/uid/jarvis-overview" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" 2>/dev/null || true)"
    logs="$(curl_grafana "${ingress_ip}" "/api/dashboards/uid/jarvis-logs" -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" 2>/dev/null || true)"

    python3 - "${datasources:-}" "${dashboards:-}" "${overview:-}" "${logs:-}" <<'PY'
import json
import sys

datasources_raw, dashboards_raw, overview_raw, logs_raw = sys.argv[1:]

try:
    datasources = json.loads(datasources_raw) if datasources_raw else []
except json.JSONDecodeError:
    datasources = []

try:
    dashboards = json.loads(dashboards_raw) if dashboards_raw else []
except json.JSONDecodeError:
    dashboards = []

def parse_folder(payload_raw):
    if not payload_raw:
        return {}
    try:
        payload = json.loads(payload_raw)
    except json.JSONDecodeError:
        return {}
    meta = payload.get("meta", {})
    return {
        "folderUid": meta.get("folderUid", ""),
        "folderTitle": meta.get("folderTitle", ""),
    }

result = {
    "datasourceNames": sorted(item.get("uid") or item.get("name") for item in datasources if item.get("uid") or item.get("name")),
    "dashboardUids": sorted(item.get("uid") for item in dashboards if item.get("uid")),
    "dashboardCount": len([item for item in dashboards if item.get("uid")]),
    "jarvisOverview": parse_folder(overview_raw),
    "jarvisLogs": parse_folder(logs_raw),
}

print(json.dumps(result, sort_keys=True))
PY
}

cert_fingerprint() {
    if [[ ! -f "${JARVIS_HOME}/tls/jarvis.crt" ]]; then
        printf ''
        return 0
    fi
    openssl x509 -in "${JARVIS_HOME}/tls/jarvis.crt" -noout -fingerprint -sha256 2>/dev/null | sed 's/^sha256 Fingerprint=//'
}

capture_run() {
    local run_index="$1"
    local run_log="${TMP_DIR}/launcher-run-${run_index}.log"
    local exit_code=0

    log "Starting launcher run ${run_index}/${RUNS}"
    if (cd "${PROJECT_DIR}" && ./jarvis-launch.sh >"${run_log}" 2>&1); then
        exit_code=0
    else
        exit_code=$?
    fi

    local ingress_ip
    ingress_ip="$(discover_ingress_ip)"

    local grafana_state='{}'
    if [[ -n "${ingress_ip}" ]]; then
        grafana_state="$(grafana_state_json "${ingress_ip}")"
    fi

    python3 - "${run_index}" "${exit_code}" "${run_log}" "${RUN_DIR}/last-run.json" "${RUN_DIR}/observability-status.json" "$(cert_fingerprint)" "${grafana_state}" <<'PY'
import json
import pathlib
import sys

run_index, exit_code, run_log, last_run_path, obs_path, cert_fp, grafana_state_raw = sys.argv[1:]

def load_json(path_str):
    path = pathlib.Path(path_str)
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"_invalid": True, "_path": str(path)}

try:
    grafana_state = json.loads(grafana_state_raw)
except json.JSONDecodeError:
    grafana_state = {}

payload = {
    "run": int(run_index),
    "exitCode": int(exit_code),
    "launcherLogPath": run_log,
    "launcherLogTail": pathlib.Path(run_log).read_text(encoding="utf-8", errors="replace").splitlines()[-40:],
    "lastRun": load_json(last_run_path),
    "observability": load_json(obs_path),
    "certFingerprintSha256": cert_fp,
    "grafana": grafana_state,
}

print(json.dumps(payload, sort_keys=True))
PY
}

main() {
    [[ "${RUNS}" =~ ^[0-9]+$ ]] || fail "JARVIS_IDEMPOTENCY_RUNS must be an integer"
    if (( RUNS < 3 )); then
        fail "Idempotency matrix requires at least 3 runs"
    fi

    detect_kubeconfig || fail "KUBECONFIG is not configured for launcher idempotency validation"
    kubectl cluster-info >/dev/null 2>&1 || fail "Kubernetes cluster is not reachable"
    mkdir -p "${RUN_DIR}"
    TMP_DIR="$(mktemp -d -t jarvis-launcher-idempotency-XXXXXX)"
    load_grafana_credentials || true

    local run_payloads=()
    local run_index
    for run_index in $(seq 1 "${RUNS}"); do
        run_payloads+=("$(capture_run "${run_index}")")
    done

    python3 - "${OUTPUT_FILE}" "${RUNS}" "${run_payloads[@]}" <<'PY'
import json
import sys
from datetime import datetime, timezone

output_path = sys.argv[1]
run_count = int(sys.argv[2])
runs = [json.loads(item) for item in sys.argv[3:]]

matrix = {
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "runCount": run_count,
    "runs": runs,
}

with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(matrix, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY

    log "Wrote idempotency matrix to ${OUTPUT_FILE}"
    cat "${OUTPUT_FILE}"
}

main "$@"
