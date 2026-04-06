# Tech Stack Reality: Jarvis

## Classification Rules

- **A**: real usage in working code or repo-supported runtime paths
- **B**: technically wired or implemented, but optional, limited, or not part of the main proven path
- **C**: claimed in docs/architecture, but not supported by real implementation evidence
- **D**: legacy, dead, duplicated, misleading, or unclear tail

Confidence for the classification framework: **high**

## Backend And Data

| Technology | Where Found | Intended Role | Really Used? | Category | Evidence | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| Java 21 | root `pom.xml`, service modules | backend runtime language | yes | A | `pom.xml` sets `<java.version>21</java.version>`; core services build from Maven root | high |
| Spring Boot 3.3.x | root `pom.xml`, all Java services | service framework | yes | A | parent `spring-boot-starter-parent` is `3.3.13`; all backend services inherit from root | high |
| Spring Web | gateway, voice, planner, life-tracker, analytics, others | REST APIs | yes | A | multiple `spring-boot-starter-web` dependencies and real controllers in active services | high |
| Spring Security | gateway, security-service, other services via `jarvis-common` | auth, JWT, request filtering | yes | A | `security-service` auth flows, gateway JWT filter, service JWT filters in `jarvis-common` | high |
| Spring Cloud OpenFeign | gateway, analytics, some service clients | service-to-service HTTP | yes | A | `apps/api-gateway/src/main/java/.../client`, `apps/analytics-service/.../LifeTrackerClient.java` | high |
| WebSocket | gateway, voice-gateway, desktop client | real-time voice and PC control | yes | A | `api-gateway` websocket handlers, `voice-gateway` websocket config, desktop websocket clients | high |
| JPA / Hibernate | security, user-profile, planner, life-tracker, memory | stateful persistence | yes | A | entities, repositories, migrations, real CRUD services | high |
| Flyway | security, user-profile, planner, life-tracker, memory | schema migrations | yes | A | service application configs and Flyway dependencies | high |
| HikariCP | Spring Boot stateful services | JDBC connection pool | yes, implicit via Spring Boot datasource stack | A | stateful services use standard Spring datasource/JPA stack; no custom pool replacement found | medium-high |
| PostgreSQL | local runtime, k8s base/overlay, stateful services | primary relational store | yes | A | `scripts/runtime/common.sh`, service datasource configs, k8s manifests | high |
| pgvector | memory stack | vector storage/search | yes, optional path | A | `MemoryChunk.java`, `MemoryChunkRepository.java`, memory smoke path, local runtime uses `pgvector/pgvector:pg16` | high |
| Resilience4j | `api-gateway` | circuit breaker support | weak real usage evidence | B | dependency + config exist, but no strong active code-path evidence beyond config | medium |
| Quartz | `planner-service` | job scheduling | technically included, but current runtime uses Spring `@Scheduled` | B | `planner-service/pom.xml` includes Quartz; actual code uses `@Scheduled` in `ReminderScheduler` and `AutoActionService` | high |

## AI And Voice

| Technology | Where Found | Intended Role | Really Used? | Category | Evidence | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| Python | `docker/llm-server`, `docker/embedding-service`, local runtime | AI helper runtimes | yes | A | Python apps exist and are started by runtime scripts when enabled | high |
| FastAPI | `llm-server`, `embedding-service` | inference and embedding APIs | yes, optional path | A | `docker/llm-server/app/main.py`, `docker/embedding-service/app/main.py` | high |
| Uvicorn | Python services | ASGI serving | yes, optional path | A | requirements and startup path in Python services | high |
| Pydantic | Python services | request/response models and config | yes, optional path | A | Python service code and requirements | high |
| `sentence-transformers` | embedding service | multilingual embeddings | yes, optional path | A | `docker/embedding-service/app/embedder.py`, requirements, tests | high |
| Torch | embedding service, transformer backend support | model execution | yes, optional path | A | `docker/embedding-service/requirements.txt`, `docker/llm-server` backend support | medium-high |
| Vosk | `voice-gateway`, local models path | default STT provider when models exist | yes | A | `voice-gateway` config + provider classes + runtime autodetection in `scripts/runtime/common.sh` | high |
| eSpeak / eSpeak NG | `voice-gateway` | default local TTS fallback | yes | A | `voice-gateway` config defaults to `espeak`, implementation code uses system TTS fallback | high |
| Pre-recorded WAV assets | `voice-gateway` resources | low-latency spoken responses | yes | A | `voice-gateway` config enables pre-recorded responses by default; asset catalogs exist | high |
| `llm-service` Java orchestration | `apps/llm-service` | prompt/tool orchestration | yes, but optional | B | real service code exists, but default runtime and prod overlay keep it optional | high |
| `llm-server` Python inference | `docker/llm-server` | local model serving | yes, but optional | B | real server exists, but disabled by default and scaled to `0` in prod overlay | high |
| Transformers | `docker/llm-server` | alternate model backend | technically supported, not proven as default path | B | backend code exists, but runtime docs/config prefer `llamacpp` | medium |
| Accelerate | `docker/llm-server/requirements.txt` | transformer acceleration | technically available | B | dependency exists for transformer backend, but not part of the repo's default proven runtime | medium |
| `llama.cpp` / `llama-cpp-python` | `docker/llm-server` | preferred local LLM backend | technically real, optional runtime path | B | config defaults `LLM_BACKEND=llamacpp`, optional deployment scaled to `0` | medium-high |
| Whisper | `voice-gateway` | alternate STT provider | technically real, not default | B | provider class and config exist, but default runtime prefers Vosk or NoOp | high |
| Google TTS | `voice-gateway` | alternate cloud TTS provider | technically real, not default | B | config section exists; default provider is `espeak` | high |
| STOMP / SockJS in `llm-service` | `apps/llm-service` | websocket chat UI path | code exists, no clear repo client usage | B | `WebSocketConfig.java`, `LlmWebSocketController.java`, no active client found in repo | high |
| Long-term memory as a standard always-on product feature | README and architecture language | permanent assistant memory | not true for default runtime | C | runtime defaults disable memory; prod overlay scales memory stack to `0` | high |
| Local LLM as a standard always-on product feature | README and architecture language | core intelligence layer | not true for default runtime | C | runtime defaults disable LLM; prod overlay scales LLM stack to `0` | high |

