# Iteration: Enable Memory Stack — Acceptance Run

**Date:** $(date -Is)  
**Stage:** Enable Memory  
**Goal:** Verify memory stack is fully manageable via `ENABLE_MEMORY` flag

---

## A) Default Behavior (ENABLE_MEMORY=false)

### Test 1: Memory Stack Not Deployed

**Command:**
```bash
ENABLE_MEMORY=false ./jarvis-launch.sh
```

**Expected:**
- No `postgres-pgvector` or `memory-service` pods created
- Resources scaled to `replicas: 0` if they exist
- Launcher shows `UNKNOWN (disabled)` for memory-service

**Check:**
```bash
kubectl get pods -n jarvis | grep -E "postgres-pgvector|memory-service"
kubectl get statefulset postgres-pgvector -n jarvis -o jsonpath='{.spec.replicas}'  # Should be 0
kubectl get deployment memory-service -n jarvis -o jsonpath='{.spec.replicas}'  # Should be 0
```

**Result:** ✅ PASS / ❌ FAIL

---

### Test 2: Launcher Status (Memory Disabled)

**Steps:**
1. Launch launcher: `$HOME/.jarvis/app/bin/jarvis-launcher.sh`
2. Check status: Should show `READY` (not DEGRADED)
3. Check optional services: `memory-service` should show `UNKNOWN (disabled)`

**Expected:**
- Status: `READY` (core services UP)
- Memory: `UNKNOWN (disabled)` with `isDisabled=true`
- No DEGRADED due to memory

**Result:** ✅ PASS / ❌ FAIL

---

### Test 3: Verify Script (Default Mode)

**Command:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend
```

**Expected:**
- Exit code: 0
- Stage 13: Memory checks are SKIP/WARN (not FAIL)
- No errors related to memory stack

**Result:** ✅ PASS / ❌ FAIL

---

## B) Enabled Behavior (ENABLE_MEMORY=true)

### Test 4: Memory Stack Deployed

**Command:**
```bash
ENABLE_MEMORY=true ./jarvis-launch.sh
```

**Expected:**
- `postgres-pgvector` StatefulSet scaled to `replicas: 1`
- `memory-service` Deployment scaled to `replicas: 1`
- `embedding-service` deployed (if `ENABLE_LLM=false`, auto-enabled)
- All pods reach `Running` state

**Check:**
```bash
kubectl get pods -n jarvis | grep -E "postgres-pgvector|memory-service|embedding-service"
kubectl get statefulset postgres-pgvector -n jarvis -o jsonpath='{.spec.replicas}'  # Should be 1
kubectl get deployment memory-service -n jarvis -o jsonpath='{.spec.replicas}'  # Should be 1
```

**Result:** ✅ PASS / ❌ FAIL

---

### Test 5: Pods Running/Ready

**Command:**
```bash
kubectl get pods -n jarvis -l app=postgres-pgvector
kubectl get pods -n jarvis -l app=memory-service
```

**Expected:**
- `postgres-pgvector` pod: `Running` / `1/1 Ready`
- `memory-service` pod: `Running` / `1/1 Ready`
- No `CrashLoopBackOff` or `ImagePullBackOff`

**Result:** ✅ PASS / ❌ FAIL

---

### Test 6: Health Endpoints

**Commands:**
```bash
# PostgreSQL
kubectl exec -n jarvis deploy/postgres-pgvector -- pg_isready -U jarvis

# Memory Service (via port-forward or API Gateway)
curl -s http://localhost:8093/memory/health || curl -s https://api.jarvis.local/actuator/health | grep memory-service
```

**Expected:**
- PostgreSQL: `accepting connections`
- Memory service: HTTP 200 or health JSON with `UP`

**Result:** ✅ PASS / ❌ FAIL

---

### Test 7: Launcher Status (Memory Enabled)

**Steps:**
1. Launch launcher: `$HOME/.jarvis/app/bin/jarvis-launcher.sh`
2. Check status:
   - If memory UP → `READY` (if core also UP)
   - If memory DOWN → `DEGRADED` with reason
3. Check optional services: `memory-service` should show actual status

**Expected:**
- Status reflects memory health correctly
- DEGRADED if memory enabled but DOWN
- READY if memory enabled and UP

**Result:** ✅ PASS / ❌ FAIL

---

## C) Verify Script (Strict Mode)

### Test 8: Verify with --require-memory (ENABLE_MEMORY=false)

**Command:**
```bash
ENABLE_MEMORY=false ./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-memory
```

**Expected:**
- Exit code: 1 (FAIL)
- Error: "ENABLE_MEMORY=false but --require-memory specified"

**Result:** ✅ PASS / ❌ FAIL

---

### Test 9: Verify with --require-memory (ENABLE_MEMORY=true)

**Command:**
```bash
ENABLE_MEMORY=true ./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-memory
```

**Expected:**
- Exit code: 0 (PASS)
- Stage 13: All memory checks pass
- Resources deployed, replicas >= 1, pods Running

**Result:** ✅ PASS / ❌ FAIL

---

## D) Integration Tests

### Test 10: Disable After Enable

**Commands:**
```bash
# Enable
ENABLE_MEMORY=true ./jarvis-launch.sh
# Wait for pods
sleep 30
# Disable
ENABLE_MEMORY=false ./jarvis-launch.sh
# Check
kubectl get pods -n jarvis | grep -E "postgres-pgvector|memory-service"
```

**Expected:**
- Pods scaled to 0
- Resources still exist but not running

**Result:** ✅ PASS / ❌ FAIL

---

### Test 11: Port-Forward (Memory Only)

**Command:**
```bash
ENABLE_MEMORY=true ENABLE_PORT_FORWARD=true ENABLE_LLM=false ./jarvis-launch.sh
```

**Expected:**
- Memory service port-forward active (8093)
- LLM services NOT port-forwarded (LLM disabled)

**Check:**
```bash
lsof -i :8093 | grep kubectl
lsof -i :5000 | grep kubectl  # Should be empty (LLM disabled)
```

**Result:** ✅ PASS / ❌ FAIL

---

## Summary

| Test | Status | Notes |
|------|--------|-------|
| A1: Default (not deployed) | ⬜ | Memory stack not deployed |
| A2: Launcher (disabled) | ⬜ | UNKNOWN (disabled), READY |
| A3: Verify (default) | ⬜ | SKIP/WARN, no FAIL |
| B4: Enabled (deployed) | ⬜ | Resources scaled to 1 |
| B5: Pods Running | ⬜ | No CrashLoopBackOff |
| B6: Health endpoints | ⬜ | PostgreSQL + Memory UP |
| B7: Launcher (enabled) | ⬜ | Status reflects health |
| C8: Verify strict (disabled) | ⬜ | FAIL if --require-memory |
| C9: Verify strict (enabled) | ⬜ | PASS if --require-memory |
| D10: Disable after enable | ⬜ | Pods scaled to 0 |
| D11: Port-forward (memory only) | ⬜ | Memory PF, LLM not PF |

---

## Verdict

**✅ ACCEPTED** / **❌ FAIL** (with reasons)

---

## Files Changed

1. `jarvis-launch.sh` — Conditional deployment, scaling logic
2. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt` — Real health checks
3. `scripts/verify-iteration-1.4.sh` — `--require-memory` flag, Stage 13

---

## Next Steps

- [ ] Run acceptance tests
- [ ] Fix any issues found
- [ ] Update documentation if needed


