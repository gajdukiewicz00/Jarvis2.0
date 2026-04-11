# Jarvis 2.0

This is the canonical project overview for the repository.

Source of truth is the current code and runtime wiring: `pom.xml`, module `pom.xml` files, `application.yml` and `application*.yml`, controller/service classes, Flyway migrations, runtime scripts, Docker assets, and Kubernetes manifests. Older Markdown is only valid when it matches those artifacts.

## What This Repo Actually Contains

Jarvis is a Maven multi-module workspace for a personal assistant platform with:

- Spring Boot backend services under `apps/`
- JavaFX desktop applications under `apps/`
- optional Python AI workers under `docker/`
- local runtime scripts under `scripts/`
- Kubernetes manifests under `k8s/`

The repository contains real code for auth, gateway routing, voice I/O, NLP, orchestration, planning, personal-data tracking, analytics, smart-home actions, desktop control, and a workstation-local vision-security service.

## Runtime Entry Points

Local process runtime:

- `./scripts/setup-local.sh`
- `./scripts/setup-voice-local.sh`
- `./scripts/setup-ai-local.sh`
- `./scripts/runtime-up.sh`
- `./scripts/runtime-status.sh`
- `./scripts/runtime-down.sh`

Kubernetes / product runtime:

- `./jarvis-launch.sh`
- `./jarvis-stop.sh`
- `./jarvis-logs.sh`
- `./scripts/product/jarvis-*.sh`

Desktop run paths:

- `mvn -pl apps/desktop-app-javafx -am javafx:run`
- `mvn -pl apps/desktop-client-javafx -am javafx:run`
- `mvn -pl apps/launcher-javafx -am javafx:run`

Current reality notes:

- `vision-security-service` is part of the local runtime, not `k8s/base`.
- `pc-control` is real on a local workstation, but runs in stub mode in Kubernetes.
- optional AI workloads exist in local scripts and `k8s/overlays/prod`, but they are not part of the minimum core runtime.
- no mobile module exists in this repository.

## Module And Runtime Catalog

| Component | Type | Runtime | Status | Doc |
| --- | --- | --- | --- | --- |
| `jarvis-common` | shared Java library | build-only | implemented | [docs/services/jarvis-common.md](docs/services/jarvis-common.md) |
| `api-gateway` | backend gateway | local + k8s | implemented | [docs/services/api-gateway.md](docs/services/api-gateway.md) |
| `voice-gateway` | backend voice service | local + k8s | implemented | [docs/services/voice-gateway.md](docs/services/voice-gateway.md) |
| `nlp-service` | backend NLP service | local + k8s | implemented, rule-based | [docs/services/nlp-service.md](docs/services/nlp-service.md) |
| `orchestrator` | orchestration service | local + k8s | partial, bounded intent router | [docs/services/orchestrator.md](docs/services/orchestrator.md) |
| `pc-control` | desktop-control service | local + k8s | implemented locally, stubbed in k8s | [docs/services/pc-control.md](docs/services/pc-control.md) |
| `vision-security-service` | workstation-local CV service | local only | implemented, local-only | [docs/services/vision-security-service.md](docs/services/vision-security-service.md) |
| `life-tracker` | personal-data service | local + k8s | implemented | [docs/services/life-tracker.md](docs/services/life-tracker.md) |
| `analytics-service` | analytics/read-model service | local + k8s | implemented | [docs/services/analytics-service.md](docs/services/analytics-service.md) |
| `planner-service` | planning service | local + k8s | partial, planner-owned LLM endpoints return `501` | [docs/services/planner-service.md](docs/services/planner-service.md) |
| `user-profile` | profile/context service | local + k8s | implemented | [docs/services/user-profile.md](docs/services/user-profile.md) |
| `security-service` | auth service | local + k8s | implemented | [docs/services/security-service.md](docs/services/security-service.md) |
| `smart-home-service` | smart-home API | local + k8s | implemented with static catalog | [docs/services/smart-home-service.md](docs/services/smart-home-service.md) |
| `llm-service` | authenticated AI facade | optional local + optional k8s | implemented, optional | [docs/services/llm-service.md](docs/services/llm-service.md) |
| `memory-service` | semantic memory/vector service | optional local + optional k8s | implemented, optional | [docs/services/memory-service.md](docs/services/memory-service.md) |
| `desktop-client-javafx` | desktop client/module | local desktop | implemented | [docs/services/desktop-client-javafx.md](docs/services/desktop-client-javafx.md) |
| `desktop-app-javafx` | shell desktop app | local desktop | implemented | [docs/services/desktop-app-javafx.md](docs/services/desktop-app-javafx.md) |
| `launcher-javafx` | launcher/operator app | local desktop | implemented | [docs/services/launcher-javafx.md](docs/services/launcher-javafx.md) |
| `docker/llm-server` | Python inference worker | optional local + optional k8s | implemented, optional | [docs/services/llm-server.md](docs/services/llm-server.md) |
| `docker/embedding-service` | Python embedding worker | optional local + optional k8s | implemented, optional | [docs/services/embedding-service.md](docs/services/embedding-service.md) |
| `postgres` | runtime datastore | local managed container + k8s | implemented infra, optional pgvector overlay | [docs/services/postgres.md](docs/services/postgres.md) |
| `mosquitto` | MQTT broker | k8s only | implemented infra | [docs/services/mosquitto.md](docs/services/mosquitto.md) |
| `observability-stack` | Prometheus/Loki/Tempo/Grafana/Alloy | k8s only | implemented infra | [docs/services/observability-stack.md](docs/services/observability-stack.md) |

