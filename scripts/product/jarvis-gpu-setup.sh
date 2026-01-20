#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - GPU Setup for k3s
# =============================================================================
# Configures containerd for NVIDIA runtime and installs device plugin.
# Intended to be run via sudo/pkexec from the launcher/launch script.
# =============================================================================

set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run with sudo or pkexec: $0"
    exit 1
fi

if [[ -z "${KUBECONFIG:-}" && -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG="/etc/rancher/k3s/k3s.yaml"
fi

if ! command -v nvidia-smi >/dev/null 2>&1; then
    echo "ERROR: nvidia-smi not found. Install NVIDIA driver first."
    exit 1
fi

if ! command -v nvidia-ctk >/dev/null 2>&1; then
    echo "ERROR: nvidia-ctk not found. Install nvidia-container-toolkit."
    exit 1
fi

CONFIG_DIR="/var/lib/rancher/k3s/agent/etc/containerd"
CONFIG_TPL="${CONFIG_DIR}/config.toml.tmpl"
CONFIG_CURRENT="${CONFIG_DIR}/config.toml"

mkdir -p "${CONFIG_DIR}"
if [[ ! -f "${CONFIG_TPL}" && -f "${CONFIG_CURRENT}" ]]; then
    cp "${CONFIG_CURRENT}" "${CONFIG_TPL}"
fi

echo "Configuring containerd NVIDIA runtime..."
nvidia-ctk runtime configure --runtime=containerd --config="${CONFIG_TPL}" >/dev/null

if command -v systemctl >/dev/null 2>&1; then
    echo "Restarting k3s..."
    systemctl restart k3s
    sleep 3
fi

echo "Installing NVIDIA device plugin..."
kubectl apply -f https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/v0.14.1/nvidia-device-plugin.yml >/dev/null
kubectl -n kube-system rollout status ds/nvidia-device-plugin-daemonset --timeout=180s >/dev/null 2>&1 || true

ALLOCATABLE=$(kubectl get nodes -o jsonpath='{.items[*].status.allocatable.nvidia\.com/gpu}' 2>/dev/null || true)
if [[ -z "${ALLOCATABLE}" || "${ALLOCATABLE}" == "0" ]]; then
    echo "ERROR: GPU not allocatable in cluster after setup."
    exit 1
fi

echo "GPU setup complete (nvidia.com/gpu: ${ALLOCATABLE})."
