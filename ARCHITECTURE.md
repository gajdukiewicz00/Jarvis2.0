# Architecture

Audit date: 2026-05-08; **light-touch refresh 2026-07-05** to add the
`agent-service`/`media-service` modules below (see note at the end of this
section). The canonical project overview lives in [README.md](README.md).
This page summarizes the **runtime topology** and **data flows** as they
exist in the current code, not the long-form Phase 0 narrative.

Cross-references:

- [docs/STATUS.md](docs/STATUS.md) — current per-subsystem status, including
  which wave-1 features (across `agent-service`, `memory-service`,
  `planner-service`, `life-tracker`, `analytics-service`, `security-service`,
  `media-service`, `smart-home-service`) are code-complete but not yet
  redeployed
- [docs/COMPONENT_STATUS.md](docs/COMPONENT_STATUS.md) — every module with
  status and evidence
- [docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md) — supported entry points
- [docs/DEPLOYMENT_CANONICAL.md](docs/DEPLOYMENT_CANONICAL.md) — the single
  canonical deploy/recover path (`infra/k8s/overlays/prod`; `k8s/` is frozen)
- [docs/LEGACY_AND_CLEANUP.md](docs/LEGACY_AND_CLEANUP.md) — drift items
- [docs/architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md](docs/architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md) — Phase 0 spec
- [docs/architecture/ADR/](docs/architecture/ADR/) — architecture decisions

## High-Level Map

```
                                ┌──────────────────────────────────┐
                                │   external clients (HTTPS / WSS) │
                                └─────────────┬────────────────────┘
                                              │
                                  ingress-nginx │ jarvis-prod
                                              │
                                              ▼
                                ┌──────────────────────────────┐
                                │       api-gateway:8080       │  Spring Cloud Gateway
                                └─┬────────────┬───────────────┘   + Feign clients
                                  │            │
                ┌─────────────────┘            └────────────────────────────┐
                │                                                            │
                ▼                                                            ▼
   ┌──────────────────────┐                                 ┌──────────────────────────┐
   │ security-service:8088 │  JWT issuer / validator        │ voice-gateway:8081       │
   └──────────┬───────────┘                                  │ STT (vosk) / TTS / WS    │
              │                                              └────────────┬─────────────┘
              ▼                                                            │
  ┌────────────────────────┐                                              ▼
  │ user-profile:8089      │                                ┌──────────────────────────┐
  └─────────┬──────────────┘                                │ nlp-service:8082         │
            │                                               │ rule-based intent parse  │
            ▼                                               └────────────┬─────────────┘
  ┌────────────────────────┐                                              │
  │ life-tracker:8085      │◄── analytics-service:8087                    ▼
  │ planner-service:8092   │                                ┌──────────────────────────┐
  └────────────────────────┘                                │ orchestrator:8083        │
                                                            │ bounded intent router    │
                                                            └─┬───────────────┬────────┘
                                                              │               │
                                              ┌───────────────┘               └───────────────┐
                                              ▼                                               ▼
                                ┌──────────────────────────┐                ┌─────────────────────────┐
                                │ pc-control:8084          │                │ smart-home-service:8086 │
                                │ stub in K8s; real on host│                │ static catalog          │
                                └──────────────────────────┘                └─────────────────────────┘

  Local-only:                              Optional AI overlay (gated by ENABLE_LLM / ENABLE_MEMORY):

  ┌────────────────────────────┐           ┌─────────────────────┐    ┌──────────────────────────┐
  │ vision-security-service:8094│          │ llm-service:8091    │───►│ host-model-daemon (host) │
  └────────────────────────────┘           └─────────────────────┘    │ llama.cpp on Linux host  │
                                                                     │ Service + manual EP      │
                                           ┌─────────────────────┐    └──────────────────────────┘
                                           │ memory-service:8093 │───►┌──────────────────────────┐
                                           └─────────────────────┘    │ embedding-service-py     │
                                                                     │ (Containerfile, podman)  │
                                                                     └──────────────────────────┘
```

