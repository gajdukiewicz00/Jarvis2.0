# Iteration: LLM GPU Support — Acceptance Run Results

**Date:** $(date -Is)  
**Stage:** perf/hardware (LLM GPU)  
**Goal:** Verify LLM stack works reliably with GPU and fails gracefully without GPU

---

## Acceptance Scenarios

### A) База не ломается (ENABLE_LLM=false) — ОБЯЗАТЕЛЬНО

**Commands:**
```bash
./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
```

**Expected:**
- Exit code: 0
- LLM stack not deployed
- No errors related to LLM
- Verify summary shows "Passed: X" with no FAIL

**Actual Results:**

**Verify Summary (last lines):**
```
[TO BE FILLED AFTER RUN]
```

**Exit Code:**
```
[TO BE FILLED AFTER RUN]
```

**LLM Pods Check:**
```bash
kubectl get pods -n jarvis | grep -E "llm|embedding" || true
```
```
[TO BE FILLED AFTER RUN]
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Notes:**
- Default `ENABLE_LLM=false` ensures LLM stack is not deployed
- Verify script should pass without `--require-llm` flag

---

### B) GPU путь (если GPU реально доступен)

**Step 1: Determine path (B or C)**
```bash
kubectl get nodes -o jsonpath='{.items[*].status.allocatable.nvidia\.com/gpu}'; echo
```

**Actual GPU Allocatable:**
```
[TO BE FILLED AFTER RUN]
```

**Decision:** If output is empty/0 → Path C. If 1 (or more) → Path B.

**Step 2: If Path B (GPU available)**

**Commands:**
```bash
ENABLE_LLM=true ./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https --require-llm
echo $?
kubectl get pods -n jarvis | egrep "llm|embedding" || true
```

**Expected:**
- GPU prerequisites check passes
- LLM stack deployed (`llm-server`, `llm-service`, `embedding-service`)
- Pods reach `Running` state
- No `CrashLoopBackOff` or `ImagePullBackOff`
- GPU resource allocated: `nvidia.com/gpu: 1`
- Verify script passes with `--require-llm` (exit code 0)

**Actual Results:**

**Verify Summary (last lines):**
```
[TO BE FILLED AFTER RUN]
```

**Exit Code:**
```
[TO BE FILLED AFTER RUN]
```

**LLM Pods:**
```
[TO BE FILLED AFTER RUN]
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Notes:**
- GPU prerequisites detection should show all checks passing
- LLM stack should deploy successfully
- Verify script should pass with `--require-llm`

---

### C) Fail-fast путь (если GPU нет/не настроен)

**Commands:**
```bash
ENABLE_LLM=true ./jarvis-launch.sh
kubectl get pods -n jarvis | egrep "llm|embedding" || true
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
```

**Expected:**
- GPU prerequisites check fails (at least one check fails)
- Clear error messages with recommendations
- `ENABLE_LLM` set to `false` automatically
- LLM stack NOT deployed
- No `CrashLoopBackOff` pods
- No red pods in cluster
- Verify script passes WITHOUT `--require-llm` (exit code 0)

**Actual Results:**

**Launch Output (GPU prerequisites check):**
```
[TO BE FILLED AFTER RUN]
```

**LLM Pods (should be empty):**
```
[TO BE FILLED AFTER RUN]
```

**Verify Summary (last lines):**
```
[TO BE FILLED AFTER RUN]
```

**Exit Code:**
```
[TO BE FILLED AFTER RUN]
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Notes:**
- Fail-fast prevents deployment if GPU unavailable
- Clear error messages guide user to fix prerequisites
- No red pods left in cluster
- If no llm/embedding pods AND verify is green → ACCEPTED (fail-fast works)

---

## Summary

| Scenario | Status | Notes |
|----------|--------|-------|
| A) База не ломается | ⬜ | Default ENABLE_LLM=false |
| B) GPU путь | ⬜ | Requires GPU-enabled cluster |
| C) Fail-fast путь | ⬜ | Requires non-GPU cluster or disabled GPU |

---

## Verdict

**⬜ PENDING** — Acceptance run required

**To complete acceptance:**
1. Run scenario A on clean cluster
2. Run scenario B on GPU-enabled cluster (or skip if GPU unavailable)
3. Run scenario C on non-GPU cluster (or disable GPU addon)
4. Update status: ✅ ACCEPTED / ❌ FAIL (with reasons)

---

## Files Changed

1. `jarvis-launch.sh` — GPU prerequisites detection, fail-fast, CPU fallback
2. `k8s/overlays/local/llm-server.yaml` — Tolerations, env flags
3. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt` — LLM health check
4. `scripts/verify-iteration-1.4.sh` — `--require-llm` flag, Stage 14
5. `docs/ITERATION_LLM_GPU.md` — Implementation documentation
6. `docs/ITERATION_LLM_GPU_ACCEPTANCE.md` — Acceptance guide
7. `docs/ITERATION_LLM_GPU_ACCEPTANCE_RUN.md` — This file (acceptance run results)

---

## Known Issues / Notes

1. **Minikube GPU**: May require proper containerd + nvidia runtime configuration. `minikube addons enable gpu` may not be sufficient if host/container runtime is not ready.

2. **NVIDIA Container Toolkit**: Installation docs mention `apt-key` which is deprecated on newer Ubuntu. If repository errors occur, use current NVIDIA installation guide (this is installation step, not part of iteration logic).

3. **GPU Prerequisites**: All 4 checks must pass for LLM deployment. If any fails, fail-fast prevents deployment.

---

## Next Steps

- [ ] Run acceptance scenario A
- [ ] Run acceptance scenario B (if GPU available)
- [ ] Run acceptance scenario C (if GPU unavailable)
- [ ] Update verdict: ✅ ACCEPTED / ❌ FAIL
- [ ] If ACCEPTED → proceed to next iteration from backlog

