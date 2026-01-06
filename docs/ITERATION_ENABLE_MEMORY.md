# Iteration: Enable Memory Stack (ENABLE_MEMORY flag)

**Date:** $(date -Is)  
**Status:** IMPLEMENTED  
**Goal:** Make memory stack (postgres-pgvector + memory-service) fully manageable via `ENABLE_MEMORY` flag

---

## Overview

Memory stack consists of:
- **postgres-pgvector**: PostgreSQL database with pgvector extension for vector storage
- **memory-service**: Spring Boot service for RAG (Retrieval-Augmented Generation) and long-term memory
- **embedding-service**: Required dependency (auto-enabled if `ENABLE_MEMORY=true` but `ENABLE_LLM=false`)

---

## Implementation

### 1. Default Behavior (`ENABLE_MEMORY=false`)

- **No deployment**: Memory stack resources are not applied
- **Scaled to 0**: If resources exist, they are scaled to `replicas: 0`
- **Launcher status**: Memory service shows as `UNKNOWN (disabled)` in optional checks
- **No DEGRADED**: Launcher does not go into DEGRADED due to memory (it's optional and disabled)
- **Verify script**: Memory checks are SKIP/WARN, not FAIL

### 2. Enabled Behavior (`ENABLE_MEMORY=true`)

- **Deployment order**:
  1. `embedding-service` (if `ENABLE_LLM=false`, auto-enabled)
  2. `postgres-pgvector` (StatefulSet, scaled to `replicas: 1`)
  3. `memory-service` (Deployment, scaled to `replicas: 1`)
- **Health checks**: 
  - `postgres-pgvector`: `pg_isready` probe
  - `memory-service`: `/memory/health` endpoint (Spring Boot actuator)
- **Launcher status**: 
  - If memory enabled but DOWN → DEGRADED status with reason
  - If memory enabled and UP → READY status (if core services also UP)
- **Port-forward**: Only enabled if `ENABLE_PORT_FORWARD=true` (separate from LLM)

### 3. Health Check Service Updates

**File:** `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt`

- **Disabled check**: Returns `UNKNOWN (disabled)` with `isDisabled=true`
- **Enabled check**: Performs actual HTTP health check via API Gateway (`/actuator/health`)
- **Error handling**: Graceful fallback for SSL/timeout/connection errors

### 4. Launch Script Updates

**File:** `jarvis-launch.sh`

- **Conditional apply**: Memory manifests applied only if `ENABLE_MEMORY=true`
- **Scaling logic**: Explicitly scales resources to 1 (enabled) or 0 (disabled)
- **Rollout order**: Storage first (`postgres-pgvector`), then service (`memory-service`)
- **Port-forward**: Separate block for memory service (not tied to LLM)

### 5. Verify Script Updates

**File:** `scripts/verify-iteration-1.4.sh`

- **New flag**: `--require-memory`
- **Default mode**: Memory checks are SKIP/WARN (not FAIL)
- **Strict mode** (`--require-memory`):
  - FAIL if `ENABLE_MEMORY=false` but flag specified
  - Checks: resources deployed, replicas >= 1, pods Running, no CrashLoopBackOff
  - Health endpoint check (via port-forward or API Gateway)

---

## Files Changed

1. **`jarvis-launch.sh`**
   - Conditional memory stack deployment
   - Scaling logic (0/1 replicas)
   - Separate port-forward block

2. **`apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt`**
   - Real health check for `memory-service` when enabled
   - Proper `isDisabled` flag handling

3. **`scripts/verify-iteration-1.4.sh`**
   - `--require-memory` flag
   - Stage 13: Memory Stack checks

---

## Usage

### Enable Memory Stack

```bash
ENABLE_MEMORY=true ./jarvis-launch.sh
```

### Disable Memory Stack (default)

```bash
./jarvis-launch.sh  # ENABLE_MEMORY=false by default
```

### Verify Memory Stack

```bash
# Default: SKIP/WARN
./scripts/verify-iteration-1.4.sh --require-install --require-backend

# Strict: FAIL if not enabled/deployed
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-memory
```

---

## Dependencies

- **embedding-service**: Required for vector embeddings (auto-enabled if needed)
- **postgres-pgvector**: Required for vector storage
- **jarvis-secrets**: Required for database credentials

---

## Acceptance Criteria

✅ **A) ENABLE_MEMORY=false**
- Memory stack not deployed
- Launcher shows `UNKNOWN (disabled)` for memory
- No DEGRADED due to memory
- Verify passes without `--require-memory`

✅ **B) ENABLE_MEMORY=true**
- Memory stack deployed and Running
- Launcher health check works
- Verify `--require-memory` passes

✅ **C) Verify Default Mode**
- `./scripts/verify-iteration-1.4.sh --require-install --require-backend` → PASS

✅ **D) Verify Strict Mode**
- `./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-memory` → PASS only if `ENABLE_MEMORY=true`

✅ **E) Launcher Status**
- Memory disabled → `UNKNOWN (disabled)`, READY not affected
- Memory enabled + DOWN → DEGRADED + reason
- Memory enabled + UP → READY (if core also UP)

---

## Notes

- Memory stack is **optional** and does not affect core product functionality
- `embedding-service` is auto-enabled if `ENABLE_MEMORY=true` but `ENABLE_LLM=false`
- Port-forward for memory is separate from LLM (controlled by `ENABLE_PORT_FORWARD`)

