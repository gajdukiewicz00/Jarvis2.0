# Core Backend Gaps

Last updated: **2026-03-27**

## Definition Of Done Checklist

| Check | Status | Notes |
| --- | --- | --- |
| All core services build | Done | Verified with Maven test/package runs across the full core set. |
| All core services start in a supported runtime | Done | Verified in local runtime. |
| No broken inter-service contracts on verified core paths | Done | Planner/user-profile, voice/orchestrator, analytics/life-tracker, gateway/analytics query params were corrected. |
| No fake endpoints or dead clients on core paths | Mostly done | Major fake/dead paths removed or gated off. Remaining optional AI endpoints are out of core scope. |
| No misleading core docs | Done | New canonical core doc set added; broader status doc was demoted. |
| Core smoke tests pass | Done | `runtime-smoke.sh` and `analytics-smoke.sh` passed. |
| Health/readiness story is aligned | Done | Core runtime uses readiness endpoints consistently. |
| Config and env flags are coherent | Mostly done | Core runtime ports and planner optional features are now env-driven and explicit. |
| No duplicate source of truth for core architecture | Mostly done | Canonical docs now exist, but broader product docs still remain as overview material. |
| K8s base, local runtime, and CI agree on core composition | Mostly done | `k8s/base`, runtime scripts, and CI are aligned. Broader prod overlay still carries optional dormant workloads. |

## Fixed In This Pass

### Contracts And Integrations

- `planner-service` no longer invents user goals locally; it now calls real `user-profile` goals API.
- dead `planner-service` placeholder `LifeTrackerClient` was removed.
- dead planner `services.life-tracker.url` config was removed.
- `planner-service` recommendations now derive from real analytics/user-goal signals instead of static placeholder text.
- planner scheduled auto-actions no longer hard-code user `denis`; scheduler is explicit and disabled by default.
- planner optional LLM controller is now hidden behind `planner.llm.enabled=false` by default.
- `api-gateway` no longer exposes optional memory tooling as if it were always available; it now returns `503` when memory runtime is disabled.
- dead `api-gateway` `SecurityServiceClient` was removed.
- analytics proxy now forwards `from` and `to` query parameters instead of silently dropping them.
- `voice-gateway /api/v1/voice/command` now returns the real orchestrator reply instead of `Processed: ...`.

### Runtime And Operability

- local runtime ports for core services are now env-driven instead of being locked to resource-file constants only.
- local runtime now fails early with a clear message if a requested service port is already occupied.
- `runtime-smoke.sh` now verifies the real voice-command path through `voice-gateway`.
- `analytics-service` default runtime wiring now points to `life-tracker` consistently for cluster-style service discovery.

### CI And Release Wiring

- `backend-readiness.yml` is now scoped to the core backend instead of optional memory/runtime docs.
- core release wiring no longer treats `memory-service` and `embedding-service` as mandatory release artifacts.
- `scripts/product/jarvis-promote-images.sh` now promotes core images by default.
- optional stacks are explicit release choices via `--include-data` and `--include-llm`.
- core CI now includes a dedicated analytics smoke job.

## Remaining Blockers

### 1. No Dedicated Core-Only Production Overlay

- What remains:
  `k8s/overlays/prod` still includes optional LLM/memory workloads as scaled-to-zero manifests.
- Why it blocks 100%:
  cluster deployment composition is still broader than the core backend scope document.
- Criticality:
  medium to high for release clarity.
- Separate work:
  split a true `prod-core` overlay from optional AI/data overlays, then verify rollout tooling against that overlay.

### 2. Production Cluster Runtime Was Not Re-Verified In This Pass

- What remains:
  this pass verified local core runtime, CI wiring, and smoke paths, but not a fresh cluster rollout.
- Why it blocks 100%:
  local release-ready is not the same claim as cluster production-ready.
- Criticality:
  high for a full production claim.
- Separate work:
  execute and validate a core-only cluster rollout path end to end.

### 3. Voice Media Path Still Depends On Local Models/Providers

- What remains:
  text-command and notification paths are real and verified, but full STT/TTS behavior still depends on local model assets and provider/runtime specifics.
- Why it blocks 100%:
  the backend contract is real, but media-quality readiness is not guaranteed by the current automated core verification.
- Criticality:
  medium.
- Separate work:
  add deterministic media fixtures and a stable model/provider verification matrix.

### 4. Real Smart-Home Provider Coverage Is Still Provider-Specific

- What remains:
  local core runtime verifies the mock smart-home provider; real provider behavior is not covered by the core smoke path.
- Why it blocks 100%:
  smart-home backend APIs are ready, but hardware/provider correctness is not universally proven.
- Criticality:
  medium.
- Separate work:
  define supported providers and add provider-specific contract/integration suites.
