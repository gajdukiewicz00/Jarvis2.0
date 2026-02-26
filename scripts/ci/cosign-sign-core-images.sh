#!/usr/bin/env bash
set -euo pipefail

# Signs core image digests with Cosign.
# Input: COSIGN_CORE_IMAGES as newline/comma/space separated image@sha256 refs.
# Modes:
# - keyless (default): uses OIDC identity
# - key-pair: uses COSIGN_PRIVATE_KEY (+ COSIGN_PASSWORD)

COSIGN_SIGN_MODE="${COSIGN_SIGN_MODE:-keyless}"
COSIGN_CORE_IMAGES_RAW="${COSIGN_CORE_IMAGES:-}"

if ! command -v cosign >/dev/null 2>&1; then
  echo "❌ cosign is not installed"
  exit 1
fi

if [[ -z "${COSIGN_CORE_IMAGES_RAW//[[:space:]]/}" ]]; then
  echo "ℹ️ No COSIGN_CORE_IMAGES provided; skipping signing step"
  exit 0
fi

if [[ "${COSIGN_SIGN_MODE}" != "keyless" && "${COSIGN_SIGN_MODE}" != "key-pair" ]]; then
  echo "❌ Unsupported COSIGN_SIGN_MODE='${COSIGN_SIGN_MODE}' (expected: keyless|key-pair)"
  exit 1
fi

if [[ "${COSIGN_SIGN_MODE}" == "key-pair" ]]; then
  if [[ -z "${COSIGN_PRIVATE_KEY:-}" ]]; then
    echo "❌ COSIGN_PRIVATE_KEY is required for key-pair mode"
    exit 1
  fi
  if [[ -z "${COSIGN_PASSWORD:-}" ]]; then
    echo "❌ COSIGN_PASSWORD is required for key-pair mode"
    exit 1
  fi
fi

tmp_file="$(mktemp)"
trap 'rm -f "${tmp_file}"' EXIT
printf '%s\n' "${COSIGN_CORE_IMAGES_RAW}" | tr ', ' '\n\n' | sed '/^[[:space:]]*$/d' > "${tmp_file}"

while IFS= read -r image_ref; do
  image_ref="${image_ref//[[:space:]]/}"
  [[ -z "${image_ref}" ]] && continue

  if [[ ! "${image_ref}" =~ @sha256:[0-9a-fA-F]{64}$ ]]; then
    echo "❌ Ref '${image_ref}' is not a pinned digest (expected image@sha256:<64hex>)"
    exit 1
  fi

  echo "🔏 Signing ${image_ref} via cosign (${COSIGN_SIGN_MODE})"
  if [[ "${COSIGN_SIGN_MODE}" == "key-pair" ]]; then
    cosign sign --yes --key env://COSIGN_PRIVATE_KEY "${image_ref}"
  else
    cosign sign --yes "${image_ref}"
  fi
done < "${tmp_file}"

echo "✅ Cosign signing completed"
