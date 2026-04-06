# Core Backend Operations

Last updated: **2026-03-27**

This file describes the supported operational path for the Jarvis core backend.

## Preconditions

- Java 21+
- Maven 3.8+
- Docker
- optional local TLS material only if `JARVIS_USE_TLS=true`

## Supported Local Runtime

Start the core backend:

```bash
ENABLE_LLM=false ENABLE_MEMORY=false ./scripts/runtime-up.sh
```

Stop it:

```bash
./scripts/runtime-down.sh
```

Inspect health:

```bash
./scripts/runtime-status.sh
```

## Core Smoke

End-to-end core smoke without optional AI:

```bash
JARVIS_RUNTIME_SMOKE_SKIP_LLM=true ./scripts/runtime-smoke.sh
```

Analytics smoke on top of the same runtime:

```bash
./scripts/analytics-smoke.sh
```

## Supported Port Overrides

Core runtime ports are now env-driven instead of being hard-coded only in resource files.

Common example:

```bash
JARVIS_API_GATEWAY_PORT=18080 ENABLE_LLM=false ENABLE_MEMORY=false ./scripts/runtime-up.sh
```

Important overrides:

- `JARVIS_API_GATEWAY_PORT`
- `JARVIS_VOICE_GATEWAY_PORT`
- `JARVIS_NLP_SERVICE_PORT`
- `JARVIS_ORCHESTRATOR_PORT`
- `JARVIS_PC_CONTROL_PORT`
- `JARVIS_LIFE_TRACKER_PORT`
- `JARVIS_SMART_HOME_PORT`
- `JARVIS_ANALYTICS_PORT`
- `JARVIS_SECURITY_PORT`
- `JARVIS_USER_PROFILE_PORT`
- `JARVIS_PLANNER_PORT`

The runtime now fails early with a clear message if a requested service port is already occupied.

## Important Runtime Flags

- `ENABLE_LLM=false`
  core backend default; do not enable optional LLM stack unless you are intentionally testing it
- `ENABLE_MEMORY=false`
  core backend default; memory stack is optional
- `JARVIS_USE_TLS=true`
  enables local HTTPS/WSS on the public gateway
- `PLANNER_LLM_ENABLED=false`
  default; hides optional planner LLM endpoints from the core runtime
- `PLANNER_AUTO_ACTIONS_SCHEDULER_ENABLED=false`
  default; avoids fake global scheduled user actions
- `PLANNER_AUTO_ACTIONS_USER_ID`
  required only if the scheduler is intentionally enabled

## Readiness Endpoints

Core runtime uses `/actuator/health/readiness` for all Spring services:

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

Analytics smoke additionally verifies real domain endpoints, not just actuator health.

## CI Alignment

Core CI lives in `.github/workflows/backend-readiness.yml` and now checks:

- release wiring for the core release chain
- core runtime smoke
- analytics smoke on top of a real core runtime

## K8s Alignment

- `k8s/base` matches the core backend service set.
- `k8s/overlays/prod` is broader than core scope and includes optional workloads scaled to zero.
- `scripts/product/jarvis-promote-images.sh` now promotes core backend images by default.
- Use `--include-data` and/or `--include-llm` only when you intentionally want optional stacks in the release overlay.
