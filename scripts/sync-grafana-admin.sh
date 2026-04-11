#!/usr/bin/env bash

set -euo pipefail

NAMESPACE="${JARVIS_OBS_NAMESPACE:-jarvis}"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
SECRETS_FILE="${JARVIS_OBS_SECRETS_FILE:-${JARVIS_HOME}/secrets/secrets.env}"
GRAFANA_SECRET_NAME="${JARVIS_OBS_SECRET_NAME:-jarvis-secrets}"
GRAFANA_SECRET_USER_KEY="${JARVIS_OBS_GRAFANA_USER_KEY:-GRAFANA_ADMIN_USER}"
GRAFANA_SECRET_PASSWORD_KEY="${JARVIS_OBS_GRAFANA_PASSWORD_KEY:-GRAFANA_ADMIN_PASSWORD}"
GRAFANA_LABEL_SELECTOR="${JARVIS_GRAFANA_LABEL_SELECTOR:-app=grafana}"
WAIT_SECONDS="${JARVIS_GRAFANA_SYNC_WAIT_SECONDS:-120}"
GRAFANA_USER="${JARVIS_GRAFANA_ADMIN_USER:-}"
GRAFANA_PASSWORD="${JARVIS_GRAFANA_ADMIN_PASSWORD:-}"

log() {
    printf '[grafana-admin-sync] %s\n' "$*"
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

grafana_pod_name() {
    kubectl get pods -n "${NAMESPACE}" -l "${GRAFANA_LABEL_SELECTOR}" \
        -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.metadata.name}{"\n"}{end}' 2>/dev/null | head -n 1
}

wait_for_grafana_pod() {
    local attempts
    attempts=$(( WAIT_SECONDS / 5 ))
    (( attempts > 0 )) || attempts=1

    local pod_name attempt
    for attempt in $(seq 1 "${attempts}"); do
        pod_name="$(grafana_pod_name)"
        if [[ -n "${pod_name}" ]]; then
            printf '%s' "${pod_name}"
            return 0
        fi
        sleep 5
    done

    return 1
}

reset_admin_password() {
    local pod_name="$1"

    # Grafana persists the admin password inside its DB on the PVC.
    # Reconcile it against jarvis-secrets on every launcher path so an older
    # installation does not keep the legacy default password.
    if ! printf '%s' "${GRAFANA_PASSWORD}" | \
        kubectl exec -i -n "${NAMESPACE}" "pod/${pod_name}" -- \
            grafana cli --homepath /usr/share/grafana admin reset-admin-password --password-from-stdin >/dev/null; then
        fail "Failed to reset Grafana admin password in pod/${pod_name}"
    fi
}

main() {
    detect_kubeconfig || fail "KUBECONFIG is not configured for Grafana admin sync"
    kubectl cluster-info >/dev/null 2>&1 || fail "Kubernetes cluster is not reachable"
    load_grafana_credentials

    if [[ "${GRAFANA_USER}" != "admin" ]]; then
        fail "Grafana username reconciliation currently supports only ${GRAFANA_SECRET_USER_KEY}=admin for existing PVC-backed installs"
    fi

    local pod_name
    pod_name="$(wait_for_grafana_pod)" || fail "Timed out waiting for a running Grafana pod"
    reset_admin_password "${pod_name}"
    log "Grafana admin password synchronized from secret/${GRAFANA_SECRET_NAME}"
}

main "$@"
