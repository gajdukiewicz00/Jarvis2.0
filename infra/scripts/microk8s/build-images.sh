#!/usr/bin/env bash
# =============================================================================
# Jarvis 2.0 — Dockerless OCI image build (Phase 2).
# =============================================================================
# Builds OCI images for the local MicroK8s registry without using a container
# daemon.
#   Java services -> jib-maven-plugin (no daemon, no Dockerfile)
#   Python workers -> podman (or buildah) against Containerfile, daemonless
#
# By default images are pushed to localhost:5000 (the MicroK8s registry
# addon — enable with `microk8s enable registry`).
#
# Modes:
#   --mode=registry  (default) push to ${REGISTRY} via jib:build / podman push
#   --mode=tar              produce ${target}/*.image.tar files only, no push
#   --mode=ctr-import       build tarballs and `microk8s ctr image import` them
#   --no-python             skip Python workers (Java only)
#   --no-java               skip Java services
#   --tag=NAME              image tag (default: local)
#
# Env knobs:
#   REGISTRY            default: localhost:5000
#   IMAGE_NAMESPACE     default: jarvis
#   IMAGE_TAG           default: local
#   PODMAN_BIN          default: podman (falls back to buildah on Linux)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

MODE="${MODE:-registry}"
REGISTRY="${REGISTRY:-localhost:5000}"
IMAGE_NAMESPACE="${IMAGE_NAMESPACE:-jarvis}"
IMAGE_TAG="${IMAGE_TAG:-local}"
SKIP_JAVA="${SKIP_JAVA:-false}"
SKIP_PYTHON="${SKIP_PYTHON:-false}"
PODMAN_BIN="${PODMAN_BIN:-podman}"

JAVA_SERVICES=(
  api-gateway
  voice-gateway
  nlp-service
  orchestrator
  pc-control
  life-tracker
  analytics-service
  planner-service
  security-service
  user-profile
  smart-home-service
  sync-service
  llm-service
  memory-service
)

PYTHON_SERVICES=(
  embedding-service-py
  llm-server-py
)

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/microk8s/build-images.sh [options]

Modes:
  --mode=registry      (default) push images to ${REGISTRY}
  --mode=tar           produce OCI tarballs only
  --mode=ctr-import    build tarballs + microk8s ctr image import

Filters:
  --no-java            skip 13 Java services
  --no-python          skip 2 Python workers
  --service=NAME       build a single service (Java or Python)

Tag / registry:
  --tag=NAME           image tag (default: local)
  --registry=URL       registry hostport (default: localhost:5000)

Env knobs (all optional, override the same flags):
  MODE, REGISTRY, IMAGE_NAMESPACE, IMAGE_TAG, SKIP_JAVA, SKIP_PYTHON, PODMAN_BIN
EOF
}

SINGLE_SERVICE=""
for arg in "$@"; do
  case "${arg}" in
    --mode=*)         MODE="${arg#*=}" ;;
    --registry=*)     REGISTRY="${arg#*=}" ;;
    --tag=*)          IMAGE_TAG="${arg#*=}" ;;
    --no-java)        SKIP_JAVA=true ;;
    --no-python)      SKIP_PYTHON=true ;;
    --service=*)      SINGLE_SERVICE="${arg#*=}" ;;
    --help|-h)        usage; exit 0 ;;
    *) echo "❌ Unknown argument: ${arg}" >&2; usage >&2; exit 1 ;;
  esac
done

case "${MODE}" in
  registry|tar|ctr-import) ;;
  *) echo "❌ Invalid --mode='${MODE}'. Expected: registry | tar | ctr-import" >&2; exit 1 ;;
esac

cd "${ROOT_DIR}"

log()    { printf '── %s ──\n' "$*"; }
warn()   { printf '⚠ %s\n' "$*" >&2; }
fatal()  { printf '❌ %s\n' "$*" >&2; exit 1; }

resolve_python_bin() {
  if command -v "${PODMAN_BIN}" >/dev/null 2>&1; then
    return 0
  fi
  if command -v buildah >/dev/null 2>&1; then
    PODMAN_BIN=buildah
    warn "podman not found, using buildah instead"
    return 0
  fi
  fatal "Neither podman nor buildah is available. Install one of them (apt install podman) for Dockerless Python builds."
}

