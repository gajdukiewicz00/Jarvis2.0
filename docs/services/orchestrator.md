# orchestrator

## 1. Name

`orchestrator`

## 2. Type

Backend orchestration service.

## 3. Purpose

Receives high-level command requests, consults `nlp-service`, and routes resulting actions to downstream services such as `pc-control`, `smart-home-service`, and optionally `llm-service`.

## 4. Current Reality

The service is wired into the runtime and used by voice flows, but the orchestration logic is not a generic workflow engine. It is primarily switch-based routing around a finite set of known intents and downstream actions.

## 5. Entry Points

- Spring Boot app: `org.jarvis.orchestrator.OrchestratorApplication`
- REST base path: `/api/v1/orchestrator`

## 6. Configuration

Main configuration source:

- `apps/orchestrator/src/main/resources/application.yml`

Important settings include:

- server port `8083`
- downstream URLs for NLP, PC control, smart-home, and API gateway
- optional LLM enablement and timeout settings

## 7. API / WebSocket Surface

REST endpoints:

- `POST /api/v1/orchestrator/execute`
- `POST /api/v1/orchestrator/execute-detailed`
- `POST /internal/orchestrator/smart-home-action`
- `POST /internal/orchestrator/pc-action`

No WebSocket endpoint.

## 8. Main Internal Components

- `OrchestratorServiceImpl`
- `JarvisPhraseProvider`
- `NlpClient`
- `PcControlClient`
- `ApiGatewayPcClient`
- `SmartHomeClient`
- `LlmServiceClient`

## 9. Dependencies On Other Services

- `nlp-service`
- `pc-control`
- `smart-home-service`
- `api-gateway` internal PC routing path
- `llm-service` when enabled

## 10. Data / Storage

No database or durable workflow state.

## 11. Security Model

Protected by service auth. User context is propagated from upstream services.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/orchestrator -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Partially implemented.

## 14. Known Gaps / Caveats

- Intent coverage is limited to coded branches.
- Optional LLM use is a fallback/augmentation path, not a guaranteed core path.
- No long-running workflow, queue, or durable orchestration state exists here.
