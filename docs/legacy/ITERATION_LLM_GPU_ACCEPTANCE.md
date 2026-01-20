# Iteration: LLM GPU Support — Acceptance Run

**Date:** $(date -Is)  
**Stage:** perf/hardware (LLM GPU)  
**Goal:** Verify LLM stack works reliably with GPU and fails gracefully without GPU

---

## A) Default Behavior (ENABLE_LLM=false)

### Test 1: LLM Stack Not Deployed

**Command:**
```bash
ENABLE_LLM=false ./jarvis-launch.sh
```

**Expected:**
- No `llm-server` or `llm-service` pods created
- Launcher shows `UNKNOWN (disabled)` for llm-service

**Check:**
```bash
kubectl get pods -n jarvis | grep -E "llm-server|llm-service"
```

**Result:** ✅ PASS / ❌ FAIL

---

### Test 2: Launcher Status (LLM Disabled)

**Steps:**
1. Launch launcher: `$HOME/.jarvis/app/bin/jarvis-launcher.sh`
2. Check status: Should show `READY` (not DEGRADED)
3. Check optional services: `llm-service` should show `UNKNOWN (disabled)`

**Expected:**
- Status: `READY` (core services UP)
- LLM: `UNKNOWN (disabled)` with `isDisabled=true`
- No DEGRADED due to LLM

**Result:** ✅ PASS / ❌ FAIL

---

### Test 3: Verify Script (Default Mode)