# ---------------------------------------------------------------------------
# Java services via Jib
# ---------------------------------------------------------------------------
build_java() {
  local goal=jib:build
  case "${MODE}" in
    registry)    goal=jib:build ;;
    tar|ctr-import) goal=jib:buildTar ;;
  esac

  local mvn_filter=""
  local svcs=("${JAVA_SERVICES[@]}")
  if [[ -n "${SINGLE_SERVICE}" ]]; then
    svcs=("${SINGLE_SERVICE}")
  fi

  for svc in "${svcs[@]}"; do
    if [[ ! -d "apps/${svc}" ]]; then
      continue
    fi
    if [[ -n "${mvn_filter}" ]]; then
      mvn_filter+=","
    fi
    mvn_filter+="apps/${svc}"
  done

  if [[ -z "${mvn_filter}" ]]; then
    log "no Java services selected"
    return 0
  fi

  log "Java: mvn package + ${goal} for [${mvn_filter}]"
  local mvn_args=(
    -pl "${mvn_filter}"
    -am
    -DskipTests
    -Djib.image.registry="${REGISTRY}"
    -Djib.image.namespace="${IMAGE_NAMESPACE}"
    -Djib.image.tag="${IMAGE_TAG}"
    package
    "${goal}"
  )
  mvn "${mvn_args[@]}"

  if [[ "${MODE}" == "ctr-import" ]]; then
    if ! command -v microk8s >/dev/null 2>&1; then
      fatal "--mode=ctr-import requires the 'microk8s' CLI"
    fi
    for svc in "${svcs[@]}"; do
      local tar="apps/${svc}/target/${svc}-1.0.0.image.tar"
      if [[ -f "${tar}" ]]; then
        log "ctr import: ${tar}"
        microk8s ctr image import "${tar}"
      fi
    done
  fi
}

# ---------------------------------------------------------------------------
# Python workers via podman
# ---------------------------------------------------------------------------
build_python() {
  resolve_python_bin
  local svcs=("${PYTHON_SERVICES[@]}")
  if [[ -n "${SINGLE_SERVICE}" ]]; then
    svcs=("${SINGLE_SERVICE}")
  fi

  for svc in "${svcs[@]}"; do
    local dir="apps/${svc}"
    [[ -d "${dir}" ]] || continue
    [[ -f "${dir}/Containerfile" ]] || { warn "no Containerfile in ${dir}, skipping"; continue; }

    local image_short="${IMAGE_NAMESPACE}/${svc%-py}"
    local image_full="${REGISTRY}/${image_short}:${IMAGE_TAG}"

    log "Python: ${PODMAN_BIN} build ${image_full}"
    "${PODMAN_BIN}" build -t "${image_full}" -f "${dir}/Containerfile" "${dir}"

    case "${MODE}" in
      registry)
        log "Python: ${PODMAN_BIN} push ${image_full}"
        "${PODMAN_BIN}" push --tls-verify=false "${image_full}"
        ;;
      tar)
        local out="${dir}/${svc}.image.tar"
        log "Python: ${PODMAN_BIN} save ${out}"
        "${PODMAN_BIN}" save -o "${out}" "${image_full}"
        ;;
      ctr-import)
        if ! command -v microk8s >/dev/null 2>&1; then
          fatal "--mode=ctr-import requires the 'microk8s' CLI"
        fi
        local tmptar
        tmptar="$(mktemp -t "${svc}-XXXXXX.tar")"
        "${PODMAN_BIN}" save -o "${tmptar}" "${image_full}"
        log "ctr import: ${tmptar}"
        microk8s ctr image import "${tmptar}"
        rm -f "${tmptar}"
        ;;
    esac
  done
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
echo "Build mode:    ${MODE}"
echo "Registry:      ${REGISTRY}"
echo "Namespace:     ${IMAGE_NAMESPACE}"
echo "Tag:           ${IMAGE_TAG}"
[[ -n "${SINGLE_SERVICE}" ]] && echo "Single service: ${SINGLE_SERVICE}"
echo ""

if [[ "${SKIP_JAVA}" != "true" ]]; then
  build_java
fi

if [[ "${SKIP_PYTHON}" != "true" ]]; then
  build_python
fi

echo ""
echo "✅ Image build complete (mode=${MODE})"
