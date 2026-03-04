#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

export K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE="${K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE:-enforce}"

"${PROJECT_ROOT}/scripts/ci/k8s-preflight.sh" "${PROJECT_ROOT}/k8s/overlays/staging"