**Command:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend
```

**Expected:**
- Exit code: 0
- Stage 14: LLM checks are SKIP/WARN (not FAIL)
- No errors related to LLM stack

**Result:** ✅ PASS / ❌ FAIL

---

## B) Enabled Behavior (ENABLE_LLM=true on GPU machine)

### Test 4: GPU Prerequisites Check

**Command:**
```bash
ENABLE_LLM=true ./jarvis-launch.sh
```

**Expected:**
- GPU prerequisites checked (nvidia-smi, container toolkit, device plugin, allocatable)
- Clear output showing each check result
- If prerequisites OK → LLM stack deployed

**Check:**
```bash
# Verify GPU prerequisites
nvidia-smi
kubectl get nodes -o jsonpath='{.items[0].status.allocatable.nvidia\.com/gpu}'
kubectl get daemonset -A | grep nvidia-device-plugin
```

**Result:** ✅ PASS / ❌ FAIL

---

### Test 5: LLM Stack Deployed

**Expected:**
- `llm-server` Deployment scaled to `replicas: 1`
- `llm-service` Deployment scaled to `replicas: 1`
- `embedding-service` deployed
- All pods reach `Running` state

**Check:**
```bash
kubectl get pods -n jarvis | grep -E "llm-server|llm-service|embedding-service"
kubectl get deployment llm-server -n jarvis -o jsonpath='{.spec.replicas}'  # Should be 1
kubectl get deployment llm-service -n jarvis -o jsonpath='{.spec.replicas}'  # Should be 1
```

**Result:** ✅ PASS / ❌ FAIL

---

### Test 6: Pods Running/Ready (No CrashLoopBackOff)

**Command:**
```bash
kubectl get pods -n jarvis -l app=llm-server
kubectl get pods -n jarvis -l app=llm-service
```

**Expected:**
- `llm-server` pod: `Running` / `1/1 Ready`
- `llm-service` pod: `Running` / `1/1 Ready`
- No `CrashLoopBackOff` or `ImagePullBackOff`

**Result:** ✅ PASS / ❌ FAIL

---

### Test 7: GPU Resource Allocation

**Command:**
```bash
kubectl get deployment llm-server -n jarvis -o jsonpath='{.spec.template.spec.containers[0].resources.requests.nvidia\.com/gpu}'
kubectl describe pod -n jarvis -l app=llm-server | grep -A 5 "Limits\|Requests"
```

**Expected:**
- GPU resource requested: `nvidia.com/gpu: 1`
- GPU resource limit: `nvidia.com/gpu: 1`

**Result:** ✅ PASS / ❌ FAIL

---

### Test 8: CUDA Availability in Pod

**Command:**
```bash
LLM_POD=$(kubectl get pods -n jarvis -l app=llm-server -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n jarvis "$LLM_POD" -- python3 -c "import torch; print('CUDA available:', torch.cuda.is_available()); print('GPU name:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'N/A')"
```

**Expected:**
- `CUDA available: True`
- GPU name displayed (e.g., "NVIDIA GeForce RTX 5070")

**Result:** ✅ PASS / ❌ FAIL

---

### Test 9: Health Endpoints

**Commands:**
```bash
# LLM Server
curl -s http://localhost:5000/health || kubectl port-forward -n jarvis svc/llm-server 5000:5000 &
sleep 2
curl -s http://localhost:5000/health

# LLM Service (via API Gateway)
curl -s https://api.jarvis.local/actuator/health | grep -i llm || curl -s http://localhost:8091/api/v1/llm/health
```

**Expected:**
- LLM Server: HTTP 200 with health JSON
- LLM Service: HTTP 200 or health JSON with LLM components

**Result:** ✅ PASS / ❌ FAIL

---

### Test 10: Launcher Status (LLM Enabled)

**Steps:**
1. Launch launcher: `$HOME/.jarvis/app/bin/jarvis-launcher.sh`
2. Check status:
   - If LLM UP → `READY` (if core also UP)
   - If LLM DOWN → `DEGRADED` with reason
3. Check optional services: `llm-service` should show actual status

**Expected:**
- Status reflects LLM health correctly
- DEGRADED if LLM enabled but DOWN
- READY if LLM enabled and UP

**Result:** ✅ PASS / ❌ FAIL

---

## C) Fail-Fast Behavior (ENABLE_LLM=true on non-GPU machine)

### Test 11: GPU Prerequisites Check Fails

**Command:**
```bash
ENABLE_LLM=true ./jarvis-launch.sh
```

**Expected:**
- GPU prerequisites check fails
- Clear error messages with recommendations
- `ENABLE_LLM` set to `false` automatically
- No LLM stack deployed
- No CrashLoopBackOff pods

**Check:**
```bash
kubectl get pods -n jarvis | grep -E "llm-server|llm-service"  # Should be empty or scaled to 0
```

**Result:** ✅ PASS / ❌ FAIL

---

### Test 12: CPU Fallback Mode

**Command:**
```bash
ENABLE_LLM=true ENABLE_GPU=false ./jarvis-launch.sh
```

**Expected:**
- LLM stack deployed without GPU resources
- `DEVICE=cpu`, `LLM_DEVICE=cpu`, `ENABLE_GPU=false` env vars set
- Pods Running (CPU mode, slower)

**Check:**
```bash
kubectl get deployment llm-server -n jarvis -o jsonpath='{.spec.template.spec.containers[0].resources.requests.nvidia\.com/gpu}'  # Should be empty
kubectl get deployment llm-server -n jarvis -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DEVICE")].value}'  # Should be "cpu"
```

**Result:** ✅ PASS / ❌ FAIL

---

## D) Verify Script (Strict Mode)

### Test 13: Verify with --require-llm (ENABLE_LLM=false)

**Command:**
```bash
ENABLE_LLM=false ./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-llm
```

**Expected:**
- Exit code: 1 (FAIL)
- Error: "ENABLE_LLM=false but --require-llm specified"

**Result:** ✅ PASS / ❌ FAIL

---

### Test 14: Verify with --require-llm (ENABLE_LLM=true)

**Command:**
```bash
ENABLE_LLM=true ./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-llm
```

**Expected:**
- Exit code: 0 (PASS)
- Stage 14: All LLM checks pass
- Resources deployed, replicas >= 1, pods Running
- GPU checks pass (if GPU available)

**Result:** ✅ PASS / ❌ FAIL

---

## Summary

| Test | Status | Notes |
|------|--------|-------|
| A1: Default (not deployed) | ⬜ | LLM stack not deployed |
| A2: Launcher (disabled) | ⬜ | UNKNOWN (disabled), READY |
| A3: Verify (default) | ⬜ | SKIP/WARN, no FAIL |
| B4: GPU prerequisites | ⬜ | All checks pass |
| B5: LLM deployed | ⬜ | Resources scaled to 1 |
| B6: Pods Running | ⬜ | No CrashLoopBackOff |
| B7: GPU allocation | ⬜ | nvidia.com/gpu: 1 |
| B8: CUDA in pod | ⬜ | torch.cuda.is_available() = True |
| B9: Health endpoints | ⬜ | LLM Server + Service UP |
| B10: Launcher (enabled) | ⬜ | Status reflects health |
| C11: Fail-fast (no GPU) | ⬜ | Clear error, no deployment |
| C12: CPU fallback | ⬜ | LLM runs without GPU |
| C13: Verify strict (disabled) | ⬜ | FAIL if --require-llm |
| C14: Verify strict (enabled) | ⬜ | PASS if --require-llm |

---

## Verdict

**✅ ACCEPTED** / **❌ FAIL** (with reasons)

---

## Files Changed

1. `jarvis-launch.sh` — GPU prerequisites detection, fail-fast, CPU fallback
2. `k8s/overlays/prod/llm-server.yaml` — Tolerations, env flags
3. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt` — Real health checks
4. `scripts/verify-iteration-1.4.sh` — `--require-llm` flag, Stage 14

---

## Prerequisites Installation Commands

### Minikube GPU Support

```bash
minikube addons enable gpu
minikube addons list | grep gpu  # Should show "enabled"
```

### Host NVIDIA Driver (Ubuntu)

```bash
sudo apt update
sudo apt install -y nvidia-driver-535
sudo reboot  # Required after driver install
nvidia-smi  # Verify after reboot
```

### NVIDIA Container Toolkit

```bash
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -s -L https://nvidia.github.io/nvidia-docker/gpgkey | sudo apt-key add -
curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | sudo tee /etc/apt/sources.list.d/nvidia-docker.list
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit
sudo systemctl restart docker  # or containerd
```

---

## Next Steps

- [ ] Run acceptance tests
- [ ] Fix any issues found
- [ ] Update documentation if needed


