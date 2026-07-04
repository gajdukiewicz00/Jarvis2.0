#!/usr/bin/env bash
# Seed the voice-gateway Vosk models PVC.
#
# voice-gateway expects models at:
#   /models/stt/vosk/vosk-model-small-ru-0.22
#   /models/stt/vosk/vosk-model-small-en-us-0.15
# (paths come from JARVIS_VOSK_MODEL_PATH_RU/EN in infra/k8s/base/voice-gateway).
#
# Source models default to ${HOME}/.jarvis/models/vosk/<name>.  If they are
# missing we attempt to download them with the same URLs used by
# scripts/setup-voice-local.sh.  The seeded layout under the PVC is:
#   stt/vosk/vosk-model-small-ru-0.22/...
#   stt/vosk/vosk-model-small-en-us-0.15/...
#
# Idempotent: re-runs verify file count and exit 0 if the models are already
# present.  Use --force to overwrite.

set -euo pipefail

KUBECONFIG="${KUBECONFIG:-$HOME/.jarvis/kubeconfig}"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
PVC_NAME="${JARVIS_VOSK_MODEL_PVC:-vosk-models-pvc}"
LOADER_POD="${JARVIS_VOSK_MODEL_LOADER_POD:-vosk-model-loader}"
LOADER_IMAGE="${JARVIS_VOSK_MODEL_LOADER_IMAGE:-busybox:1.36.1}"
LOCAL_MODELS_DIR="${JARVIS_LOCAL_MODELS_DIR:-${HOME}/.jarvis/models}"
VOSK_MODEL_RU="${JARVIS_VOSK_MODEL_NAME_RU:-vosk-model-small-ru-0.22}"
VOSK_MODEL_EN="${JARVIS_VOSK_MODEL_NAME_EN:-vosk-model-small-en-us-0.15}"
VOSK_MODEL_RU_URL="${VOSK_MODEL_RU_URL:-https://alphacephei.com/vosk/models/${VOSK_MODEL_RU}.zip}"
VOSK_MODEL_EN_URL="${VOSK_MODEL_EN_URL:-https://alphacephei.com/vosk/models/${VOSK_MODEL_EN}.zip}"
FORCE="false"
if [[ "${1:-}" == "--force" ]]; then
    FORCE="true"
fi

log() { printf '[seed-vosk] %s\n' "$*"; }
fail() { printf '[seed-vosk] ERROR: %s\n' "$*" >&2; exit 1; }

ensure_local_model() {
    local name="$1"
    local url="$2"
    local local_dir="${LOCAL_MODELS_DIR}/vosk/${name}"
    if [[ -d "${local_dir}" && -n "$(ls -A "${local_dir}" 2>/dev/null)" ]]; then
        return 0
    fi
    log "Downloading ${name} from ${url}"
    local tmp_dir archive
    tmp_dir="$(mktemp -d)"
    archive="${tmp_dir}/${name}.zip"
    curl -fsSL "${url}" -o "${archive}"
    python3 - "${archive}" "${tmp_dir}" <<'PY'
import sys, zipfile
zip_path, extract_dir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(zip_path) as zf:
    zf.extractall(extract_dir)
PY
    local extracted
    extracted="$(find "${tmp_dir}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    [[ -n "${extracted}" ]] || fail "Archive ${archive} did not extract a model directory"
    mkdir -p "$(dirname "${local_dir}")"
    rm -rf "${local_dir}"
    mv "${extracted}" "${local_dir}"
    log "Installed ${name} -> ${local_dir}"
}

ensure_local_model "${VOSK_MODEL_RU}" "${VOSK_MODEL_RU_URL}"
ensure_local_model "${VOSK_MODEL_EN}" "${VOSK_MODEL_EN_URL}"

cleanup() {
    KUBECONFIG="${KUBECONFIG}" kubectl delete pod "${LOADER_POD}" -n "${NAMESPACE}" --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT

KUBECONFIG="${KUBECONFIG}" kubectl delete pod "${LOADER_POD}" -n "${NAMESPACE}" --ignore-not-found >/dev/null 2>&1 || true

cat <<EOF | KUBECONFIG="${KUBECONFIG}" kubectl apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: ${LOADER_POD}
  namespace: ${NAMESPACE}
  labels:
    app: vosk-model-loader
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    fsGroup: 10001
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: loader
      image: ${LOADER_IMAGE}
      command: ["sh", "-lc", "sleep 1800"]
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
      volumeMounts:
        - name: models
          mountPath: /models
        - name: tmp
          mountPath: /tmp
  volumes:
    - name: models
      persistentVolumeClaim:
        claimName: ${PVC_NAME}
    - name: tmp
      emptyDir: {}
EOF

KUBECONFIG="${KUBECONFIG}" kubectl wait --for=condition=Ready "pod/${LOADER_POD}" -n "${NAMESPACE}" --timeout=180s >/dev/null

needs_seed() {
    local name="$1"
    local marker="${name}/am/final.mdl"
    if [[ "${FORCE}" == "true" ]]; then
        return 0
    fi
    if KUBECONFIG="${KUBECONFIG}" kubectl exec -n "${NAMESPACE}" "${LOADER_POD}" -- \
        sh -c "test -s /models/stt/vosk/${marker}" >/dev/null 2>&1; then
        return 1
    fi
    return 0
}

seed_one() {
    local name="$1"
    local src="${LOCAL_MODELS_DIR}/vosk/${name}"
    [[ -d "${src}" ]] || fail "Source model directory missing: ${src}"

    if ! needs_seed "${name}"; then
        log "${name} already present in PVC, skipping"
        return 0
    fi

    log "Seeding ${name} from ${src}"
    KUBECONFIG="${KUBECONFIG}" kubectl exec -n "${NAMESPACE}" "${LOADER_POD}" -- \
        sh -c "mkdir -p /models/stt/vosk && rm -rf /models/stt/vosk/${name}"
    KUBECONFIG="${KUBECONFIG}" kubectl cp "${src}" \
        "${NAMESPACE}/${LOADER_POD}:/models/stt/vosk/${name}"
    KUBECONFIG="${KUBECONFIG}" kubectl exec -n "${NAMESPACE}" "${LOADER_POD}" -- \
        sh -c "test -s /models/stt/vosk/${name}/am/final.mdl && ls -la /models/stt/vosk/${name} | head"
    log "Seeded ${name}"
}

seed_one "${VOSK_MODEL_RU}"
seed_one "${VOSK_MODEL_EN}"

log "Vosk PVC ready; restarting voice-gateway to re-load models"
KUBECONFIG="${KUBECONFIG}" kubectl rollout restart deployment/voice-gateway -n "${NAMESPACE}" >/dev/null
KUBECONFIG="${KUBECONFIG}" kubectl rollout status deployment/voice-gateway -n "${NAMESPACE}" --timeout=240s >/dev/null || \
    log "voice-gateway rollout did not complete in time; check kubectl describe / logs"

log "Done."
