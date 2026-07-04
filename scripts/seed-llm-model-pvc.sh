#!/usr/bin/env bash
set -euo pipefail

KUBECONFIG="${KUBECONFIG:-$HOME/.jarvis/kubeconfig}"
NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
MODEL_SOURCE="${1:-${JARVIS_LLM_MODEL_PATH:-$HOME/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf}}"
PVC_NAME="${JARVIS_LLM_MODEL_PVC:-llm-models-pvc}"
LOADER_POD="${JARVIS_LLM_MODEL_LOADER_POD:-llm-model-loader}"
LOADER_IMAGE="${JARVIS_LLM_MODEL_LOADER_IMAGE:-busybox:1.36.1}"
TARGET_NAME="${JARVIS_LLM_MODEL_TARGET_NAME:-$(basename "${MODEL_SOURCE}")}"

if [[ ! -f "${MODEL_SOURCE}" ]]; then
    echo "Model file not found: ${MODEL_SOURCE}" >&2
    exit 1
fi

cleanup() {
    KUBECONFIG="${KUBECONFIG}" kubectl delete pod "${LOADER_POD}" -n "${NAMESPACE}" --ignore-not-found >/dev/null 2>&1 || true
}

trap cleanup EXIT

cat <<EOF | KUBECONFIG="${KUBECONFIG}" kubectl apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: ${LOADER_POD}
  namespace: ${NAMESPACE}
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
      command: ["sh", "-lc", "sleep 3600"]
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

KUBECONFIG="${KUBECONFIG}" kubectl wait --for=condition=Ready "pod/${LOADER_POD}" -n "${NAMESPACE}" --timeout=300s >/dev/null
KUBECONFIG="${KUBECONFIG}" kubectl cp "${MODEL_SOURCE}" "${NAMESPACE}/${LOADER_POD}:/models/${TARGET_NAME}"
KUBECONFIG="${KUBECONFIG}" kubectl exec -n "${NAMESPACE}" "${LOADER_POD}" -- sh -lc "test -s /models/${TARGET_NAME} && ls -lh /models/${TARGET_NAME}"

echo "Seeded ${PVC_NAME} with ${TARGET_NAME}"
