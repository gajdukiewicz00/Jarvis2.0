# Jarvis 2.0 — Backlog

**Last Updated:** $(date -Is)

---

## 0. LLM GPU Acceptance Gate (SHORT — 10 min)

**Status:** ⚠️ PENDING (code complete, acceptance run needed)  
**Priority:** HIGH (gate before marking LLM GPU iteration as ACCEPTED)

### Quick Acceptance Run

**Baseline (A):**
```bash
./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
kubectl get pods -n jarvis | egrep "llm|embedding" || echo "No LLM pods"
```

**Determine GPU (B or C):**
```bash
kubectl get nodes -o jsonpath='{.items[*].status.allocatable.nvidia\.com/gpu}'; echo
```

**Path B (if GPU available):**
```bash
ENABLE_LLM=true ./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https --require-llm
echo $?
kubectl get pods -n jarvis | egrep "llm|embedding" || true
```

**Path C (if GPU unavailable):**
```bash
ENABLE_LLM=true ./jarvis-launch.sh
kubectl get pods -n jarvis | egrep "llm|embedding" || echo "No LLM pods (fail-fast worked)"
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
```

**Acceptance Criteria:**
- ✅ A (baseline) passes: verify green, no LLM pods
- ✅ B OR C passes (depending on GPU availability)
- ✅ No red pods (CrashLoopBackOff) in any scenario
- ✅ If C: fail-fast disables LLM, verify green without `--require-llm`

**Estimated Time:** 10 minutes

---

## 1. perf/hardware: LLM GPU (CUDA error)

**Goal:** Enable LLM server to run with GPU support, eliminate CUDA errors.

### Current State
- ✅ **IMPLEMENTED** — GPU prerequisites detection, fail-fast, CPU fallback
- ✅ **IMPLEMENTED** — Kubernetes manifests with GPU resources
- ✅ **IMPLEMENTED** — HealthCheckService with LLM health checks
- ✅ **IMPLEMENTED** — Verify script with `--require-llm` flag
- ⚠️ **PENDING** — Acceptance run (see "LLM GPU Acceptance Gate" above)

### Definition of Done

**A) GPU Detection & Initialization**
- [x] `llm-server` detects GPU availability via `nvidia-smi` or PyTorch CUDA check
- [x] If GPU unavailable → graceful fallback (disable LLM or show clear status)
- [x] No CUDA errors in logs when GPU is unavailable (fail-fast prevents deployment)

**B) Docker/Kubernetes Integration**
- [x] Dockerfile for `llm-server` includes CUDA support (nvidia/cuda base or CUDA runtime)
- [x] Kubernetes deployment uses `nvidia.com/gpu` resource requests (if available)
- [x] `jarvis-launch.sh` detects GPU prerequisites and fails-fast if unavailable

**C) Verification**
- [x] `scripts/verify-iteration-1.4.sh --require-llm` flag:
  - Checks `nvidia-smi` availability
  - Verifies `llm-server` pod uses GPU (if available)
  - Confirms no CUDA errors in logs
- [ ] Manual test: `nvidia-smi` shows GPU usage by `llm-server` container (pending acceptance run)

**D) Documentation**
- [x] `docs/ITERATION_LLM_GPU.md` — Implementation documentation
- [x] `docs/ITERATION_LLM_GPU_ACCEPTANCE.md` — Acceptance guide
- [x] `docs/ITERATION_LLM_GPU_ACCEPTANCE_RUN.md` — Acceptance run template

**Risks:**
- CUDA version mismatch between host and container
- NVIDIA driver compatibility
- GPU memory limits

**Estimated Effort:** ✅ COMPLETE (pending acceptance run)

**Status:** ✅ CODE COMPLETE, ⚠️ ACCEPTANCE PENDING

---

## 2. enable-memory: postgres-pgvector + memory-service

**Goal:** Enable memory stack (postgres-pgvector + memory-service) as optional feature flag.

### Current State
- `postgres-pgvector` disabled by default (`replicas: 0`)
- `memory-service` disabled by default (`replicas: 0`)
- No feature flag to enable memory stack

### Definition of Done

**A) Feature Flag**
- [ ] `jarvis-launch.sh` supports `ENABLE_MEMORY=true` flag
- [ ] When enabled:
  - `postgres-pgvector` statefulset scaled to `replicas: 1`
  - `memory-service` deployment scaled to `replicas: 1`