Ports above match [`scripts/runtime/common.sh`](scripts/runtime/common.sh)
defaults; in cluster mode the same containerPort values are used. The diagram
predates two modules that are already deployed but not yet drawn in
(`agent-service`, `media-service`) — see the note below the responsibilities
table.

## Service Responsibilities

| Service | Responsibility | Notable peers |
| --- | --- | --- |
| `api-gateway` | edge REST + WS, Feign service-to-service auth | all backend services |
| `security-service` | JWT issuance + validation | `api-gateway`, `user-profile` |
| `voice-gateway` | STT (vosk), TTS, voice WebSocket dispatch | `nlp-service`, `orchestrator` |
| `nlp-service` | rule-based intent extraction | `voice-gateway`, `orchestrator`, optional `llm-service` |
| `orchestrator` | normalized intent → action; `RabbitTopologyConfig` + Kafka audit | `pc-control`, `smart-home-service`, `llm-service` |
| `pc-control` | desktop control on workstation; stubbed in cluster | `orchestrator` |
| `smart-home-service` | static device catalog + in-memory state | `orchestrator` |
| `vision-security-service` | local CV / owner verification | local desktop only |
| `life-tracker` | finance / calendar / time tracking | `analytics-service`, `user-profile`, `planner-service` |
| `analytics-service` | read-model summaries over `life-tracker` | `life-tracker` |
| `planner-service` | tasks, reminders, recommendations | `life-tracker`, `analytics-service`, `voice-gateway` |
| `user-profile` | profile / context | `security-service`, optional `memory-service` |
| `llm-service` | authenticated AI facade | `host-model-daemon` (prod), `llm-server` (deprecated wrapper) |
| `memory-service` | semantic memory; pgvector + embedding worker | `embedding-service-py`, PostgreSQL/pgvector |
| `sync-service` | E2E sync inbox for paired devices | `cloud-relay` |
| `cloud-relay` | off-prem opaque blob forwarder | `sync-service` |
| `agent-service` | role-based agent swarm: task queue, permission guard, panic-checkpointed executors, git-worktree sandboxed code/tester roles | `api-gateway` (panic kill-switch), orchestrator-adjacent, own Postgres task store |
| `media-service` | media pipeline: ffmpeg extraction/mux, async job queue, ASR → translation → subtitles/dubbing | `llm-service` (for real translation), own Postgres job store |
| `desktop-javafx` | desktop shell, launcher UX, local auth bootstrap, host-present UI | api-gateway over HTTPS, voice-gateway over WSS |

**Wave-1 note (2026-07-05):** `agent-service` and `media-service` are already
deployed (movie-tagged images in `jarvis-prod`), but this wave added
substantial new functionality to both — a Postgres-backed task store,
git-worktree sandboxing, and a real TESTER-role executor for `agent-service`;
real `ffmpeg`/Whisper.cpp/LLM-translation provider scaffolding for
`media-service` (still mock-by-default) — that is code-complete and
unit-tested but **not yet rebuilt into the running images**. The same wave
added comparable code-only-not-deployed feature sets to `memory-service`,
`planner-service`, `life-tracker`, `analytics-service`, and `security-service`.
See [docs/STATUS.md](docs/STATUS.md) for the full per-service breakdown.

## Auth Flow

1. Client posts credentials to `security-service` via `api-gateway`.
2. `security-service` returns a signed JWT.
3. Subsequent calls carry `Authorization: Bearer …`.
4. `api-gateway` validates and forwards. Internal service-to-service calls are
   signed using `ServiceFeignAutoConfiguration`
   ([`apps/jarvis-common/.../feign/ServiceFeignAutoConfiguration`](apps/jarvis-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports))
   issuing a service JWT (helper:
   [`scripts/runtime/make_service_jwt.py`](scripts/runtime/make_service_jwt.py)).

Reference: [docs/security/AUTH_MODEL.md](docs/security/AUTH_MODEL.md).
Token revocation note: immediate access-token cut-off is **not** implemented;
existing tokens remain valid until expiry.

## Trust Boundaries

