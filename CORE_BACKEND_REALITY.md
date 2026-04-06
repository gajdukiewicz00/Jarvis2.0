# Core Backend Reality

Last updated: **2026-03-27**

This file is the canonical scope document for the Jarvis core backend.

## Scope

Core backend includes:

- `api-gateway`
- `security-service`
- `user-profile`
- `nlp-service`
- `orchestrator`
- `voice-gateway`
- `pc-control`
- `smart-home-service`
- `life-tracker`
- `analytics-service`
- `planner-service`
- shared runtime/config/auth wiring in `apps/jarvis-common`, `scripts/runtime/*`, `.github/workflows/backend-readiness.yml`, and `k8s/base`

Explicitly not part of core backend readiness:

- `llm-service`
- `llm-server`
- `memory-service`
- `embedding-service`
- Android/mobile
- computer vision monitoring
- wake word
- bank sync, smartwatch, weather station, roadmap items

## Status Labels

- `Working`: part of the verified core runtime path.
- `Partial`: present and usable, but not a complete or release-claimable path on its own.
- `Optional`: real code, but intentionally excluded from core readiness.
- `Legacy`: not part of the current supported core runtime story.

## Service Reality

| Service | Status | Reality |
| --- | --- | --- |
| `api-gateway` | Working | Single public HTTP/WebSocket entry point for core backend. Memory tooling is now explicitly gated off when memory runtime is disabled. |
| `security-service` | Working | Real auth source for register/login/token/me flows. |
| `user-profile` | Working | Real source of goals/preferences/habits for planner personalization. |
| `nlp-service` | Working | Deterministic intent classification for orchestrator. |
| `orchestrator` | Working | Real command router for PC and smart-home actions. |
| `voice-gateway` | Working | Real text-command proxy to orchestrator and real voice notification/WebSocket path. Full STT/TTS quality still depends on local model/provider assets. |
| `pc-control` | Working | Real action/scenario backend plus gateway WebSocket relay path. |
| `smart-home-service` | Working | Core backend path is real; local runtime defaults to mock provider. |
| `life-tracker` | Working | System of record for calendar, finance, and time tracking. |
| `analytics-service` | Working | Real derived analytics over life-tracker data. |
| `planner-service` | Working | Deterministic planner/reminder/recommendation service. Optional LLM endpoints are disabled by default and are not part of core readiness. |

## Verified Runtime Modes

Verified in this pass:

- local core runtime with `ENABLE_LLM=false` and `ENABLE_MEMORY=false`
- local core runtime smoke via `./scripts/runtime-smoke.sh`
- analytics integration smoke via `./scripts/analytics-smoke.sh`
- local core runtime with alternate public gateway port via `JARVIS_API_GATEWAY_PORT=18080`

Maintained but not re-verified in this pass:

- `k8s/base` core manifests
- broader production overlays under `k8s/overlays/prod*`

## Source Of Truth

Use these files in this order:

1. `CORE_BACKEND_REALITY.md`
2. `CORE_BACKEND_ARCHITECTURE.md`
3. `CORE_BACKEND_OPERATIONS.md`
4. `CORE_BACKEND_GAPS.md`

Supporting implementation truth:

- `scripts/runtime/common.sh` defines the local core service set and local runtime wiring.
- `k8s/base/kustomization.yaml` is the core Kubernetes base composition.
- `.github/workflows/backend-readiness.yml` is the core CI gate.

Important boundary:

- `k8s/overlays/prod` is broader than core backend scope. It still carries optional workloads scaled to zero and should not be used as the source of truth for what is mandatory in core backend.