- [ ] When disabled: both services remain at `replicas: 0` (current behavior)

**B) Database Migrations**
- [ ] `postgres-pgvector` initializes `pgvector` extension on first start
- [ ] Migration scripts for memory schema (if needed)
- [ ] Data persistence in `~/.jarvis/data/postgres-pgvector/`

**C) Service Integration**
- [ ] `memory-service` connects to `postgres-pgvector` (no hardcoded URLs)
- [ ] Graceful startup: `memory-service` waits for `postgres-pgvector` readiness
- [ ] Health checks: `memory-service` reports DOWN if DB unavailable (not CrashLoopBackOff)

**D) Verification**
- [ ] `scripts/verify-iteration-1.4.sh --require-memory` flag:
  - Checks `postgres-pgvector` pod is running (if `ENABLE_MEMORY=true`)
  - Checks `memory-service` pod is running and healthy
  - Verifies `pgvector` extension in database
- [ ] Manual test: `ENABLE_MEMORY=true ./jarvis-launch.sh` → both services start

**E) Documentation**
- [ ] `docs/MEMORY_STACK.md` — Memory stack architecture, enable/disable, data location
- [ ] Update `docs/TROUBLESHOOTING.md` with memory-related issues

**Risks:**
- `postgres-pgvector` image build/pull issues
- `pgvector` extension compatibility
- Memory service dependency on embedding-service (if required)

**Estimated Effort:** Small-Medium (1-2 iterations)

---

## 3. product-polish: UI/иконки/косметика (Stage 15)

**Goal:** Improve visual appearance and user experience without changing core logic.

### Current State
- ✅ **IMPLEMENTED** — Icons structure (`assets/icons/`)
- ✅ **IMPLEMENTED** — Desktop entry polish (Categories, Keywords, Actions, Icon path)
- ✅ **IMPLEMENTED** — Launcher UI polish (status badge, buttons, tooltips, human-readable errors)
- ✅ **IMPLEMENTED** — Verify script Stage 15 checks
- ⚠️ **PENDING** — Acceptance run (5 minutes)

### Definition of Done

**A) Icons & Branding**
- [x] Custom Jarvis icon (`.png`) in `assets/icons/jarvis.png`
- [x] Desktop entry references custom icon (`$HOME/.jarvis/app/assets/icons/jarvis.png`)
- [x] Icon included in release archive
- [x] Icons copied to `~/.jarvis/app/assets/icons/` during install

**B) UI Polish**
- [x] Launcher UI:
  - Status badge with colors (IDLE/STARTING/READY/DEGRADED/ERROR)
  - "Open Logs Folder" button
  - "Copy Diagnostics" button (masked)
  - Tooltips for all buttons
  - Human-readable error messages (no stacktraces)
- [ ] Desktop client:
  - Consistent styling with launcher
  - Better error messages (user-friendly, not technical)

**C) Text & Messages**
- [ ] All user-facing text reviewed for clarity
- [ ] Error messages are action-oriented (e.g., "Click 'Start Backend' to begin" instead of "Backend not running")
- [ ] Tooltips/help text for complex features

**D) Documentation**
- [ ] `docs/UI_POLISH.md` — Design guidelines, icon usage, color scheme
- [ ] Screenshots of polished UI in `docs/screenshots/`

**Risks:**
- Subjective preferences (may need user feedback)
- Icon licensing (if using external assets)

**Estimated Effort:** ✅ COMPLETE (pending acceptance run)

**Status:** ✅ CODE COMPLETE, ⚠️ ACCEPTANCE PENDING

**Acceptance Run:**
```bash
./scripts/product/jarvis-install.sh
./scripts/verify-iteration-1.4.sh --require-install
~/.jarvis/app/bin/jarvis-launcher.sh  # Visual check
```

**See:** `docs/ITERATION_PRODUCT_POLISH_ACCEPTANCE.md` for acceptance template.

---

## Priority Order

1. **LLM GPU Acceptance Gate** (10 min, gate for perf/hardware)
2. **enable-memory** (if memory features are needed soon)
3. **perf/hardware** (if GPU support is critical) — ✅ CODE COMPLETE, ⚠️ ACCEPTANCE PENDING
4. **product-polish** (ongoing, can be done incrementally)

---

## Notes

- All backlog items follow the same workflow: plan → implement → verify → document
- Each item should have clear DoD before starting
- No breaking changes to existing functionality

