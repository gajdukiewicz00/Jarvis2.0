#!/usr/bin/env bash
# =============================================================================
# Stop the host llama.cpp servers (main / coding / router).
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/_common.sh"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/model-runtime/stop-llama-server.sh [section...]

If no sections are given, stops every section that has a running PID file.
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --help|-h) usage; exit 0 ;;
  esac
done

requested=("$@")
if (( ${#requested[@]} == 0 )); then
  requested=("${KNOWN_SECTIONS[@]}")
fi

stop_section() {
  local section="$1"
  local pidfile
  pidfile="$(pid_file_for "${section}")"

  if [[ ! -f "${pidfile}" ]]; then
    echo "⏭ ${section}: no PID file, nothing to stop"
    return 0
  fi

  local pid
  pid="$(cat "${pidfile}")"
  if [[ -z "${pid}" ]] || ! kill -0 "${pid}" 2>/dev/null; then
    echo "⏭ ${section}: PID ${pid:-?} not running, cleaning stale file"
    rm -f "${pidfile}"
    return 0
  fi

  echo "■ ${section}: SIGTERM pid ${pid}"
  kill -TERM "${pid}" 2>/dev/null || true

  local deadline=$(( $(date +%s) + 15 ))
  while (( $(date +%s) < deadline )); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      rm -f "${pidfile}"
      echo "✅ ${section}: stopped"
      return 0
    fi
    sleep 1
  done

  echo "⚠ ${section}: SIGKILL pid ${pid} (did not exit on SIGTERM)" >&2
  kill -KILL "${pid}" 2>/dev/null || true
  rm -f "${pidfile}"
}

for section in "${requested[@]}"; do
  case "${section}" in
    main|coding|router) ;;
    *) echo "❌ unknown section '${section}'" >&2; exit 1 ;;
  esac
  stop_section "${section}"
done
