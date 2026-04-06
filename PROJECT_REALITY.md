# Project Reality: Jarvis

## Verdict

Jarvis is **not** just a demo and **not** a finished AI platform.

The most accurate description is:

**Jarvis is a working backend/service platform with a real assistant core, plus a large amount of optional AI and partially implemented future-facing architecture.**

Confidence: **high**

Why this is the most honest label:

- The repository contains a real multi-service backend, not only diagrams.
- `scripts/runtime-up.sh` starts a concrete default runtime with core services even when AI is disabled.
- `.github/workflows/backend-readiness.yml` runs real core and data smoke jobs.
- Optional AI services exist and are wired, but they are disabled by default in the local runtime and scaled to `0` in the production overlay.
- Some areas that look advanced in docs are still placeholder-heavy in code.

Key evidence:

- `pom.xml`
- `scripts/runtime-up.sh`
- `scripts/runtime/common.sh`
- `.github/workflows/backend-readiness.yml`
- `k8s/overlays/prod/llm-service.yaml`
- `k8s/overlays/prod/memory-service.yaml`
- `k8s/overlays/prod/embedding-service.yaml`
- `k8s/overlays/prod/llm-server.yaml`
- `apps/planner-service/src/main/java/org/jarvis/planner/client/UserProfileClient.java`
- `apps/planner-service/src/main/java/org/jarvis/planner/client/LifeTrackerClient.java`
- `apps/planner-service/src/main/java/org/jarvis/planner/client/LlmServiceClient.java`

## Plain-English Explanation

Jarvis is a self-hosted personal assistant platform built as a microservice system.

At its core, it already does three real things:

- it authenticates users and exposes a public API through `api-gateway`
- it understands simple commands through `nlp-service` plus `orchestrator`
- it executes useful actions through domain services such as `pc-control`, `smart-home-service`, `planner-service`, `life-tracker`, and `analytics-service`

The AI part is real, but it is **not** the center of gravity of the current product.

In the code that exists today, Jarvis works best as:

- a backend for text and voice command routing
- a desktop-control and smart-home action system
- a personal planning / life-tracking backend
- an optional local-LLM and memory experiment layered on top of that core

The important architectural truth is this:

**AI is mostly an optional planning and language layer. The durable business logic still lives in the Java services.**

Confidence: **high**

## What Really Exists Today

| Area | Reality | Mandatory In Default Runtime | Evidence | Confidence |
| --- | --- | --- | --- | --- |
| Public edge | `api-gateway` is the main public HTTP entry point and public WebSocket proxy | Yes | `apps/api-gateway/src/main/resources/application.yaml`, `k8s/base/ingress.yaml` | high |
| Voice | `voice-gateway` is a real voice service with REST + WebSocket, STT/TTS, and notification delivery | Yes | `apps/voice-gateway/src/main/resources/application.yaml`, `apps/voice-gateway/src/main/java/.../VoiceWebSocketHandler.java` | high |
| Command brain | `nlp-service` plus `orchestrator` form the real command interpretation and routing core | Yes | `apps/nlp-service`, `apps/orchestrator` | high |
| Auth | `security-service` is a real JWT/auth service backed by PostgreSQL | Yes | `apps/security-service/src/main/java/.../AuthController.java`, `apps/security-service/src/main/resources/application.yml` | high |
| User data | `user-profile`, `planner-service`, `life-tracker`, and `analytics-service` are real domain/backend services | Yes | service code + runtime scripts + CI | high |
| Device control | `pc-control` is a real local automation service | Yes | `apps/pc-control` | high |
| Smart home | `smart-home-service` is real, but local runtime defaults to mock mode | Yes | `apps/smart-home-service/src/main/resources/application.yml`, `scripts/runtime/common.sh` | high |
| LLM | `llm-service` plus `llm-server` are real optional services, not default runtime core | No | `scripts/runtime/common.sh`, `k8s/overlays/prod/llm-service.yaml`, `k8s/overlays/prod/llm-server.yaml` | high |
| Long-term memory | `memory-service` plus `embedding-service` are real optional services, not default runtime core | No | `scripts/runtime/common.sh`, `.github/workflows/backend-readiness.yml`, `k8s/overlays/prod/memory-service.yaml`, `k8s/overlays/prod/embedding-service.yaml` | high |
| Desktop UX | JavaFX desktop client and JavaFX launcher are real product surfaces | No, but real repo components | `apps/desktop-client-javafx`, `apps/launcher-javafx` | high |
| Android | There is a partial Android client scaffold, not a finished client | No | `apps/mobile-client` | high |

## What Works, What Is Partial, What Is Still Mostly Ambition

### Clearly Working

- core backend runtime startup path
- authentication and JWT issuance
- API gateway routing
- rule-based NLP and orchestrated command execution
- desktop-control action routing
- planner task/reminder basics
- planner notification delivery to voice and desktop paths
- life-tracker CRUD-style domain behavior
- analytics aggregation over life-tracker data
- smart-home device registry and action dispatch
- desktop client login/tab shell
- launcher health/status model

Confidence: **high**

Key evidence:

- `.github/workflows/backend-readiness.yml`
- `scripts/runtime-smoke.sh`
- `scripts/analytics-smoke.sh`
- `scripts/memory-smoke.sh`
- `apps/*/controller`

