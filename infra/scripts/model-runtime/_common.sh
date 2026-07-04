#!/usr/bin/env bash
# Shared helpers for the host model-runtime scripts (Phase 3).
# Sourced by start/stop/health-llama-server.sh — do not run directly.

# shellcheck shell=bash
set -euo pipefail

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "_common.sh is meant to be sourced." >&2
  exit 1
fi

JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
MODEL_RUNTIME_DIR="${JARVIS_HOME}/run/model-runtime"
MODEL_LOG_DIR="${JARVIS_HOME}/logs/model-runtime"
mkdir -p "${MODEL_RUNTIME_DIR}" "${MODEL_LOG_DIR}"

PROFILE_FILE="${MODEL_PROFILE_FILE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/model-profile.yml}"

LLAMA_SERVER_BIN="${LLAMA_SERVER_BIN:-llama-server}"

KNOWN_SECTIONS=(main coding router)

require_profile() {
  if [[ ! -f "${PROFILE_FILE}" ]]; then
    echo "❌ model profile not found: ${PROFILE_FILE}" >&2
    echo "   Copy infra/scripts/model-runtime/model-profile.example.yml to model-profile.yml and edit paths." >&2
    return 1
  fi
}

require_yq() {
  if ! command -v yq >/dev/null 2>&1; then
    echo "❌ 'yq' is required. Install: 'sudo apt install yq' or download https://github.com/mikefarah/yq" >&2
    return 1
  fi
}

# Reads a scalar value from the profile YAML.
# Usage: profile_get main name
profile_get() {
  local section="$1"
  local key="$2"
  yq -r ".${section}.${key} // \"\"" "${PROFILE_FILE}"
}

# Returns 0 if a section exists in the profile, 1 otherwise.
profile_has_section() {
  local section="$1"
  local raw
  raw="$(yq -r ".${section} // \"\"" "${PROFILE_FILE}" 2>/dev/null || true)"
  [[ -n "${raw}" && "${raw}" != "null" ]]
}

pid_file_for() { printf '%s/%s.pid' "${MODEL_RUNTIME_DIR}" "$1"; }
log_file_for() { printf '%s/%s.log' "${MODEL_LOG_DIR}" "$1"; }

is_running() {
  local pidfile="$1"
  [[ -f "${pidfile}" ]] || return 1
  local pid
  pid="$(cat "${pidfile}")"
  [[ -n "${pid}" ]] || return 1
  kill -0 "${pid}" 2>/dev/null
}