| Boundary | Description | Enforcement |
|----------|-------------|-------------|
| External user → ingress | Browser/desktop reaches Nginx ingress on `api.jarvis.local`, `voice.jarvis.local`, `grafana.jarvis.local` | TLS termination, force-ssl-redirect, CORS allow-list |
| Ingress → api-gateway | HTTP/WS reaches gateway (cluster-internal) | Spring Security, `JwtAuthFilter`, `ServiceJwtFilter` |
| api-gateway → downstream services | Internal Feign / WebSocket proxy calls | `X-Service-Token` minted by `ServiceJwtProvider`; user `Authorization` is stripped for non-auth routes |
| security-service (auth plane) | User-auth issuer | Internal only; receives proxied auth requests with original `Authorization` preserved |
| Local-only services | `pc-control` real mode, `vision-security-service`, host model daemon | Must never be exposed via ingress |
| Observability stack | Grafana / Prometheus / Loki / Tempo / Alloy | Cluster-internal; only Grafana is exposed via ingress, behind admin login |

## Gateway Security Flow

1. Request enters `api-gateway`. `ServiceJwtFilter` runs first; if a valid `X-Service-Token` is present, it sets a Spring `Authentication` with authority `SVC_INTERNAL`.
2. `JwtAuthFilter` runs next. It is a kill-switch filter (`jarvis.jwt.enabled`); production `application.yaml` sets it `true`. The filter:
   - Skips a fixed set of public paths (`/auth/login`, `/auth/register`, `/auth/logout`, `/auth/refresh`, `/api/v1/security/auth/*`, `/actuator/health[/**]`, `/actuator/prometheus`).
   - Otherwise requires `Authorization: Bearer <access-token>`, validates issuer, type, signature, expiry.
   - Wraps the request to expose `X-User-Id`, `X-Username`, `X-User-Roles` derived from the validated JWT.
3. For proxied calls, `FeignAuthConfig.serviceAuthInterceptor` strips the original `Authorization`, sets `X-Service-Token`, and forwards `X-User-*` headers when delegated user identity is appropriate.
4. Downstream `ServiceJwtFilter` validates the service JWT. `GatewayAuthFilter` then merges delegated `X-User-*` only when the principal already has authority `SVC_INTERNAL` — external clients cannot forge user identity by sending `X-User-*` directly.
5. Internal-only Spring controllers carry `@PreAuthorize("hasAuthority('SVC_INTERNAL')")`.

## Service-to-Service Authentication

- Header: `X-Service-Token`. Issued and validated via `ServiceJwtProvider` in `apps/jarvis-common`.
- Required claims: `iss=jarvis-internal`, `aud=jarvis-services`, `svc=<service-name>`, `roles` includes `SVC_INTERNAL`, `token_type=service`.
- Secret: `service.jwt.secret` (env `SERVICE_JWT_SECRET`), defaults to `${JWT_SECRET}` if unset — this fallback should be removed for production (see [docs/security/SECURITY_HARDENING_PLAN.md](docs/security/SECURITY_HARDENING_PLAN.md), task F-002).

## Local-only Components and Observability Exposure

- `vision-security-service` — host-trust only; never reaches `k8s/base`.
- `pc-control` — real desktop control on the host; cluster runtime uses `PC_CONTROL_STUB_MODE=true`.
- Grafana is the only observability surface exposed via ingress, behind admin login. Prometheus / Loki / Tempo / Alloy are cluster-internal.
- `/actuator/health` and `/actuator/prometheus` are publicly reachable on the gateway today — see hardening item F-009.

## Kubernetes Security Assumptions

- `securityContext` on every base deployment: `runAsNonRoot: true`, non-zero UID, `seccompProfile.type=RuntimeDefault`, container-level `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop=[ALL]`.
- Kyverno enforce policies in `k8s/overlays/prod/kyverno/` cover runtime hardening, container hardening, host isolation, capability drops, disallow-latest tag, image signature verification.
- Network policies live in `k8s/base/observability/networkpolicy.yaml`, `k8s/overlays/prod/networkpolicy-baseline.yaml`, `networkpolicy-allowlist.yaml`.
- Image policy: launcher overlay (`k8s/overlays/prod`) uses mutable `:local` tags; digest-pinned overlay is `k8s/overlays/prod-release`. Use the digest-pinned overlay for production.
- `infra/k8s/overlays/prod` is a parallel MicroK8s production tree; documented drift versus `k8s/`. Pick a single source of truth before any non-LAN deployment.