## Actual Topology

The repo is centered around `api-gateway`.

- external clients authenticate through gateway-proxied `security-service` endpoints
- `api-gateway` is the main REST and WebSocket edge
- `voice-gateway` owns STT, TTS, voice WebSocket handling, and rule-based voice command dispatch
- `orchestrator` routes normalized intents to `pc-control`, `smart-home-service`, and optional `llm-service`
- `life-tracker` is the main source-of-record service for finance, calendar, and time tracking
- `analytics-service` derives summaries from `life-tracker`
- `planner-service` stores tasks/reminders in PostgreSQL and talks to analytics/profile/voice/gateway paths
- `llm-service` fronts `llm-server` and can optionally integrate with `memory-service` and `user-profile`
- `memory-service` uses `embedding-service` plus PostgreSQL/pgvector
- `vision-security-service` is a separate local workstation service with gateway proxying

## Local Runtime Defaults

Default local ports come from `scripts/runtime/common.sh`.

| Component | Port |
| --- | --- |
| `api-gateway` | `8080` |
| `voice-gateway` | `8081` |
| `nlp-service` | `8082` |
| `orchestrator` | `8083` |
| `pc-control` | `8084` |
| `life-tracker` | `8085` |
| `smart-home-service` | `8086` |
| `analytics-service` | `8087` |
| `security-service` | `8088` |
| `user-profile` | `8089` |
| `llm-service` | `8091` |
| `planner-service` | `8092` |
| `memory-service` | `8093` |
| `vision-security-service` | `8094` |
| `llm-server` | `15000` |
| `embedding-service` | `15001` |

Optional local AI flags:

- `ENABLE_LLM=true`
- `ENABLE_MEMORY=true`

The local runtime can auto-start a managed Postgres container if external DB settings are not supplied.

## Kubernetes Reality

`k8s/base` contains:

- core Spring services
- `postgres`
- `mosquitto`
- ingress resources
- observability manifests

`k8s/overlays/prod` adds hardening and optional AI workloads:

- `llm-server`
- `llm-service`
- `embedding-service`
- `memory-service`
- `postgres-pgvector`

Those optional AI workloads are present in manifests but default to `replicas: 0` in the prod overlay.

## Current Constraints

- no mobile client/module exists
- `vision-security-service` is not deployed by `k8s/base`
- `pc-control` is intentionally stubbed in Kubernetes
- `smart-home-service` uses a fixed device catalog and in-memory state
- `nlp-service` is deterministic/rule-based
- `orchestrator` is not a general workflow engine
- `planner-service` core planner works, but planner-owned LLM endpoints return `501 Not Implemented`

## Build And Verification

Build or test from the repo root:

```bash
mvn test
```

Target one module with dependencies:

```bash
mvn -pl apps/<module> -am test
```

Typical verification commands for this repo:

```bash
./scripts/runtime-status.sh
./scripts/runtime-smoke.sh
./scripts/verify-observability.sh
```

## Supplementary Docs Kept On Purpose

Local or policy docs that remain intentionally outside the canonical overview:

- [k8s/README.md](k8s/README.md)
- [docker/llm-server/README.md](docker/llm-server/README.md)
- [docs/HTTPS_STANDARD.md](docs/HTTPS_STANDARD.md)
- [docs/security/SECRETS_POLICY.md](docs/security/SECRETS_POLICY.md)

Per-service and per-module documentation lives under [`docs/services/`](docs/services/).
