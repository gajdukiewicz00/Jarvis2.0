#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 — Image build (Phase 2: Dockerless).
# =============================================================================
# This is a thin wrapper. The canonical entry is
# `infra/scripts/microk8s/build-images.sh` which uses Jib (Java) and podman
# (Python) without a container daemon.
#
# Legacy behavior used daemon build/export -> k3s ctr image import.
# New default is: jib:build / podman push -> localhost:5000 (MicroK8s registry).
#
# Compatibility flags:
#   --no-import       === --mode=registry (no extra ctr import step)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CANONICAL="${ROOT_DIR}/infra/scripts/microk8s/build-images.sh"

if [[ ! -x "${CANONICAL}" ]]; then
  echo "❌ canonical builder missing: ${CANONICAL}" >&2
  exit 1
fi

forwarded=()
for arg in "$@"; do
  case "${arg}" in
    --no-import)
      forwarded+=("--mode=registry")
      ;;
    --help|-h)
      cat <<'EOF'
Usage: ./scripts/build-images.sh [--no-import] [extra args...]

This wrapper delegates to infra/scripts/microk8s/build-images.sh, which builds
OCI images via Jib (Java) and podman (Python), without a container daemon.

Old --no-import flag maps to --mode=registry on the canonical builder.
EOF
      "${CANONICAL}" --help
      exit 0
      ;;
    *)
      forwarded+=("${arg}")
      ;;
  esac
done

exec "${CANONICAL}" "${forwarded[@]}"