## Voice Flow

1. Browser / desktop opens `wss://voice.jarvis.local`.
2. `voice-gateway` STT (vosk) → text → `nlp-service` intent → `orchestrator`.
3. `orchestrator` dispatches to `pc-control` / `smart-home-service` /
   `llm-service`.
4. Response → TTS (`espeak-ng` by default per
   [`JARVIS_CANONICAL_LOCAL_VOICE_STACK`](scripts/runtime/common.sh)) → WS frame.

Verification: [`scripts/voice-local-smoke.sh`](scripts/voice-local-smoke.sh),
[`scripts/runtime/VoiceWsScenario.java`](scripts/runtime/VoiceWsScenario.java).

## Observability Flow

`api-gateway` → Alloy collector → Loki (logs) / Prometheus (metrics) /
Tempo (traces) → Grafana. All deployed by `k8s/base/observability/`.

`jarvis-launch.sh` brings the stack up before workloads
(`ensure_observability_stack`). Verification:
[`scripts/verify-observability.sh`](scripts/verify-observability.sh).

## Data Stores

- PostgreSQL (`k8s/base/postgres/`) — main relational store; local Mode 1 uses
  a managed `pgvector/pgvector:pg16` container.
- PostgreSQL + `pgvector` (`k8s/overlays/prod/postgres-pgvector.yaml`) —
  dedicated StatefulSet for `memory-service`; default `replicas: 0`.
- File-based model storage on the host:
  `~/.jarvis/models/` (Mode 1 default) or `${PROJECT_DIR}/models` if
  `JARVIS_MODELS_DIR` is unset and `jarvis-launch.sh` is used.

## Runtime Modes

See [docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md). In short:

1. Native local processes via `scripts/runtime-up.sh`.
2. K8s mutable launcher via `./jarvis-launch.sh` (`k8s/overlays/prod`).
3. K8s digest-pinned release via `./jarvis-launch.sh --release-overlay` or
   `./scripts/product/jarvis-deploy-prod.sh` (`k8s/overlays/prod-release`).
4. K8s MicroK8s production via `./scripts/product/jarvis-deploy-microk8s-prod.sh`
   (`infra/k8s/overlays/prod`) — alternative tree, see drift notes.
5. AI overlay (optional) — `ENABLE_LLM=true ENABLE_MEMORY=true`.
6. Desktop launcher — `apps/desktop-javafx`.

## Local-only Components

- `vision-security-service` — never reaches `k8s/base`.
- `pc-control` real desktop control — same image runs in cluster with
  `PC_CONTROL_STUB_MODE=true`.

## Optional / Experimental Components

- `voice-gateway` (degrade-to-WARN unless `JARVIS_REQUIRE_VOICE_GATEWAY=true`).
- `llm-service`, `memory-service`, `embedding-service-py`, `llm-server` (deprecated wrapper).
- `apps/android-app` (Phase 12 Pass 1 Gradle scaffold; not in Maven reactor;
  no canonical APK build path).
- `mosquitto` — kept for future smart-home integration; documented as legacy
  ([phase-1-acceptance-evidence.md](docs/architecture/phase-1-acceptance-evidence.md)).

## Known Architectural Drift

Documented in detail in [docs/LEGACY_AND_CLEANUP.md](docs/LEGACY_AND_CLEANUP.md):

- Two parallel K8s trees (`k8s/` vs `infra/k8s/`).
- `jarvis-launch.sh --enable-llm` references the `llm-server` Deployment, but
  `k8s/overlays/prod/llm-server.yaml` is deleted in the working tree.
- Several service docs still reference the removed `docker/` directory paths.
- `planner-service` LLM endpoints return `501 Not Implemented`.
