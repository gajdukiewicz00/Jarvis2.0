# Service Catalog: Jarvis

## How To Read This Catalog

Status meanings:

- `working`: clear implementation and real usage path
- `partially working`: core parts work, but important behavior is placeholder or incomplete
- `scaffold`: structure exists, but not a complete product/runtime path
- `planned`: described, but no strong implementation proof
- `legacy`: old or superseded
- `broken / unclear`: exists, but current runtime value is unclear or misleading

Confidence for this catalog approach: **high**

## Core Backend Services

| Service / Module | Purpose | Status | Really Used? | Dependencies | Called By | Notes | Confidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `api-gateway` | Main public HTTP entry point, public WebSocket proxy, JWT validation, service routing | working | yes | `security-service`, `orchestrator`, `voice-gateway`, `planner-service`, `life-tracker`, `analytics-service`, `user-profile`, `pc-control`, `smart-home-service`, optional `llm-service`, optional `memory-service` | desktop client, launcher, mobile client, public ingress | real public edge of the system; HTTP-first core | high |
| `security-service` | Registration, login, token refresh, identity lookup | working | yes | PostgreSQL, Flyway, JWT config | `api-gateway`, clients via gateway | real DB-backed auth service | high |
| `user-profile` | Preferences, goals, habits, priorities, profile defaults | working | yes | PostgreSQL, Flyway | `api-gateway`, `llm-service`, planner code intends to use it | more real than some current consumers suggest | high |
| `voice-gateway` | STT, TTS, voice websocket, audio upload handling, notifications | working | yes | `orchestrator`, STT/TTS providers, voice assets | `api-gateway`, `planner-service` internal notifications | real voice backend; public path commonly proxied through gateway | high |
| `nlp-service` | Deterministic intent parsing and slot extraction | working | yes | internal rule parsers only | `orchestrator` | rule-based, not ML-heavy | high |
| `orchestrator` | Main text-command routing brain | working | yes | `nlp-service`, `pc-control`, `smart-home-service`, optional `llm-service` | `api-gateway`, `voice-gateway` | core assistant routing logic lives here | high |
| `pc-control` | Desktop and OS automation | working | yes | local OS tools, scenario YAML, optional CV stack | `orchestrator`, `api-gateway`, planner indirect action paths | one of the most concrete services in the repo | high |
| `smart-home-service` | Device registry and smart-home action routing | working | yes | MQTT transport, mock transport, device catalog | `orchestrator`, `api-gateway`, `voice-gateway` internal paths | local runtime defaults to mock provider | high |
| `life-tracker` | Finance, calendar, time tracking system of record | working | yes | PostgreSQL, Flyway, service JWT tooling | `api-gateway`, `analytics-service`, tool flows | one of the strongest domain services | high |
| `analytics-service` | Read-only analytics and summaries over life data | working | yes | `life-tracker` over Feign | `api-gateway`, planner code | stateless HTTP aggregator, not a DB-owning analytics engine | high |
| `planner-service` | Tasks, reminders, planner tool endpoints, daily plans | partially working | yes | PostgreSQL, `analytics-service`, partial `user-profile`, partial `life-tracker`, partial `llm-service`, `voice-gateway` | `api-gateway`, internal tool flows | reminders/tasks are real; several intelligence/integration pieces are placeholders | high |
| `jarvis-common` | Shared JWT, auth propagation, common wiring | working | yes | Spring shared infra | all Java backend services | real cross-service glue module | high |

## Optional AI And Memory Services

| Service / Module | Purpose | Status | Really Used? | Dependencies | Called By | Notes | Confidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `llm-service` | Java-side AI orchestration, prompt building, tool planning | working | yes, but optional | `llm-server`, optional `memory-service`, optional `user-profile` | `api-gateway`, `orchestrator` | real code and endpoints; not part of default always-on runtime | medium-high |
| `llm-server` | Python model inference service | working | yes, but optional | model files, FastAPI runtime, optional GPU | `llm-service` | real server, default-off in prod overlay | medium-high |
| `memory-service` | Long-term memory ingestion and retrieval | working | yes, but optional | pgvector/PostgreSQL, `embedding-service` | `api-gateway` tool proxy, `llm-service` | real semantic memory path, not default core | high |
| `embedding-service` | Text embedding worker | working | yes, but optional | Python, `sentence-transformers`, torch | `memory-service` | real implementation, default-off in prod overlay | high |

