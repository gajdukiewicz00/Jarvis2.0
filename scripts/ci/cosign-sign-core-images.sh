#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Sign Core Release Images With Cosign
# =============================================================================
#
# Input priority:
#   1. COSIGN_CORE_IMAGES            newline/comma/space separated image@sha256 refs
#   2. COSIGN_CORE_IMAGES_FILE       refs file in service=image@sha256 format
#   3. COSIGN_RELEASE_OVERLAY        generated prod-release kustomization.yaml
#
# Modes:
#   keyless   default, intended for GitHub Actions OIDC
#   key-pair  uses COSIGN_PRIVATE_KEY (+ optional COSIGN_PASSWORD)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

COSIGN_SIGN_MODE="${COSIGN_SIGN_MODE:-keyless}"
COSIGN_RELEASE_OVERLAY="${COSIGN_RELEASE_OVERLAY:-${PROJECT_ROOT}/k8s/overlays/prod-release/kustomization.yaml}"
COSIGN_CORE_IMAGES_FILE="${COSIGN_CORE_IMAGES_FILE:-}"
COSIGN_CORE_IMAGES="${COSIGN_CORE_IMAGES:-}"

CORE_SERVICES=(
  api-gateway
  orchestrator
  security-service
  voice-gateway
  planner-service
  life-tracker
)

declare -a IMAGE_REFS=()
declare -A IMAGE_REF_SEEN=()
COSIGN_KEY_FILE=""

usage() {
  cat <<'EOF'
Usage: ./scripts/ci/cosign-sign-core-images.sh

Input priority:
  1. COSIGN_CORE_IMAGES      newline/comma/space separated image@sha256 refs
  2. COSIGN_CORE_IMAGES_FILE service=image@sha256 refs file
  3. COSIGN_RELEASE_OVERLAY  prod-release kustomization.yaml

Modes:
  COSIGN_SIGN_MODE=keyless   default, GitHub OIDC based signing
  COSIGN_SIGN_MODE=key-pair  requires COSIGN_PRIVATE_KEY and optional COSIGN_PASSWORD
EOF
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

validate_digest_ref() {
  local ref="$1"
  if [[ ! "${ref}" =~ ^[^[:space:]]+@sha256:[0-9a-fA-F]{64}$ ]]; then
    echo "❌ Invalid digest ref: ${ref}" >&2
    exit 1
  fi
}

append_ref() {
  local ref="$1"
  validate_digest_ref "${ref}"
  if [[ -n "${IMAGE_REF_SEEN[${ref}]:-}" ]]; then
    return 0
  fi
  IMAGE_REF_SEEN["${ref}"]=1
  IMAGE_REFS+=("${ref}")
}

load_refs_from_env() {
  local ref
  while IFS= read -r ref; do
    [[ -z "${ref//[[:space:]]/}" ]] && continue
    append_ref "${ref}"
  done < <(printf '%s\n' "${COSIGN_CORE_IMAGES}" | tr ',\t ' '\n\n\n' | sed '/^[[:space:]]*$/d')
}

service_is_core() {
  local service="$1"
  local candidate
  for candidate in "${CORE_SERVICES[@]}"; do
    if [[ "${service}" == "${candidate}" ]]; then
      return 0
    fi
  done
  return 1
}

load_refs_from_file() {
  local line
  local service
  local ref

  [[ -f "${COSIGN_CORE_IMAGES_FILE}" ]] || {
    echo "❌ COSIGN_CORE_IMAGES_FILE not found: ${COSIGN_CORE_IMAGES_FILE}" >&2
    exit 1
  }

  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${line//[[:space:]]/}" ]] && continue
    if [[ "${line}" != *=* ]]; then
      echo "❌ Invalid refs file entry: ${line}" >&2
      exit 1
    fi
    service="${line%%=*}"
    ref="${line#*=}"
    service="${service//[[:space:]]/}"
    ref="$(printf '%s' "${ref}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if service_is_core "${service}"; then
      append_ref "${ref}"
    fi
  done < "${COSIGN_CORE_IMAGES_FILE}"
}

load_refs_from_overlay() {
  local line
  local name=""
  local new_name=""
  local digest=""
  local service=""

  [[ -f "${COSIGN_RELEASE_OVERLAY}" ]] || {
    echo "❌ No signing inputs found." >&2
    echo "   Set COSIGN_CORE_IMAGES, COSIGN_CORE_IMAGES_FILE, or COSIGN_RELEASE_OVERLAY." >&2
    exit 1
  }

  while IFS= read -r line || [[ -n "${line}" ]]; do
    case "${line}" in
      "  - name: "*)
        name="${line#  - name: }"
        new_name=""
        digest=""
        ;;
      "    newName: "*)
        new_name="${line#    newName: }"
        ;;
      "    digest: "*)
        digest="${line#    digest: }"
        service="${name#jarvis/}"
        if service_is_core "${service}" && [[ -n "${new_name}" && -n "${digest}" ]]; then
          append_ref "${new_name}@${digest}"
        fi
        ;;
    esac
  done < "${COSIGN_RELEASE_OVERLAY}"
}

load_image_refs() {
  if [[ -n "${COSIGN_CORE_IMAGES//[[:space:]]/}" ]]; then
    load_refs_from_env
  elif [[ -n "${COSIGN_CORE_IMAGES_FILE}" ]]; then
    load_refs_from_file
  else
    load_refs_from_overlay
  fi

  if [[ "${#IMAGE_REFS[@]}" -eq 0 ]]; then
    echo "❌ No core image digests were resolved for signing." >&2
    exit 1
  fi
}

sign_keyless() {
  local ref="$1"
  cosign sign --yes "${ref}"
}

cleanup() {
  if [[ -n "${COSIGN_KEY_FILE}" && -f "${COSIGN_KEY_FILE}" ]]; then
    rm -f "${COSIGN_KEY_FILE}"
  fi
}

prepare_key_pair() {
  if [[ -n "${COSIGN_KEY_FILE}" ]]; then
    return 0
  fi

  [[ -n "${COSIGN_PRIVATE_KEY:-}" ]] || {
    echo "❌ COSIGN_PRIVATE_KEY is required for COSIGN_SIGN_MODE=key-pair" >&2
    exit 1
  }

  COSIGN_KEY_FILE="$(mktemp)"
  chmod 600 "${COSIGN_KEY_FILE}"
  printf '%s' "${COSIGN_PRIVATE_KEY}" > "${COSIGN_KEY_FILE}"
}

sign_key_pair() {
  local ref="$1"
  prepare_key_pair
  cosign sign --yes --key "${COSIGN_KEY_FILE}" "${ref}"
}

main() {
  local ref

  if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
  fi

  require_cmd cosign
  trap cleanup EXIT
  load_image_refs

  echo "🔏 Signing ${#IMAGE_REFS[@]} core image digest(s) with cosign (${COSIGN_SIGN_MODE})"
  for ref in "${IMAGE_REFS[@]}"; do
    echo "   ${ref}"
    case "${COSIGN_SIGN_MODE}" in
      keyless)
        sign_keyless "${ref}"
        ;;
      key-pair|keypair)
        sign_key_pair "${ref}"
        ;;
      *)
        echo "❌ Unsupported COSIGN_SIGN_MODE: ${COSIGN_SIGN_MODE}" >&2
        exit 1
        ;;
    esac
  done

  echo "✅ Cosign signing completed"
}

main "$@"
