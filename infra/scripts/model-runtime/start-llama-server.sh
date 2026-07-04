#!/usr/bin/env bash
# =============================================================================
# Start one llama.cpp server per active model section (main / coding / router).
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/_common.sh"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/model-runtime/start-llama-server.sh [section...]

If no sections are given, starts every section present in model-profile.yml.
Examples:
  ./start-llama-server.sh                # start all configured (main/coding/router)
  ./start-llama-server.sh main           # start only the main brain
  ./start-llama-server.sh main router    # start two
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --help|-h) usage; exit 0 ;;
  esac
done

require_profile
require_yq

if ! command -v "${LLAMA_SERVER_BIN}" >/dev/null 2>&1; then
  echo "❌ '${LLAMA_SERVER_BIN}' not in PATH. Install llama.cpp and put 'llama-server' in PATH." >&2
  echo "   See README.md in this directory for build instructions." >&2
  exit 1
fi

requested=("$@")
if (( ${#requested[@]} == 0 )); then
  requested=("${KNOWN_SECTIONS[@]}")
fi

# Reject unknown sections early.
for section in "${requested[@]}"; do
  case "${section}" in
    main|coding|router) ;;
    *) echo "❌ unknown model section '${section}' — expected one of: ${KNOWN_SECTIONS[*]}" >&2; exit 1 ;;
  esac
done

# ----------------------------------------------------------------------------

resolve_gpu_layers() {
  local raw="$1"
  case "${raw,,}" in
    auto) printf '%s' "999" ;;       # llama.cpp clips to actual layer count
    ""|null) printf '%s' "0" ;;
    *) printf '%s' "${raw}" ;;
  esac
}

start_section() {
  local section="$1"

  if ! profile_has_section "${section}"; then
    echo "⏭ section '${section}' not present in profile, skipping."
    return 0
  fi

  local pidfile log
  pidfile="$(pid_file_for "${section}")"
  log="$(log_file_for "${section}")"

  if is_running "${pidfile}"; then
    echo "↺ ${section}: already running (pid $(cat "${pidfile}"))"
    return 0
  fi

  local name path port context gpu_layers temperature gpu_arg
  name="$(profile_get "${section}" name)"
  path="$(profile_get "${section}" path)"
  port="$(profile_get "${section}" port)"
  context="$(profile_get "${section}" context)"
  gpu_layers="$(profile_get "${section}" gpu_layers)"
  temperature="$(profile_get "${section}" temperature)"

  if [[ -z "${path}" || -z "${port}" ]]; then
    echo "❌ ${section}: profile missing 'path' or 'port'" >&2
    return 1
  fi
  if [[ ! -f "${path}" ]]; then
    echo "❌ ${section}: model file not found at '${path}'" >&2
    echo "   No automatic download will be attempted (SPEC-1). Place the GGUF manually." >&2
    return 1
  fi

  gpu_arg="$(resolve_gpu_layers "${gpu_layers}")"

  echo "▶ ${section}: starting ${name} on :${port} (ctx=${context}, gpu=${gpu_arg}, temp=${temperature})"
  echo "  model: ${path}"
  echo "  log:   ${log}"

  nohup "${LLAMA_SERVER_BIN}" \
    --host 0.0.0.0 \
    --port "${port}" \
    --model "${path}" \
    --ctx-size "${context}" \
    --temp "${temperature}" \
    --n-gpu-layers "${gpu_arg}" \
    --alias "${name}" \
    >"${log}" 2>&1 &
  local pid=$!
  echo "${pid}" >"${pidfile}"

  # Best-effort wait for the health endpoint to come up.
  local deadline=$(( $(date +%s) + 60 ))
  while (( $(date +%s) < deadline )); do
    if curl -fsS -m 2 "http://127.0.0.1:${port}/health" >/dev/null 2>&1; then
      echo "✅ ${section}: healthy (pid ${pid})"
      return 0
    fi
    if ! kill -0 "${pid}" 2>/dev/null; then
      echo "❌ ${section}: process exited during startup — see ${log}" >&2
      rm -f "${pidfile}"
      return 1
    fi
    sleep 2
  done
  echo "⚠ ${section}: did not report healthy within 60s, but process is alive (pid ${pid})" >&2
  return 1
}

rc=0
for section in "${requested[@]}"; do
  start_section "${section}" || rc=$?
  echo ""
done
exit "${rc}"
