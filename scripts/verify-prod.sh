#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 - Production Repo Verification
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "❌ Missing dependency: $1" >&2
        exit 1
    }
}

require_cmd rg
require_cmd grep

RUNTIME_TARGETS=(
    "${PROJECT_ROOT}/apps"
    "${PROJECT_ROOT}/k8s"
    "${PROJECT_ROOT}/scripts/product"
    "${PROJECT_ROOT}/jarvis"
    "${PROJECT_ROOT}/jarvis-launch.sh"
    "${PROJECT_ROOT}/jarvis-stop.sh"
    "${PROJECT_ROOT}/jarvis-logs.sh"
    "${PROJECT_ROOT}/Makefile"
)

RUNTIME_DIRS=(
    "${PROJECT_ROOT}/apps"
    "${PROJECT_ROOT}/k8s"
    "${PROJECT_ROOT}/scripts/product"
)

RG_EXCLUDES=(
    "--glob" "!**/target/**"
    "--glob" "!**/build/**"
    "--glob" "!**/.git/**"
    "--glob" "!**/logs/**"
    "--glob" "!**/docs/**"
    "--glob" "!**/docs/legacy/**"
    "--glob" "!**/scripts/legacy/**"
    "--glob" "!**/data/**"
    "--glob" "!**/application-dev.yaml"
)

fail_if_found() {
    local label="$1"
    shift
    if rg -n "${@}" "${RUNTIME_TARGETS[@]}" "${RG_EXCLUDES[@]}" >/dev/null; then
        echo "❌ ${label} found"
        rg -n "${@}" "${RUNTIME_TARGETS[@]}" "${RG_EXCLUDES[@]}" | head -20
        exit 1
    fi
}

# Banned patterns in active repo
fail_if_found "docker-compose references" "docker-compose"
fail_if_found "minikube references" "minikube"
fail_if_found "NodePort references" "NodePort"
fail_if_found "dev profile references" "application-dev|application-docker"

# Banned files/dirs
docker_compose_files="$(find "${RUNTIME_DIRS[@]}" -type f \( -name 'docker-compose*.yml' -o -name 'docker-compose*.yaml' \) 2>/dev/null || true)"
if [[ -n "${docker_compose_files}" ]]; then
    echo "❌ docker-compose file present"
    echo "${docker_compose_files}"
    exit 1
fi

for d in "${PROJECT_ROOT}/k8s/legacy" "${PROJECT_ROOT}/k8s/overlays/local" "${PROJECT_ROOT}/k8s/overlays/dev"; do
    if [[ -d "$d" ]]; then
        echo "❌ Legacy k8s dir present: $d"
        exit 1
    fi
done

if grep -RIn --include="*.yaml" --include="*.yml" "image:.*:latest" "${PROJECT_ROOT}/k8s" >/dev/null; then
    echo "❌ Found ':latest' image tags in k8s manifests"
    grep -RIn --include="*.yaml" --include="*.yml" "image:.*:latest" "${PROJECT_ROOT}/k8s" | head -20
    exit 1
fi

# Secrets/certs in repo
cert_files="$(find "${RUNTIME_DIRS[@]}" -type f \( -name '*.key' -o -name '*.crt' -o -name '*.pem' \) 2>/dev/null || true)"
if [[ -n "${cert_files}" ]]; then
    echo "❌ Certificate/key files detected in repo"
    echo "${cert_files}"
    exit 1
fi

if [[ -x "${PROJECT_ROOT}/scripts/ci/check-mqtt-hardening.sh" ]]; then
    "${PROJECT_ROOT}/scripts/ci/check-mqtt-hardening.sh"
fi

echo "✅ verify-prod: OK"