### Partial Or Mixed-Quality

- planner "intelligence" layer
- planner integration with user goals and life-tracker business data
- mobile client
- real-device smart-home story
- LLM-driven product flows
- long-term memory as a daily default feature
- computer-vision security monitoring

Confidence: **high**

Why:

- `planner-service` contains real reminders/tasks, but multiple "AI" and integration classes are explicit placeholders.
- `mobile-client` contains a basic Android app shell, but not a complete product path.
- smart-home defaults to `SMART_HOME_PROVIDER=mock` in the local runtime.
- AI services are optional in runtime and off in prod overlay by default.
- the computer-vision path is real code, but its own doc describes it as an MVP with major limitations.

### Mostly Vision, Not Current Product Reality

- local LLM as a standard always-on assistant layer
- long-term memory as a standard always-on feature
- RabbitMQ or Kafka as a real backbone
- Android as a parity client
- advanced AI-enhanced planner recommendations
- production-grade biometric workstation verification

Confidence: **high**

## What I Verified Beyond Reading

I did not rely only on diagrams and README files.

### Local Runtime Verification

What I ran:

- `scripts/runtime-smoke.sh`
- `JARVIS_RUNTIME_SMOKE_SKIP_LLM=true JARVIS_RUNTIME_SMOKE_STOP_ON_EXIT=true scripts/runtime-smoke.sh`

Observed result:

- the LLM-enabled smoke failed first because port `5000` was already occupied on this machine
- the core smoke without the LLM stub started the local Postgres container and successfully brought up:
  - `security-service`
  - `user-profile`
  - `nlp-service`
  - `orchestrator`
  - `voice-gateway`
  - `pc-control`
  - `smart-home-service`
  - `life-tracker`
  - `analytics-service`
- startup then stopped at `api-gateway` because port `8080` was already occupied on this machine

What that means:

- the repo has a real startup path for the core backend
- my environment had port conflicts, so I could not claim a full clean local pass here
- those port collisions do **not** invalidate the code/CI/runtime wiring evidence

Confidence: **high**

Evidence:

- `scripts/runtime-smoke.sh`
- `/home/kwaqa/.jarvis/logs/local-runtime/llm-server-stub.log`
- `/tmp/jarvis-runtime-core-smoke.log`

## Main Sources Of Confusion

| Problem | Why It Confuses The Project Owner | Evidence | Confidence |
| --- | --- | --- | --- |
| Docs describe optional AI as if it were part of the always-on core | It makes the project sound more AI-centric than its actual default runtime | `README.md`, `docs/architecture.md`, `scripts/runtime/common.sh`, `k8s/overlays/prod/*` | high |
| `analytics-service` is shown like a DB-owning data service | In real code it is a stateless HTTP aggregator over `life-tracker` | `README.md`, `docs/architecture.md`, `apps/analytics-service/pom.xml`, `apps/analytics-service/src/main/java/.../LifeTrackerClient.java` | high |
| Planner looks smarter in docs than in code | Several planner integrations are still placeholder methods | `apps/planner-service/src/main/java/.../UserProfileClient.java`, `.../LifeTrackerClient.java`, `.../LlmServiceClient.java`, `.../LlmEnhancementService.java` | high |
| RabbitMQ and Kafka appear in configs and dependencies | They look live, but there is no evidence they are the real messaging backbone today | `apps/voice-gateway/pom.xml`, `apps/pc-control/pom.xml`, `apps/smart-home-service/pom.xml`, service code search results | high |
| There are too many "truth" documents | README, architecture docs, backend status docs, k8s docs, and archive docs overlap | `README.md`, `docs/architecture.md`, `docs/BACKEND_STATUS.md`, `k8s/README.md`, `docs/_archive/` | high |
| Optional services are real, but hidden behind flags and scale-to-zero | This makes them feel both "implemented" and "not really there" at the same time | `scripts/runtime/common.sh`, `k8s/overlays/prod/*.yaml` | high |

## What Is Optional Versus Core

### Core In The Repo-Supported Default Runtime

- `api-gateway`
- `security-service`
- `user-profile`
- `voice-gateway`
- `nlp-service`
- `orchestrator`
- `pc-control`
- `smart-home-service`
- `life-tracker`
- `analytics-service`
- `planner-service`
- PostgreSQL

Evidence:

- `scripts/runtime-up.sh`
- `scripts/runtime/common.sh`

Confidence: **high**

### Real But Optional

- `llm-service`
- `llm-server`
- `memory-service`
- `embedding-service`
- real MQTT-backed smart-home hardware mode
- computer-vision security monitoring
- wake-word support in the desktop client

Evidence:

- `scripts/runtime/common.sh`
- `k8s/overlays/prod/llm-service.yaml`
- `k8s/overlays/prod/memory-service.yaml`
- `apps/pc-control/src/main/java/org/jarvis/pccontrol/securitymonitoring`
- `apps/desktop-client-javafx/pom.xml`

Confidence: **high**

## Short Honest Summary

If someone asked, "What is Jarvis right now?" the honest answer would be:

**Jarvis is a real microservice backend for auth, command routing, planning, life tracking, analytics, voice, desktop control, and smart-home control, with optional local AI and memory layers that exist but are not the default core runtime.**

Confidence: **high**