## Clients And Local Product Surfaces

| Service / Module | Purpose | Status | Really Used? | Dependencies | Called By | Notes | Confidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `desktop-client-javafx` | Main desktop user client | working | yes | `api-gateway`, WebSockets, JavaFX/Kotlin | end user | login, tabs, PC/voice/life/analytics/settings surfaces exist | high |
| `launcher-javafx` | Local runtime launcher and diagnostics UI | working | yes | shell scripts, health endpoints, JavaFX/Kotlin | end user / operator | knows about core vs optional services and degraded mode | high |
| `mobile-client` | Android companion app | scaffold | partially | Android/Kotlin audio path | end user, in theory | not feature-complete; settings activity exists but is not declared in manifest | high |

## Supporting Runtime Infrastructure

| Service / Module | Purpose | Status | Really Used? | Dependencies | Called By | Notes | Confidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| local Postgres runtime container | local persistence for default runtime | working | yes | Docker | runtime scripts and stateful services | local runtime uses `pgvector/pgvector:pg16` container | high |
| `postgres` in k8s base | default relational DB in cluster | working | yes | PVC/storage | stateful domain services | cluster core persistence | high |
| `postgres-pgvector` in prod overlay | optional memory-oriented DB in cluster | working | yes, but optional | PVC/storage | `memory-service` | only needed when memory stack is enabled | high |
| `mosquitto` | MQTT broker | working | yes | k8s/Docker | `smart-home-service` | real smart-home transport backend, but local mock mode can reduce dependency | high |

## Partial, Placeholder, Or Misleading Subsystems

| Service / Module | Purpose | Status | Really Used? | Dependencies | Called By | Notes | Confidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| planner -> user-profile integration | feed planner with real goals/preferences | partially working | weakly | `user-profile` | planner internals | current `UserProfileClient.getUserGoals()` returns hardcoded goals | high |
| planner -> life-tracker integration | planner access to real life data | partially working | weakly | `life-tracker` | planner internals | current client is basically a health check placeholder | high |
| planner -> llm integration | text enhancement and AI plan help | partially working | weakly | `llm-service` | planner internals | current client returns input as-is in placeholder path | high |
| `pc-control` security-monitoring | workstation monitoring with CV | partially working | optional only | OpenCV, webcam, email | `pc-control` scheduler and endpoints | real MVP feature, but explicitly not production-grade biometrics | medium-high |
| `llm-service` STOMP websocket | interactive AI websocket chat | broken / unclear | unclear | Spring messaging | unknown | real endpoint exists, but no active repo client path found | high |

## Legacy Or Confusing Artifacts

| Service / Module | Purpose | Status | Really Used? | Dependencies | Called By | Notes | Confidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `assistant-core` | old assistant module | legacy | no | n/a | none | root `pom.xml` marks it removed and replaced by `llm-service` + `orchestrator` | high |
| root `analytics-service` file | unclear leftover | broken / unclear | no | none | none | zero-byte file in repo root; likely junk/confusing artifact | high |
| gateway `SecurityServiceClient` | old token validation approach | legacy | no | `security-service` | none in real runtime | points to nonexistent `/api/v1/auth/validate` endpoint | high |

## Repo-Supported Default Runtime Catalog

This is the most important practical list.

The default runtime started by `scripts/runtime-up.sh` treats these services as the real product core:

- `security-service`
- `user-profile`
- `nlp-service`
- `orchestrator`
- `voice-gateway`
- `pc-control`
- `smart-home-service`
- `life-tracker`
- `analytics-service`
- `api-gateway`
- `planner-service`

Optional services in that same runtime model:

- `llm-service`
- `llm-server`
- `memory-service`
- `embedding-service`

Evidence:

- `scripts/runtime-up.sh`
- `scripts/runtime/common.sh`

Confidence: **high**
