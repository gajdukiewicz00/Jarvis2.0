#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Promote Backend Images and Generate Digest-Pinned Release Overlay
# =============================================================================
# Supported paths:
#   1. Promote locally built images to a registry and resolve repo digests
#   2. Consume a refs file with image@sha256 entries produced elsewhere (CI)
#
# Output:
#   ${JARVIS_RELEASE_OUTPUT_DIR:-k8s/overlays/prod-release}/kustomization.yaml
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_DIR="${JARVIS_RELEASE_OUTPUT_DIR:-${PROJECT_ROOT}/k8s/overlays/prod-release}"

IMAGE_REGISTRY="${IMAGE_REGISTRY:-}"
IMAGE_REPO="${IMAGE_REPO:-jarvis}"
IMAGE_TAG="${IMAGE_TAG:-prod}"
SOURCE_IMAGE_REPO="${SOURCE_IMAGE_REPO:-jarvis}"
SOURCE_IMAGE_TAG="${SOURCE_IMAGE_TAG:-local}"
REFS_FILE=""
WRITE_REFS_FILE=""
PUSH_IMAGES=false
INCLUDE_DATA=false
INCLUDE_LLM=false

CORE_BACKEND_SERVICES=(
  api-gateway
  security-service
  user-profile
  nlp-service
  orchestrator
  voice-gateway
  smart-home-service
  life-tracker
  analytics-service
  planner-service
  pc-control
)

OPTIONAL_DATA_SERVICES=(
  memory-service
  embedding-service
)

LLM_SERVICES=(
  llm-service
  llm-server
)

declare -A IMAGE_DIGEST_REFS=()

usage() {
  cat <<'EOF'
Usage: ./scripts/product/jarvis-promote-images.sh [options]

Options:
  --push                    Tag and push promoted images before resolving digests
  --refs-file=PATH          Read service=image@sha256:... refs from an existing file
  --write-refs-file=PATH    Write resolved refs to PATH for audit/CI handoff
  --include-data            Also include optional memory-service and embedding-service
  --include-llm             Also include optional llm-service and llm-server in the release overlay
  --help, -h                Show this help

Environment when not using --refs-file:
  IMAGE_REGISTRY            Registry host, for example ghcr.io (required)
  IMAGE_REPO                Registry repo prefix, default: jarvis
  IMAGE_TAG                 Target tag to push and resolve, default: prod
  SOURCE_IMAGE_REPO         Local source repo prefix, default: jarvis
  SOURCE_IMAGE_TAG          Local source tag, default: local
  JARVIS_RELEASE_OUTPUT_DIR Optional output directory for the generated release overlay

Examples:
  IMAGE_REGISTRY=ghcr.io IMAGE_REPO=my-org/jarvis IMAGE_TAG=2026-03-21 \
    ./scripts/product/jarvis-promote-images.sh --push

  JARVIS_RELEASE_OUTPUT_DIR=/tmp/jarvis-prod-release \
    ./scripts/product/jarvis-promote-images.sh \
      --refs-file=/tmp/backend-image-refs.env

  ./scripts/product/jarvis-promote-images.sh \
    --include-data --include-llm \
    --refs-file=/tmp/backend-image-refs.env

  ./scripts/product/jarvis-promote-images.sh \
    --refs-file=/tmp/backend-image-refs.env
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --push)
      PUSH_IMAGES=true
      ;;
    --refs-file=*)
      REFS_FILE="${arg#*=}"
      ;;
    --write-refs-file=*)
      WRITE_REFS_FILE="${arg#*=}"
      ;;
    --include-data)
      INCLUDE_DATA=true
      ;;
    --include-llm)
      INCLUDE_LLM=true
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "❌ Unknown argument: ${arg}" >&2
      usage >&2
      exit 1
      ;;
  esac
done

