#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

ALLOWLIST=(
    "${ROOT}/scripts/product/jarvis-install.sh"
    "${ROOT}/scripts/product/jarvis-build-release.sh"
)

PATTERN='\\.desktop($|[^A-Za-z0-9_.])'

if command -v rg >/dev/null 2>&1; then
    MATCHES="$(rg -n "${PATTERN}" "${ROOT}/scripts" \
        --glob '!scripts/legacy/**' --glob '!scripts/ci/**' --glob '!**/target/**' || true)"
else
    MATCHES="$(grep -RIn -E "${PATTERN}" "${ROOT}/scripts" \
        --exclude-dir scripts/legacy --exclude-dir scripts/ci --exclude-dir target || true)"
fi

if [[ -z "${MATCHES}" ]]; then
    echo "Desktop entry guard: OK"
    exit 0
fi

BAD_LINES=()
while IFS= read -r line; do
    [[ -z "${line}" ]] && continue
    file="${line%%:*}"
    allowed=false
    for allowed_file in "${ALLOWLIST[@]}"; do
        if [[ "${file}" == "${allowed_file}" ]]; then
            allowed=true
            break
        fi
    done
    if [[ "${allowed}" == "false" ]]; then
        BAD_LINES+=("${line}")
    fi
done <<< "${MATCHES}"

if (( ${#BAD_LINES[@]} > 0 )); then
    echo "Desktop entry guard: found .desktop references outside installers:"
    printf '%s\n' "${BAD_LINES[@]}"
    exit 1
fi

echo "Desktop entry guard: OK"
