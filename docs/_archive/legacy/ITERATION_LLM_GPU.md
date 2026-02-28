# Iteration: LLM GPU Support (perf/hardware)

**Date:** $(date -Is)  
**Status:** ✅ CODE COMPLETE, ⚠️ ACCEPTANCE PENDING  
**Goal:** Make LLM stack work reliably with GPU in Kubernetes (minikube) with stable startup/health/verify

**Note:** Code implementation complete. Acceptance run pending (see `docs/BACKLOG.md` → "LLM GPU Acceptance Gate").

---

## Overview

LLM stack consists of:
- **llm-server**: Python/FastAPI service running h2oGPT model (GPU-enabled)
- **llm-service**: Spring Boot orchestrator service
- **embedding-service**: Required dependency for vector embeddings

---

## Implementation

### 1. GPU Prerequisites Detection

**File:** `jarvis-launch.sh`

Before deploying LLM stack, script checks:

1. **NVIDIA driver on host**: `nvidia-smi` command available
2. **nvidia-container-toolkit**: Runtime configuration for containerd/docker
3. **Kubernetes device plugin**: `nvidia-device-plugin` daemonset in cluster
4. **GPU allocatable**: `nvidia.com/gpu` resource available in cluster nodes

**Fail-fast behavior:**
- If prerequisites not met → `ENABLE_LLM` set to `false` automatically
- Clear error messages with actionable recommendations
- No deployment of LLM stack (prevents CrashLoopBackOff)

### 2. Kubernetes Manifests

**File:** `k8s/overlays/prod/llm-server.yaml`

- **GPU resources**: `nvidia.com/gpu: 1` in requests/limits
- **Tolerations**: For GPU node taints (`nvidia.com/gpu: Exists`)
- **Node affinity**: Prefer nodes with GPU (`nvidia.com/gpu.present: true`)
- **Probes**: Startup (180s), readiness (10s), liveness (30s)
- **Env flags**: `LLM_DEVICE=gpu`, `ENABLE_GPU=true`

### 3. CPU Fallback Mode

**When:** `ENABLE_GPU=false` or GPU prerequisites check fails

- GPU resource requests/limits removed from deployment
- `DEVICE=cpu`, `LLM_DEVICE=cpu`, `ENABLE_GPU=false` env vars set
- Pod can run without GPU (slower inference)

### 4. Health Check Service

**File:** `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt`

- **Disabled check**: Returns `UNKNOWN (disabled)` with `isDisabled=true`
- **Enabled check**: Performs HTTP health check via API Gateway
- **Error messages**: Clear reasons (GPU missing, device plugin missing, image pull, OOM)

### 5. Verify Script

**File:** `scripts/verify-iteration-1.4.sh`

- **New flag**: `--require-llm`
- **Default mode**: LLM checks are SKIP/WARN (not FAIL)
- **Strict mode** (`--require-llm`):
  - FAIL if `ENABLE_LLM=false` but flag specified
  - Checks: resources deployed, replicas >= 1, pods Running
  - GPU checks: allocatable, device plugin, GPU resource in pod spec
  - Health endpoint check

---

## Files Changed

1. **`jarvis-launch.sh`**
   - GPU prerequisites detection
   - Fail-fast logic
   - CPU fallback configuration
   - GPU verification after deployment

2. **`k8s/overlays/prod/llm-server.yaml`**
   - Tolerations for GPU nodes
   - Explicit env flags (`LLM_DEVICE`, `ENABLE_GPU`)

3. **`apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt`**
   - Real health check for `llm-service` when enabled
   - Proper error messages

4. **`scripts/verify-iteration-1.4.sh`**
   - `--require-llm` flag
   - Stage 14: LLM Stack checks

---

## Prerequisites Installation

### For Minikube

```bash
# Enable GPU addon
minikube addons enable gpu

# Verify GPU is available
kubectl get nodes -o jsonpath='{.items[0].status.allocatable.nvidia\.com/gpu}'
```

### For Host System

```bash
# Install NVIDIA driver (Ubuntu)
sudo apt update
sudo apt install -y nvidia-driver-535  # or latest version

# Install nvidia-container-toolkit
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -s -L https://nvidia.github.io/nvidia-docker/gpgkey | sudo apt-key add -
curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | sudo tee /etc/apt/sources.list.d/nvidia-docker.list
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit
sudo systemctl restart docker  # or containerd
```

### For Kubernetes (non-minikube)

```bash
# Install NVIDIA device plugin
kubectl apply -f https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/v0.14.1/nvidia-device-plugin.yml
```

---

## Usage

### Enable LLM with GPU

```bash
ENABLE_LLM=true ./jarvis-launch.sh
```

### Enable LLM with CPU Fallback

```bash
ENABLE_LLM=true ENABLE_GPU=false ./jarvis-launch.sh
```

### Disable LLM (default)

```bash
./jarvis-launch.sh  # ENABLE_LLM=false by default
```

### Verify LLM Stack

```bash
# Default: SKIP/WARN
./scripts/verify-iteration-1.4.sh --require-install --require-backend

# Strict: FAIL if not enabled/deployed
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-llm
```

---

## Acceptance Criteria

✅ **A) ENABLE_LLM=false**
- LLM stack not deployed
- Launcher shows `UNKNOWN (disabled)` for LLM
- No DEGRADED due to LLM
- Verify passes without `--require-llm`

✅ **B) ENABLE_LLM=true on GPU machine**
- LLM stack deployed and Running
- GPU prerequisites checked
- No CrashLoopBackOff
- Verify `--require-llm` passes

✅ **C) ENABLE_LLM=true on non-GPU machine**
- Script gives clear FAIL-fast message
- No red pods (or CPU fallback if implemented)
- Actionable recommendations provided

---

## Troubleshooting

### GPU Not Available

**Symptoms:**
- `nvidia.com/gpu: 0` in node allocatable
- Pod in `Pending` state (insufficient resources)

**Solutions:**
1. Enable GPU addon: `minikube addons enable gpu`
2. Install NVIDIA driver on host
3. Install nvidia-container-toolkit
4. Use CPU fallback: `ENABLE_GPU=false`

### CrashLoopBackOff

**Symptoms:**
- Pod restarts repeatedly
- Logs show CUDA errors

**Solutions:**
1. Check pod logs: `kubectl logs -n jarvis -l app=llm-server --tail=50`
2. Verify GPU prerequisites
3. Check CUDA version compatibility
4. Use CPU fallback: `ENABLE_GPU=false`

### Device Plugin Missing

**Symptoms:**
- GPU not allocatable in cluster
- Warning in verify script

**Solutions:**
1. Install NVIDIA device plugin daemonset
2. For minikube: `minikube addons enable gpu`
3. Verify: `kubectl get daemonset -A | grep nvidia-device-plugin`

---

## Notes

- LLM stack is **optional** and does not affect core product functionality
- GPU prerequisites check prevents deployment if GPU unavailable (fail-fast)
- CPU fallback mode available but not recommended for production
- Health checks provide clear error messages for troubleshooting

