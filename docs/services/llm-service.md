# llm-service

## 1. Name

`llm-service`

## 2. Type

Optional backend AI service.

## 3. Purpose

Provides authenticated LLM-facing REST and WebSocket APIs, prompt/session handling, runtime health, admission control, and optional integration with `memory-service` and `user-profile`.

## 4. Current Reality

This service is implemented but optional. It is disabled by default in configuration and, in production, calls llama.cpp through the selectorless `host-model-daemon` Service and manually patched Endpoints.

## 5. Entry Points

- Spring Boot app: `org.jarvis.llm.LlmServiceApplication`
- REST base path: `/api/v1/llm`
- STOMP/SockJS endpoint: `/ws/jarvis-llm`

## 6. Configuration

Main configuration source:

- `apps/llm-service/src/main/resources/application.yml`

Important settings include:

- server port `8091`
- `jarvis.llm.enabled`, default `false`
- `llm.base-url` / `LLM_SERVER_URL`, pointing at `http://host-model-daemon.jarvis-prod.svc.cluster.local:18080` in production
- request budgets and model profiles
- admission control and executor limits
- memory integration flags and URL
- optional `user-profile` integration flag and URL

## 7. API / WebSocket Surface

REST endpoints:

- `POST /api/v1/llm/chat`
- `POST /api/v1/llm/dialog`
- `DELETE /api/v1/llm/session/{sessionId}`
- `GET /api/v1/llm/health`
- `GET /api/v1/llm/runtime`
- `POST /api/v1/llm/orchestrate`

The public Jarvis chat endpoint remains `POST /api/v1/llm/chat`; `LlmClient` forwards inference to the upstream llama.cpp OpenAI-compatible endpoint `POST /v1/chat/completions`.

STOMP messaging surface:

- connect to `/ws/jarvis-llm`
- send chat messages to `/app/llm-chat`
- subscribe to `/topic/llm-response/{sessionId}`

## 8. Main Internal Components

- `LlmService`
- `LlmRestController`
- `LlmOrchestratorService`
- `LlmLifecycleManager`
- `LlmAdmissionController`
- `AiRuntimeStatusService`
- `LlmClient`
- `MemoryClient`
- `UserProfileClient`

## 9. Dependencies On Other Services

- `host-model-daemon` Service + manual Endpoints to the Linux host llama.cpp server
- `memory-service` when enabled
- `user-profile` when enabled

## 10. Data / Storage

- in-memory session/conversation state inside the service
- no dedicated database configured in this module

## 11. Security Model

- `/api/v1/llm/health` is public
- the rest of the API uses the shared security base
- user context may be omitted for internal service requests

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/llm-service -am test
```

Start the optional AI stack locally:

```bash
ENABLE_LLM=true ./scripts/runtime-up.sh
```

## 13. Implementation Status

Implemented, optional.

## 14. Known Gaps / Caveats

- Process health does not guarantee inference readiness; the module can be up while `host-model-daemon` is unavailable.
- This module is not part of the core runtime baseline.
- Kubernetes/public ingress is only a supported release claim when the generated `k8s/overlays/prod-release` overlay includes an immutable ref for `llm-service`. Model execution remains outside the cluster through `host-model-daemon`.