## Messaging, Clients, And Local UX

| Technology | Where Found | Intended Role | Really Used? | Category | Evidence | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| MQTT / Mosquitto | `smart-home-service`, `k8s/base/mosquitto` | smart-home transport | yes | A | `MqttConfig.java`, `MqttSmartHomeTransport.java`, k8s Mosquitto manifests | high |
| JavaFX | desktop client, launcher | desktop UI | yes | A | `apps/desktop-client-javafx`, `apps/launcher-javafx` | high |
| Kotlin | desktop client, launcher, Android app | UI/client language | yes | A | Kotlin source in desktop, launcher, and mobile modules | high |
| Android / Gradle app | `apps/mobile-client` | mobile companion app | yes, but only as a partial scaffold | A | real Android module exists with build files and activities | high |
| RabbitMQ | voice, smart-home, pc-control configs/deps | event transport | no strong real runtime usage | B | config blocks and dependencies exist, but no active listener/producer path found | high |
| Kafka | voice, smart-home, pc-control configs/deps | event transport | no strong real runtime usage | B | config blocks and dependencies exist, but no active listener/producer path found | high |
| Porcupine wake word | desktop client | wake-word UX | optional niche feature | B | desktop client POM includes Porcupine support, not part of default verified runtime | medium |
| OpenCV security monitoring stack | `pc-control` | workstation monitoring / CV | real code, but optional MVP feature | B | `pc-control` securitymonitoring package and dedicated doc call it an MVP | medium-high |
| Android as a real parity product client | docs/product framing | full mobile client | not supported by current code | C | manifest wires only `MainActivity`; no real auth/app flow or settings wiring | high |

## Infra, Build, And Operations

| Technology | Where Found | Intended Role | Really Used? | Category | Evidence | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| Docker | runtime helpers, Dockerfiles, local dependencies | containerized services and local infra | yes | A | Dockerfiles, local Postgres management, product scripts | high |
| Kubernetes | `k8s/` | cluster deployment | yes | A | base + prod overlays, deployment scripts, backend status docs | high |
| Kustomize | `k8s/base`, `k8s/overlays/*` | manifest composition | yes | A | overlay structure and deployment scripts | high |
| ingress | `k8s/base/ingress.yaml` | edge traffic routing | yes | A | ingress manifest plus TLS/readme docs | high |
| TLS overlays | `k8s/overlays/prod-release-internal-tls-*` | incremental internal HTTPS rollout | yes | A | committed overlays and backend readiness docs | high |
| NetworkPolicy | `k8s/overlays/prod/networkpolicy-*` | namespace traffic restrictions | yes | A | explicit policy manifests and k8s README | high |
| Kyverno | `k8s/overlays/prod/kyverno` | admission policy baseline | yes | A | policy manifests and k8s README | high |
| Maven multi-module | root `pom.xml` | main Java build | yes | A | root aggregator with all active Java services/modules | high |
| Gradle | `apps/mobile-client` | Android build | yes | A | real Gradle Android module | high |
| Shell scripts | `scripts/`, `jarvis-launch.sh`, runtime/product helpers | runtime, smoke, deployment, release automation | yes | A | runtime and product scripts are central to repo operation | high |
| "Analytics has its own DB" | docs/diagrams | analytics persistence | no | C | `apps/analytics-service/pom.xml` has no JPA/datasource/Flyway stack; service uses Feign to `life-tracker` | high |
| root zero-byte `analytics-service` file | repo root | unclear leftover | no meaningful use | D | `analytics-service` file at repo root is empty | high |
| Unused `SecurityServiceClient` in gateway | `api-gateway` | token validation path | broken/unreal | D | client points to nonexistent `/api/v1/auth/validate` and has no real usage path | high |
| Legacy archived AI/spec docs | `docs/_archive/` | historical notes | not current runtime truth | D | archive docs still describe states that are no longer the main source of truth | high |

## Bottom Line

The actual stack is strongest in these areas:

- Java/Spring backend services
- HTTP-first service orchestration
- PostgreSQL-backed domain services
- voice and desktop control plumbing
- operational scripts and Kubernetes deployment discipline

The stack is weaker or more aspirational in these areas:

- always-on local LLM productization
- long-term memory as a default feature
- Kafka/RabbitMQ event architecture
- mobile parity
- AI-enhanced planner intelligence

Confidence: **high**
