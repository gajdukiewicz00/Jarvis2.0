# api-gateway

## 1. Name

`api-gateway`

## 2. Type

Backend edge gateway.

## 3. Purpose

`api-gateway` is the main HTTP and WebSocket ingress for Jarvis. It authenticates clients, propagates user and service context to downstream services, proxies supported routes, and reports when a capability is disabled, unsupported in the current runtime, or unavailable upstream.

## 4. Current Reality

`api-gateway` does not own business data. Its job is operational truth:

- expose the real downstream surface that Jarvis supports
- fail explicitly when an optional feature is disabled
- distinguish disabled, unsupported, timeout, auth, and upstream-availability failures
- keep desktop-facing WebSocket behavior predictable during reconnects and disconnect storms

`route exists` is no longer treated as proof that the feature works.

## 5. Entry Points

- Spring Boot app: `org.jarvis.apigateway.ApiGatewayApplication`
- REST capability summary:
  - `/api/v1/capabilities`
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
- `services.llm.enabled`
- `services.memory.enabled`
- `services.vision-security.enabled`
- `services.pc-control.stub-mode`
- `jarvis.runtime.mode`
- proxy timeout policy
- internal HTTPS listener settings

Runtime-mode and optional-service truth is fed by runtime scripts and deployment manifests, not by documentation alone.

## 7. REST Surface

Direct proxy routes:

- `/auth/**` -> `security-service`
- `/api/v1/security/auth/**` -> `security-service` alias, rewritten upstream to `/auth/**`
- `/api/v1/analytics/**` -> `analytics-service`
- `/api/v1/life/**` -> `life-tracker`
- `/api/v1/llm/**` -> `llm-service` when enabled
- `/api/v1/nlp/**` -> `nlp-service`
- `/api/v1/orchestrator/**` -> `orchestrator`
- `/api/v1/pc/**` -> `pc-control` only in workstation runtimes with non-stub `pc-control`
- `/api/v1/planner/**` -> `planner-service`
- `/api/v1/smarthome/**` -> `smart-home-service`
- `/api/v1/vision-security/**` -> `vision-security-service` only when enabled and only in workstation runtimes
- `/api/v1/voice/**` -> `voice-gateway`

Tool routes:

- `/api/v1/tools/todo/**` -> `planner-service`
- `/api/v1/tools/calendar/**` -> `life-tracker`
- `/api/v1/tools/finance/**` -> `life-tracker`
- `/api/v1/tools/memory/**` -> `memory-service` when enabled

Local internal route:

- `/internal/pc-control/**` dispatches actions to connected desktop websocket clients and waits for desktop ACKs before reporting delivery state

## 8. WebSocket Surface

- `/ws/voice`
  - proxies the client websocket to `voice-gateway`
  - forwards user headers and service identity
  - buffers a small number of frames while the backend socket is connecting
  - returns explicit websocket error frames and close codes when the backend is unreachable, times out, closes unexpectedly, or never becomes ready

- `/ws/pc-control`
  - managed locally by `api-gateway`
  - requires an authenticated desktop client to identify itself
  - tracks reconnect replacement by `clientId`
  - sends heartbeat pings and removes stale sessions
  - tracks pending action ACKs so upstream callers can distinguish delivery from execution failure or timeout

## 9. Capability Reporting

`GET /api/v1/capabilities` returns the gateway's runtime-aware ingress summary.

The response includes:

- `runtimeMode`
- overall gateway `status`
- each advertised route
- downstream service name
- whether the route is mandatory
- route status such as `available`, `disabled`, `unsupported-runtime`, or `partially-degraded`
- per-route reason strings
- websocket descriptors, including connected desktop count for `/ws/pc-control`

This endpoint is configuration-aware and runtime-aware. It does not actively probe every downstream on each request.

## 10. Failure Contract

Expected degraded cases are returned as structured JSON instead of vague proxy failures.

Capability-disabled or runtime-unsupported responses include:

- `error`
- `message`
- `capability`
- `upstreamService`
- `runtimeMode`
- `supportedRuntimeModes`
- optional `details`

