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

EXCLUDES=(
    "--glob" "!docs/legacy/**"
    "--glob" "!scripts/legacy/**"
    "--glob" "!data/**"
    "--glob" "!target/**"
    "--glob" "!.git/**"
    "--glob" "!apps/api-gateway/src/main/resources/application-dev.yaml"
)

fail_if_found() {
    local label="$1"
    shift
    if rg -n "${@}" "${PROJECT_ROOT}" "${EXCLUDES[@]}" >/dev/null; then
        echo "❌ ${label} found"
        rg -n "${@}" "${PROJECT_ROOT}" "${EXCLUDES[@]}" | head -20
        exit 1
    fi
}

# Banned patterns in active repo
fail_if_found "docker-compose references" "docker-compose"
fail_if_found "minikube references" "minikube"
fail_if_found "NodePort references" "NodePort"
fail_if_found "dev profile references" "application-dev|application-docker"

# Banned files/dirs
if rg --files -g 'docker-compose*.yml' -g 'docker-compose*.yaml' "${PROJECT_ROOT}" >/dev/null; then
    echo "❌ docker-compose file present"
    rg --files -g 'docker-compose*.yml' -g 'docker-compose*.yaml' "${PROJECT_ROOT}"
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
if rg --files -g '*.key' -g '*.crt' -g '*.pem' "${PROJECT_ROOT}" "${EXCLUDES[@]}" >/dev/null; then
    echo "❌ Certificate/key files detected in repo"
    rg --files -g '*.key' -g '*.crt' -g '*.pem' "${PROJECT_ROOT}" "${EXCLUDES[@]}"
    exit 1
fi

echo "✅ verify-prod: OK"
