# Security Overview

This document is the canonical Jarvis 2.0 security overview. It must reflect the current state of code, configuration, and Kubernetes manifests, not aspirational design.

Companion documents:

- [docs/security/AUTH_MODEL.md](AUTH_MODEL.md) — formal user-auth and service-auth model
- [docs/security/SECRETS_POLICY.md](SECRETS_POLICY.md) — what may and may not be committed
- [docs/security/SECURITY_AUDIT.md](SECURITY_AUDIT.md) — most recent repo-first security audit
- [docs/security/SECURITY_HARDENING_PLAN.md](SECURITY_HARDENING_PLAN.md) — P0/P1/P2 hardening backlog
- [docs/security/SECURITY_COMPONENT_STATUS.md](SECURITY_COMPONENT_STATUS.md) — per-component security status table

## Scope

Jarvis is currently a single-tenant, primarily local-first personal-assistant platform. The threat model assumes:

- The owner runs the cluster locally (MicroK8s, single host) or on workstation-class hardware.
- The desktop, voice, and PC-control planes operate in trusted local context.
- The HTTPS edge (`api.jarvis.local`, `voice.jarvis.local`, `grafana.jarvis.local`) is intended for LAN-only use unless explicitly hardened for public exposure.
- `vision-security-service` and `pc-control` real-control mode are local-only and must not be exposed publicly.

## Trust Boundaries

| Boundary | Description | Edge enforcement |
|----------|-------------|------------------|
| External user → ingress | Browser/desktop reaches Nginx ingress | TLS termination, ssl-redirect, CORS allow-list |
| Ingress → api-gateway | HTTP/WS reaches gateway (cluster-internal) | Spring Security, JwtAuthFilter, ServiceJwtFilter |
| api-gateway → downstream services | Internal Feign / WebSocket proxy calls | `X-Service-Token` minted by `ServiceJwtProvider`; user `Authorization` is stripped for non-auth routes |
| security-service (auth plane) | User auth issuer | Internal only; receives proxied auth requests with original `Authorization` preserved |
| Local-only services | `pc-control` (real mode), `vision-security-service`, host model daemon | Must never be exposed via ingress |
| Observability stack | Grafana / Prometheus / Loki / Tempo | Cluster-internal; only Grafana is exposed via ingress, behind admin login |

## Authentication

Provider: `apps/security-service`.

| Endpoint | Public? | Behavior |
|----------|---------|----------|
| `POST /auth/register` | yes | Create user, return access+refresh JWT pair |
| `POST /auth/login` | yes | Verify password (BCrypt), return JWT pair |
| `POST /auth/refresh` | yes | Validate refresh JWT and server-side `refresh_tokens` row, rotate, detect reuse |
| `POST /auth/logout` | yes | Mark refresh token revoked (`USER_LOGOUT`) |
| `GET  /auth/me` | bearer | Resolve user from access token; reject disabled accounts |
| `POST /auth/password/change` | bearer | BCrypt check current password, encode new, revoke all refresh tokens (`PASSWORD_CHANGED`) |

Password hashing: `org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder` ([apps/security-service/src/main/java/org/jarvis/security/config/PasswordConfig.java](../../apps/security-service/src/main/java/org/jarvis/security/config/PasswordConfig.java)).

Bootstrap admin (optional, [apps/security-service/src/main/java/org/jarvis/security/config/BootstrapAdminInitializer.java](../../apps/security-service/src/main/java/org/jarvis/security/config/BootstrapAdminInitializer.java)):

- Disabled by default (`BOOTSTRAP_ADMIN_ENABLED=false`).
- Requires password ≥12 chars and non-default — bootstrap fails fast otherwise.

## Authorization

- Spring Security is enabled on every Spring Boot service via either `apps/security-service/.../SecurityConfig`, `apps/api-gateway/.../security/SecurityConfig`, or `apps/jarvis-common/.../BaseSecurityConfig`.
- Method-level `@PreAuthorize("hasAuthority('SVC_INTERNAL')")` is applied to internal-only endpoints in `orchestrator`, `voice-gateway`, `planner-service`, `api-gateway` (PC-control internal route).
- User roles (`USER`, `ADMIN`) flow as JWT `roles` / `role` claims, are emitted as `X-User-Roles` to downstream services, and are merged into the Spring `Authentication` only when a valid `X-Service-Token` is also present (see [GatewayAuthFilter](../../apps/jarvis-common/src/main/java/org/jarvis/common/security/GatewayAuthFilter.java)).

## Token Model

Implementation: `apps/security-service/src/main/java/org/jarvis/security/service/JwtService.java`.

