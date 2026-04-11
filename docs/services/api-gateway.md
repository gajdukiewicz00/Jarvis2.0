# api-gateway

## 1. Name

`api-gateway`

## 2. Type

Backend edge gateway.

## 3. Purpose

Serves as the main external REST and WebSocket entry point for Jarvis clients. It validates user JWTs, proxies requests to downstream services, and routes desktop-targeted PC/voice WebSocket traffic.

## 4. Current Reality

`api-gateway` does not own business data. It is primarily a routing, auth, and session-bridging service for the desktop and other clients.

## 5. Entry Points

- Spring Boot app: `org.jarvis.apigateway.ApiGatewayApplication`
- WebSocket endpoints:
  - `/ws/voice`
  - `/ws/pc-control`

## 6. Configuration

Main configuration sources:

- `apps/api-gateway/src/main/resources/application.yaml`
- `apps/api-gateway/src/main/resources/application-dev.yaml`

Important settings include:

- server port `8080`
- downstream service base URLs
- JWT secret and auth toggles
- CORS allowed origins
- internal HTTPS listener settings
- optional `vision-security` and `memory` proxy enablement

## 7. API / WebSocket Surface

Gateway-facing REST surface is implemented as proxy controllers for:

- `/auth/**`
- `/api/v1/analytics/**`
- `/api/v1/life/**`
- `/api/v1/llm/**`
- `/api/v1/nlp/**`
- `/api/v1/orchestrator/**`
- `/api/v1/pc/**`
- `/api/v1/planner/**`
- `/api/v1/smarthome/**`
- `/api/v1/tools/**`
- `/api/v1/vision-security/**`
- `/api/v1/voice/**`

Internal controller surface used for desktop PC routing:

- `/internal/pc-control/**`

WebSocket surface:

- `/ws/voice` proxied to `voice-gateway`
- `/ws/pc-control` handled locally for desktop identification, dispatch, and ACK tracking

## 8. Main Internal Components

- proxy controllers under `org.jarvis.apigateway.controller`
- `JwtAuthFilter`
- `JwtUtil`
- `SecurityConfig`
- `PcControlWebSocketHandler`
- `VoiceWebSocketProxyHandler`

## 9. Dependencies On Other Services

Configured downstream dependencies include:

- `security-service`
- `analytics-service`
- `life-tracker`
- `llm-service`
- `memory-service`
- `nlp-service`
- `orchestrator`
- `pc-control`
- `planner-service`
- `smart-home-service`
- `vision-security-service`
- `voice-gateway`

## 10. Data / Storage

No primary data store. WebSocket session state for PC control is kept in memory.

## 11. Security Model

- validates external JWTs
- supports service-to-service authentication
- forwards user context to downstream services
- has a dev profile for relaxed local development behavior

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/api-gateway -am test
```

Runtime paths:

- local: `./scripts/runtime-up.sh`
- k8s: `./jarvis-launch.sh`

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Availability is downstream-dependent.
- It is easy to over-document gateway routes as product features; the gateway only proves that the route/proxy exists, not that the downstream capability is complete.
- Optional services such as `memory-service` and `vision-security-service` can be disabled or absent depending on runtime mode.
