#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

ALLOWLIST=(
    "${ROOT}/scripts/product/jarvis-install.sh"
    "${ROOT}/scripts/product/jarvis-build-release.sh"
    "${ROOT}/scripts/product/jarvis-desktop-uninstall.sh"
)

PATTERN='\.desktop($|[^A-Za-z0-9_.])'

if command -v rg >/dev/null 2>&1; then
    MATCHES="$(rg -n "${PATTERN}" "${ROOT}/scripts" \
        --glob '!scripts/legacy/**' --glob '!scripts/ci/**' --glob '!**/target/**' || true)"
else
    # grep's --exclude-dir matches a directory BASENAME, not a path, so the
    # value must be `ci`/`legacy`/`target`, not `scripts/ci`. The path form
    # silently fails to exclude, which made the CI runner (no ripgrep -> grep
    # fallback) scan this very script and flag its own PATTERN/echo lines.
    MATCHES="$(grep -RIn -E "${PATTERN}" "${ROOT}/scripts" \
        --exclude-dir=legacy --exclude-dir=ci --exclude-dir=target || true)"
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
