#!/usr/bin/env bash
# =============================================================================
# Health probe for the host llama.cpp servers.
# Exits non-zero if any active section is unhealthy.
# Output is JSON-friendly so llm-service / monitoring can parse it.
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/_common.sh"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/model-runtime/health-llama-server.sh [--json] [section...]

Without --json: human-readable lines.
With    --json: a single JSON object summarising each section.
EOF
}

JSON_OUTPUT=false
sections=()
for arg in "$@"; do
  case "${arg}" in
    --json)   JSON_OUTPUT=true ;;
    --help|-h) usage; exit 0 ;;
    *) sections+=("${arg}") ;;
  esac
done

require_profile
require_yq

if (( ${#sections[@]} == 0 )); then
  sections=("${KNOWN_SECTIONS[@]}")
fi

probe_section() {
  local section="$1"
  if ! profile_has_section "${section}"; then
    printf '%s\t%s\n' "${section}" "absent"
    return 0
  fi
  local pidfile port pid status http_code
  pidfile="$(pid_file_for "${section}")"
  port="$(profile_get "${section}" port)"

  if [[ ! -f "${pidfile}" ]]; then
    printf '%s\tdown\tno-pid\n' "${section}"
    return 1
  fi
  pid="$(cat "${pidfile}")"
  if [[ -z "${pid}" ]] || ! kill -0 "${pid}" 2>/dev/null; then
    printf '%s\tdown\tprocess-dead\n' "${section}"
    return 1
  fi
  http_code="$(curl -s -o /dev/null -w '%{http_code}' -m 3 "http://127.0.0.1:${port}/health" || true)"
  if [[ "${http_code}" == "200" ]]; then
    printf '%s\thealthy\tpid=%s\tport=%s\n' "${section}" "${pid}" "${port}"
    return 0
  fi
  printf '%s\tdegraded\tpid=%s\tport=%s\thttp=%s\n' "${section}" "${pid}" "${port}" "${http_code}"
  return 1
}

results=()
overall_rc=0
for section in "${sections[@]}"; do
  if line="$(probe_section "${section}")"; then
    results+=("${line}")
  else
    results+=("${line}")
    overall_rc=1
  fi
done

if "${JSON_OUTPUT}"; then
  printf '{\n  "sections": [\n'
  local_first=true
  for line in "${results[@]}"; do
    if "${local_first}"; then local_first=false; else printf ',\n'; fi
    IFS=$'\t' read -r section status rest <<<"${line}"
    printf '    {"name":"%s","status":"%s","detail":"%s"}' \
      "${section}" "${status}" "${rest}"
  done
  printf '\n  ]\n}\n'
else
  for line in "${results[@]}"; do
    printf '%s\n' "${line}"
  done
fi

exit "${overall_rc}"