| Property | Access token | Refresh token |
|----------|--------------|----------------|
| Sign alg | HMAC-SHA (`Keys.hmacShaKeyFor`) | same |
| Secret | `jarvis.jwt.secret` (env `JWT_SECRET`) | same |
| Expiry | `jarvis.jwt.access-expiration` (1h prod default) | `jarvis.jwt.refresh-expiration` (7d prod default) |
| Issuer | `jarvis` | `jarvis` |
| `jti` | random UUID | random UUID, used as PK in `refresh_tokens` |
| Type claim | `type=access`, `token_type=access` | `type=refresh`, `token_type=refresh` |
| Server-side state | none (stateless validation) | row in `refresh_tokens` (rotation, reuse detection) |
| `kid` / multi-key | not implemented | not implemented |

JWT validation (`JwtAuthFilter` in api-gateway, `JwtService.parseClaims` in security-service):

- Reject mismatched token type (refresh JWT cannot pass as access).
- Reject expired tokens.
- Reject signature failures, malformed JWTs, missing/invalid issuer.
- Always require explicit token type — does not accept tokens with neither `type` nor `token_type` claim.

## Refresh Token Lifecycle

Implementation: `apps/security-service/src/main/java/org/jarvis/security/service/AuthService.java` (`refresh`, `logout`, `changePassword`).

- Refresh issuance persists a row in `refresh_tokens` (`token_id` = JWT `jti`).
- On `POST /auth/refresh`:
  - Reject if user disabled (also revokes all active refresh sessions for that user).
  - Reject if `token_id` not found, already revoked, or expired.
  - **Reuse detection**: if the submitted refresh token was already revoked AND replaced (`replaced_by_token_id` set), revoke the entire active family (`REFRESH_REUSE_DETECTED`).
  - Otherwise: rotate — mark old as `REFRESH_ROTATED`, issue and persist a new refresh JWT.
- `POST /auth/logout` revokes only the submitted refresh token (`USER_LOGOUT`).
- `POST /auth/password/change` revokes all active refresh tokens for the user (`PASSWORD_CHANGED`) and issues a fresh pair.

Known limitation: existing access tokens remain usable until expiry — there is no per-request introspection at the gateway. Documented in [AUTH_MODEL.md](AUTH_MODEL.md).

## Service-to-Service Authentication

Implementation: `apps/jarvis-common/src/main/java/org/jarvis/common/security/ServiceJwtProvider.java`, `ServiceJwtFilter.java`, `GatewayAuthFilter.java`.

- Service tokens are minted locally by each runtime service from `service.jwt.secret` (env `SERVICE_JWT_SECRET`, falls back to `JWT_SECRET` if not set).
- Header: `X-Service-Token`. Required claims: `iss=jarvis-internal`, `aud=jarvis-services`, `svc=<service-name>`, `roles` includes `SVC_INTERNAL`, `token_type=service`.
- `ServiceJwtFilter` populates a Spring `Authentication` for downstream services when the token validates.
- `GatewayAuthFilter` only honors `X-User-Id` / `X-User-Roles` headers if the request already has a valid `SVC_INTERNAL` authentication. External clients cannot forge user identity by sending `X-User-*` headers — the headers are ignored without a service JWT.
- For proxied calls to non-`security-service` downstreams, api-gateway strips the user `Authorization` and adds `X-Service-Token` plus delegated `X-User-*` headers.
- For proxied calls to `security-service` auth endpoints, api-gateway preserves the user `Authorization` and does not add `X-Service-Token`.

## WebSocket Security

Endpoints: `/ws/voice`, `/ws/pc-control` on api-gateway ([WebSocketConfig](../../apps/api-gateway/src/main/java/org/jarvis/apigateway/websocket/WebSocketConfig.java)).

- Allowed origins are taken from `cors.allowed-origins` and applied as origin patterns.
- WebSocket handshake passes through Spring Security and `JwtAuthFilter`; non-auth paths require a valid access token.
- Internal hop api-gateway → voice-gateway:
  - The user `Authorization` header is stripped (`VoiceWebSocketProxyHandler.afterConnectionEstablished`).
  - `X-User-Id` is set from `clientSession.getPrincipal().getName()` (i.e. the JWT subject), not from arbitrary client headers.
  - `X-Service-Token` is added with `svc=api-gateway`, `roles=SVC_INTERNAL`.
- Voice-gateway internal endpoints (`InternalVoiceCommandController`, `InternalVoiceNotificationController`) require `hasAuthority('SVC_INTERNAL')`.

## Local-only Security Boundaries

These components MUST NOT be exposed publicly:

- `pc-control` real-control mode — controls keyboard, mouse, file system on the host. In cluster runtime it must run with `PC_CONTROL_STUB_MODE=true`. Only internal endpoints are gated by `hasAuthority('SVC_INTERNAL')`.
- `vision-security-service` — owner verification camera; absent from `k8s/base` deliberately.
- Host-model daemon — the local LLM runtime that backs `llm-service` in production.