trim_slashes() {
  local value="$1"
  value="${value#/}"
  value="${value%/}"
  printf '%s' "${value}"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

target_image_base() {
  local registry
  local repo
  registry="$(trim_slashes "${IMAGE_REGISTRY}")"
  repo="$(trim_slashes "${IMAGE_REPO}")"
  if [[ -z "${registry}" ]]; then
    echo "❌ IMAGE_REGISTRY must not be empty when promoting local images" >&2
    exit 1
  fi
  if [[ -z "${repo}" ]]; then
    echo "❌ IMAGE_REPO must not be empty" >&2
    exit 1
  fi
  printf '%s/%s' "${registry}" "${repo}"
}

source_image_ref() {
  local service="$1"
  printf '%s/%s:%s' "$(trim_slashes "${SOURCE_IMAGE_REPO}")" "${service}" "${SOURCE_IMAGE_TAG}"
}

target_tagged_ref() {
  local service="$1"
  printf '%s/%s:%s' "$(target_image_base)" "${service}" "${IMAGE_TAG}"
}

known_service() {
  local service="$1"
  local candidate
  for candidate in \
    "${CORE_BACKEND_SERVICES[@]}" \
    "${OPTIONAL_DATA_SERVICES[@]}" \
    "${LLM_SERVICES[@]}"; do
    if [[ "${service}" == "${candidate}" ]]; then
      return 0
    fi
  done
  return 1
}

release_services() {
  local services=("${CORE_BACKEND_SERVICES[@]}")
  if [[ "${INCLUDE_DATA}" == "true" ]]; then
    services+=("${OPTIONAL_DATA_SERVICES[@]}")
  fi
  if [[ "${INCLUDE_LLM}" == "true" ]]; then
    services+=("${LLM_SERVICES[@]}")
  fi
  printf '%s\n' "${services[@]}"
}

validate_digest_ref() {
  local service="$1"
  local ref="$2"
  if [[ ! "${ref}" =~ ^[^[:space:]]+@sha256:[0-9a-fA-F]{64}$ ]]; then
    echo "❌ Service '${service}' has invalid digest ref: ${ref}" >&2
    exit 1
  fi
}

resolve_digest_ref_from_local_image() {
  local service="$1"
  local source_ref
  local target_ref
  local repo_digests
  local digest_ref
  local push_output
  local pushed_digest

  source_ref="$(source_image_ref "${service}")"
  target_ref="$(target_tagged_ref "${service}")"

  if ! docker image inspect "${source_ref}" >/dev/null 2>&1; then
    echo "❌ Missing local source image: ${source_ref}" >&2
    echo "   Build backend images first, or use --refs-file with pre-resolved digests." >&2
    exit 1
  fi

  docker tag "${source_ref}" "${target_ref}"
  if [[ "${PUSH_IMAGES}" == "true" ]]; then
    echo "📦 Pushing ${target_ref}"
    push_output="$(docker push "${target_ref}" 2>&1)"
    printf '%s\n' "${push_output}" >/dev/null
    pushed_digest="$(printf '%s\n' "${push_output}" | sed -n 's/^.*digest: \(sha256:[0-9a-fA-F]\{64\}\).*$/\1/p' | tail -n1)"
    if [[ -n "${pushed_digest}" ]]; then
      digest_ref="$(target_image_base)/${service}@${pushed_digest}"
      IMAGE_DIGEST_REFS["${service}"]="${digest_ref}"
      return 0
    fi
  fi

  repo_digests="$(docker image inspect "${target_ref}" --format '{{range .RepoDigests}}{{println .}}{{end}}' 2>/dev/null || true)"
  digest_ref="$(printf '%s\n' "${repo_digests}" | grep -E "^$(target_image_base)/${service}@sha256:[0-9a-fA-F]{64}$" | head -n1 || true)"
  if [[ -z "${digest_ref}" ]]; then
    echo "❌ No registry digest found for ${target_ref}" >&2
    echo "   Push the image first (--push), or provide --refs-file with immutable image@sha256 references." >&2
    exit 1
  fi

  IMAGE_DIGEST_REFS["${service}"]="${digest_ref}"
}

load_refs_file() {
  local line
  local service
  local ref

  if [[ ! -f "${REFS_FILE}" ]]; then
    echo "❌ Refs file not found: ${REFS_FILE}" >&2
    exit 1
  fi

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

    if ! known_service "${service}"; then
      echo "❌ Unknown service in refs file: ${service}" >&2
      exit 1
    fi
    validate_digest_ref "${service}" "${ref}"
    IMAGE_DIGEST_REFS["${service}"]="${ref}"
  done < "${REFS_FILE}"
}

ensure_all_required_refs_present() {
  local service
  while IFS= read -r service; do
    if [[ -z "${IMAGE_DIGEST_REFS[${service}]:-}" ]]; then
      echo "❌ Missing immutable image ref for service '${service}'" >&2
      if [[ -n "${REFS_FILE}" ]]; then
        echo "   Update ${REFS_FILE} so every promoted backend service has service=image@sha256:... ." >&2
      else
        echo "   Make sure the image was built and pushed successfully." >&2
      fi
      exit 1
    fi
  done < <(release_services)
}

write_refs_file() {
  local target_file="$1"
  local service
  mkdir -p "$(dirname "${target_file}")"
  : > "${target_file}"
  while IFS= read -r service; do
    printf '%s=%s\n' "${service}" "${IMAGE_DIGEST_REFS[${service}]}" >> "${target_file}"
  done < <(release_services)
}

write_release_overlay() {
  local overlay_file="${OUTPUT_DIR}/kustomization.yaml"
  local service
  local ref
  local new_name
  local digest

  mkdir -p "${OUTPUT_DIR}"

  cat > "${overlay_file}" <<EOF
# Generated by scripts/product/jarvis-promote-images.sh on $(date -Is)
# Do not hand-edit. Regenerate from promoted image digests.
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../prod

images:
EOF

  while IFS= read -r service; do
    ref="${IMAGE_DIGEST_REFS[${service}]}"
    new_name="${ref%@sha256:*}"
    digest="sha256:${ref##*@sha256:}"
    cat >> "${overlay_file}" <<EOF
  - name: jarvis/${service}
    newName: ${new_name}
    digest: ${digest}
EOF
  done < <(release_services)
}

main() {
  if [[ -n "${REFS_FILE}" ]]; then
    load_refs_file
  else
    require_cmd docker
    while IFS= read -r service; do
      resolve_digest_ref_from_local_image "${service}"
    done < <(release_services)
  fi

  ensure_all_required_refs_present
  write_release_overlay

  if [[ -n "${WRITE_REFS_FILE}" ]]; then
    write_refs_file "${WRITE_REFS_FILE}"
  fi

  echo "✅ Wrote digest-pinned release overlay: ${OUTPUT_DIR}/kustomization.yaml"
  if [[ -n "${WRITE_REFS_FILE}" ]]; then
    echo "✅ Wrote image refs: ${WRITE_REFS_FILE}"
  fi
  echo "Next:"
  echo "  K8S_PREFLIGHT_MODE=server K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE=enforce \\"
  echo "    ./scripts/ci/k8s-preflight.sh ${OUTPUT_DIR}"
  echo "  ./scripts/product/jarvis-deploy-prod.sh --overlay=${OUTPUT_DIR}"
}

main
