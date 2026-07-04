#!/usr/bin/env bash
# =============================================================================
# Migrate the LLM stack from legacy `jarvis` namespace into `jarvis-prod`.
# =============================================================================
# Prerequisites (already done by this point):
#   - llama-server running on host at 0.0.0.0:18080 (CPU-only fallback because
#     the NVIDIA driver kernel module isn't loaded; see TODO at the bottom).
#   - Manifests for embedding-service / memory-service / llm-service /
#     postgres-pgvector already deployed in jarvis-prod (replicas=0).
#
# Idempotent: safe to re-run.
# =============================================================================
set -euo pipefail

KCTL=(sudo k3s kubectl -n jarvis-prod)
HOST_IP="${HOST_IP:-10.113.0.176}"

echo "==> 1/5  Patch host-model-daemon Endpoints -> ${HOST_IP}"
"${KCTL[@]}" patch endpoints host-model-daemon --type merge -p "$(cat <<EOF
{
  "subsets": [
    {
      "addresses": [{"ip": "${HOST_IP}"}],
      "ports": [
        {"name": "main",   "port": 18080, "protocol": "TCP"},
        {"name": "coding", "port": 18081, "protocol": "TCP"},
        {"name": "router", "port": 18082, "protocol": "TCP"}
      ]
    }
  ]
}
EOF
)"
"${KCTL[@]}" get endpoints host-model-daemon

echo
echo "==> 2/5  Scale postgres-pgvector StatefulSet -> 1 (creates jarvis_memory DB on first boot)"
"${KCTL[@]}" scale statefulset/postgres-pgvector --replicas=1
"${KCTL[@]}" rollout status statefulset/postgres-pgvector --timeout=180s

echo
echo "==> 3/5  Scale embedding-service Deployment -> 1"
"${KCTL[@]}" scale deploy/embedding-service --replicas=1
"${KCTL[@]}" rollout status deploy/embedding-service --timeout=240s

echo
echo "==> 4/5  Scale memory-service Deployment -> 1"
"${KCTL[@]}" scale deploy/memory-service --replicas=1
"${KCTL[@]}" rollout status deploy/memory-service --timeout=240s

echo
echo "==> 5/5  Scale llm-service Deployment -> 1"
"${KCTL[@]}" scale deploy/llm-service --replicas=1
"${KCTL[@]}" rollout status deploy/llm-service --timeout=240s

echo
echo "==> Done. Final pod status:"
"${KCTL[@]}" get pods -l 'app in (postgres-pgvector,embedding-service,memory-service,llm-service)'

cat <<'NOTE'

NOTE — NVIDIA driver:
  llama-server is currently running on CPU only because the NVIDIA kernel
  module isn't loaded after this morning's reboot. To restore GPU inference:
    sudo apt install -y dkms
    sudo dpkg-reconfigure nvidia-dkms-590
    # OR reinstall the driver:
    sudo apt install --reinstall -y nvidia-driver-590-open
    sudo modprobe nvidia
    nvidia-smi
  Then restart llama-server with `-ngl 999` instead of `-ngl 0`.
NOTE