## Kubernetes Security

Manifest source of truth:

- `k8s/base/` — base templates.
- `k8s/overlays/prod/` — primary launcher overlay (mutable image tags).
- `k8s/overlays/prod-release/` — digest-pinned release overlay.
- `infra/k8s/overlays/prod/` — alternative MicroK8s production tree (drift, see [docs/LEGACY_AND_CLEANUP.md](../LEGACY_AND_CLEANUP.md)).

What is enforced today:

- `securityContext` on every base deployment: `runAsNonRoot: true`, non-zero UID/GID, `seccompProfile.type=RuntimeDefault`, container-level `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop=[ALL]`.
- Kyverno policies in `k8s/overlays/prod/kyverno/` enforce runtime hardening, container hardening, host isolation, core resource requests/limits, capability drops, ban on `:latest`, image signature verification.
- Network policies: `k8s/base/observability/networkpolicy.yaml` plus `k8s/overlays/prod/networkpolicy-baseline.yaml` and `networkpolicy-allowlist.yaml`.
- Secrets: loaded as Kubernetes `Secret/jarvis-secrets`; deployments consume them via `envFrom.secretRef` or `secretKeyRef`. Secrets are never embedded in `ConfigMap`.

Image policy in `k8s/overlays/prod/kustomization.yaml`:

- Core Jarvis images: `localhost:5000/jarvis/<service>:local` — mutable tag. The Kyverno `disallow-latest` policy permits `:local` only because it is exempted by the launcher overlay; the digest-pinned variants are in `prod-release`.
- AI stack images (`llm-service`, `embedding-service`, `memory-service`) carry pinned `release-*` tags.

## Observability Security

- Grafana is the only observability surface exposed via ingress (`grafana.jarvis.local`). Authentication is the standard Grafana admin login backed by `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` from `jarvis-secrets`.
- Prometheus, Loki, Tempo, Alloy are cluster-internal services; they have no ingress entry.
- Spring Boot actuator exposure on every service: `health, info, metrics, prometheus`. `/actuator/health` and `/actuator/prometheus` are explicitly added to the gateway public path list to allow LAN scraping.
- Tracing exporter is OTLP HTTP at `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces` — no credentials assumed in local mode.

## Secrets Handling

Canonical policy: [docs/security/SECRETS_POLICY.md](SECRETS_POLICY.md).

Repository-side guarantees:

- `secrets/` is gitignored except `secrets/.gitkeep` and `secrets/secrets.example.env` (placeholders only).
- `~/.jarvis/secrets/secrets.env` is the authoritative local secrets file; `scripts/product/jarvis-secrets-apply.sh` materializes it as the `jarvis-secrets` Kubernetes Secret.
- TLS material lives in `~/.jarvis/tls/` and is applied by `scripts/product/jarvis-apply-edge-tls-secret.sh` (and the per-service `jarvis-apply-internal-tls-*` scripts).
- Local exports of JWT material under `logs/exports/<run>/desktop-auth*.env` are debug fixtures from acceptance/verify runs. They are gitignored. The contents are real bearer tokens and must not be shared. **Operator action**: rotate `JWT_SECRET` after any incident that exposes these files.

## Known Risks

The following are accepted limitations rather than production-grade controls. Each is tracked in [SECURITY_HARDENING_PLAN.md](SECURITY_HARDENING_PLAN.md).

| Risk | Status |
|------|--------|
| Single HMAC key per plane, no `kid`, no multi-key validation | accepted; rotation is a hard cutover |
| Access tokens not introspected per request — disabled users keep working tokens until expiry | documented in AUTH_MODEL.md |
| `SERVICE_JWT_SECRET` defaults to `JWT_SECRET` if not set explicitly | accepted in local mode; production should set a distinct value |
| `jarvis.jwt.enabled=false` is the in-code default; it is set to `true` only by `application.yaml` of api-gateway | misconfigured deployment that does not load this profile would bypass gateway auth |
| Mutable image tag `:local` for core Jarvis images in `k8s/overlays/prod` | use `prod-release` digest-pinned overlay for production |
| Grafana admin is the only auth on the observability surface | acceptable for LAN; not safe for public exposure |
| CORS allow-credentials with `*` headers | acceptable for LAN; explicit per-route hardening would be safer |
| Two parallel K8s trees (`k8s/`, `infra/k8s/`) | drift risk — one source of truth required before public exposure |

## Hardening Roadmap

See [SECURITY_HARDENING_PLAN.md](SECURITY_HARDENING_PLAN.md). Priorities:

- **P0** — must fix before any public/production exposure.
- **P1** — recommended before broader LAN deployment.
- **P2** — defense-in-depth and supply-chain.