Upstream proxy failures include:

- `error`
- `message`
- `upstreamService`
- `upstreamPath`
- optional `upstreamStatus`
- optional parsed `upstreamBody`

Important error codes include:

- `FEATURE_DISABLED`
- `UNSUPPORTED_RUNTIME_MODE`
- `UPSTREAM_TIMEOUT`
- `UPSTREAM_CONNECTION_REFUSED`
- `UPSTREAM_HOST_NOT_FOUND`
- `UPSTREAM_UNAVAILABLE`
- `UPSTREAM_AUTH_FAILURE`
- `UPSTREAM_UNHEALTHY`
- `UPSTREAM_NOT_FOUND`
- `UPSTREAM_ERROR`

## 11. Context Propagation

For proxied HTTP calls the gateway standardizes:

- incoming user headers
- `X-Trace-Id` propagation
- gateway-issued `X-Service-Token` for non-`security-service` downstream calls
- delegated `X-User-Id` / `X-Username` / `X-User-Roles` derived from authenticated user context
- user `Authorization` is preserved only when proxying to `security-service` auth endpoints
- user `Authorization` is stripped before forwarding to internal downstream services
- dev-only fallback user headers when explicitly enabled
- shared timeout policy and shared error mapping

This is part of the platform's formal dual-plane model described in
`docs/security/AUTH_MODEL.md`.

## 12. Runtime Truth

- local/dev:
  - `vision-security-service` can be exposed when enabled
  - direct `/api/v1/pc/**` is supported when `pc-control` is not stubbed
  - `llm-service` and `memory-service` remain optional

- k8s/prod-like:
  - direct `/api/v1/pc/**` is intentionally rejected because `pc-control` is stubbed or workstation-bound
  - `vision-security-service` is rejected as unsupported runtime
  - `llm-service` and `memory-service` remain optional and may be disabled or scaled to zero
  - `/ws/pc-control` is still session-dependent rather than guaranteed, because it depends on authenticated desktop clients being connected

## 13. Dependencies

Configured downstream dependencies:

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

## 14. Data / Storage

No primary data store. PC-control WebSocket session state and ACK tracking live in memory inside the gateway process.

## 15. Security Model

- user-auth plane:
  - validates external user access JWTs at the edge
  - forwards `Authorization` only to `security-service`
- internal service-trust plane:
  - emits `X-Service-Token` for internal downstream calls except `security-service`
  - forwards authenticated user identity as delegated `X-User-*`
- websocket proxying follows the same split:
  - client `Authorization` terminates at the gateway
  - internal `/ws/voice` hop uses `X-Service-Token` plus delegated `X-User-*`
- supports local development fallback headers only when explicitly enabled

## 16. How To Run / Test

Module test command:

```bash
mvn -pl apps/api-gateway test
```

Runtime paths:

- local: `./scripts/runtime-up.sh`
- k8s mutable launcher path: `./jarvis-launch.sh`
- k8s digest-pinned release path: `./scripts/product/jarvis-deploy-prod.sh --overlay=./k8s/overlays/prod-release`

## 17. Verification Coverage

Gateway ingress behavior is covered by integration and websocket tests, including:

- happy-path HTTP proxying
- auth alias rewriting
- user-context and trace propagation
- disabled optional-service responses
- upstream connection-refused responses
- upstream timeout responses
- upstream auth failure mapping
- runtime-mode-specific capability reporting
- `/ws/voice` buffering and backend-failure handling
- `/ws/pc-control` ACK tracking, reconnect replacement, heartbeat, and stale-session cleanup

## 18. Implementation Status

Implemented as a truthful, runtime-aware ingress layer.

## 19. Known Limits

- `/api/v1/capabilities` is runtime/config truthful, but it is not a live readiness probe for every downstream service instance.
- `/ws/pc-control` proves gateway-side session handling and ACK tracking, but it still depends on a real desktop client being connected; the route cannot promise workstation presence by itself.
- `api-gateway` can classify upstream failures clearly, but it cannot make an unhealthy downstream healthy.
