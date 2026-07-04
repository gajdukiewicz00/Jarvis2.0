# Security Audit Report

## Audit Date

2026-05-08 — repo-first security audit run from `main` at commit `0d25e53`.

## Methodology

- Repo-first review. No live exploitation, no traffic capture, no destructive actions.
- Sources of evidence: code (`apps/`, `libs/`), Spring `application.yaml`/`yml`, Maven `pom.xml`, Kubernetes manifests (`k8s/`), runtime scripts (`scripts/`), `secrets/`, root `README.md`, `ARCHITECTURE.md`, existing `docs/security/`.
- Tooling: `git ls-files`, `grep`, `find`, `git check-ignore`. No new plugins added.
- Out of scope this round: live CVE lookups for declared dependency versions, runtime traffic instrumentation, formal pentest.

## Standards Used

Only official sources are used as reference points:

- OWASP ASVS v5
- OWASP Top 10:2021
- OWASP Cheat Sheet Series (Authentication, Session Management, JWT, Logging Vocabulary, Kubernetes)
- Spring Security reference documentation
- Spring Boot reference documentation
- Kubernetes Pod Security Standards (Restricted profile)
- Docker / Kubernetes official hardening notes
- RFC 7519 (JWT), RFC 6749 (OAuth 2.0)

## Executive Summary

Jarvis 2.0 has a coherent dual-plane auth model (user JWT + service JWT) with proper refresh-token rotation, BCrypt password hashing, and a working method-security pattern for internal endpoints. Kubernetes manifests apply Pod Security Restricted defaults and are gated by Kyverno enforce policies in `k8s/overlays/prod/kyverno/`. Secrets are not committed to git, and ConfigMaps do not leak credentials.

The platform is **safe enough for trusted local / LAN use** as designed. It is **not** ready for unrestricted public exposure: the gateway JWT enforcement depends on a config flag whose code-default is `false`, image tags in the launcher overlay are mutable, the observability stack is protected only by Grafana admin login, and the JWT plane has no `kid` or multi-key rotation path.

No real secret values were found inside git-tracked files. The only sensitive material on disk is in `secrets/secrets.env` (gitignored, mode `0600`) and live-verify export fixtures in `logs/exports/` (gitignored).

## Findings

| ID | Severity | Area | Finding | Risk | Recommendation | Evidence |
|----|----------|------|---------|------|----------------|----------|
| F-001 | Medium | api-gateway | `jarvis.jwt.enabled` default is `false` in `JwtAuthFilter`; production behavior depends on `application.yaml` (`enabled: true`) being loaded | If a deployment loads only `application-dev.yaml` or otherwise omits the prod default, gateway auth silently passes through every request | Make the kill switch fail-closed: change the `@Value` default to `true`, or fail startup if neither true nor false is explicitly configured | [JwtAuthFilter.java:48](../../apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java#L48), [application.yaml:144](../../apps/api-gateway/src/main/resources/application.yaml#L144), [application-dev.yaml:11](../../apps/api-gateway/src/main/resources/application-dev.yaml#L11) |
| F-002 | Medium | api-gateway | `service.jwt.secret` falls back to `${JWT_SECRET}` when `SERVICE_JWT_SECRET` is unset | A single compromised secret breaks both user and service trust planes simultaneously | Set `SERVICE_JWT_SECRET` to an independent value in `secrets.env`, or remove the fallback in `application.yaml` for prod | [application.yaml:159](../../apps/api-gateway/src/main/resources/application.yaml#L159), [secrets.example.env](../../secrets/secrets.example.env) |
| F-003 | Medium | k8s | Base deployments in `k8s/base/*/deployment.yaml` reference images without an explicit tag (e.g. `image: jarvis/api-gateway`); the prod launcher overlay sets `:local` (mutable) | Mutable tags break reproducibility and supply-chain traceability; `:local` is documented but easy to mis-deploy | Use `k8s/overlays/prod-release` (digest-pinned) for production; require Kyverno `verifyImages` and `disallow-latest` policies on any non-launcher deployment | [k8s/overlays/prod/kustomization.yaml:50-92](../../k8s/overlays/prod/kustomization.yaml), [kyverno/06-disallow-latest-tag-policy.yaml](../../k8s/overlays/prod/kyverno/06-disallow-latest-tag-policy.yaml) |
| F-004 | Medium | observability | Grafana is the only auth gate in front of the observability surface (`grafana.jarvis.local`) | If `GRAFANA_ADMIN_PASSWORD` is weak or leaks, observability stack is exposed; Prometheus/Loki/Tempo themselves have no auth | Restrict ingress to LAN; add network-policy-based separation; consider OAuth proxy or mTLS in front of Grafana for any non-local exposure | [k8s/base/ingress.yaml:60](../../k8s/base/ingress.yaml#L60), [k8s/base/observability/grafana.yaml](../../k8s/base/observability/grafana.yaml) |
| F-005 | Medium | api-gateway | CORS allows credentials with `allowed-headers: ["*"]` and origins limited to `api.jarvis.local`/`voice.jarvis.local` | Acceptable for LAN; if origins broaden by misconfiguration, `*` headers + credentials enables cross-origin token harvesting | Replace `"*"` with an explicit Authorization/Content-Type/X-Requested-With list; keep `allow-credentials` only for the two known hosts | [application.yaml:163-176](../../apps/api-gateway/src/main/resources/application.yaml#L163) |
| F-006 | Medium | api-gateway | `JwtAuthFilter.PUBLIC_PATHS` matches via `path::startsWith`. Combined with public auth/actuator prefixes, paths like `/auth/login.malicious` would skip JWT validation | The targets are themselves public auth endpoints proxied to security-service; security-service `/auth/**` is also `permitAll()`. Real impact only if a sub-path under `/auth/` is later mounted as authenticated | Switch to exact-match list (`String::equals`) or use Spring `requestMatchers` only — keep one source of truth | [JwtAuthFilter.java:51-62, 180-182](../../apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java#L51) |
| F-007 | Medium | platform | No per-request access-token revocation: disabled users keep valid access tokens until expiry; access expiry is 1 h in prod | Window-of-abuse for compromised or just-disabled accounts | Already documented in [AUTH_MODEL.md](AUTH_MODEL.md). Long-term: introspection or short-lived (≤5 min) access tokens with mandatory refresh | [AuthService.java:144-148](../../apps/security-service/src/main/java/org/jarvis/security/service/AuthService.java#L144), [JwtAuthFilter.java](../../apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java) |
| F-008 | Low | crypto | JWT plane has no `kid`, no key set, no multi-key validation; rotation is a hard cutover | Any rotation invalidates all sessions; no graceful key roll | Move to a key set with `kid` and dual-validation window before public exposure | [JwtService.java:34-53](../../apps/security-service/src/main/java/org/jarvis/security/service/JwtService.java#L34) |
| F-009 | Low | observability | Spring Boot actuator exposes `health,info,metrics,prometheus` on every service; `/actuator/health` and `/actuator/prometheus` are added to the gateway public allow-list | LAN scraping requires unauthenticated access. `info` does not include build secrets but does include git/build metadata | Restrict scraping to in-cluster network policy; do not expose `/actuator/*` through ingress | [application.yaml:115-124](../../apps/api-gateway/src/main/resources/application.yaml#L115), [SecurityConfig.java:48](../../apps/api-gateway/src/main/java/org/jarvis/apigateway/security/SecurityConfig.java#L48) |
| F-010 | Low | local fixtures | `logs/exports/live-verify-20260406-205808/desktop-auth*.env` contains real `JARVIS_ACCESS_TOKEN` and `JARVIS_REFRESH_TOKEN` | Local-only files; gitignored. If a host is shared or backed up unencrypted, tokens leak | Treat `logs/exports/` as sensitive, prune after each run; rotate `JWT_SECRET` if files leave the host | [logs/exports/live-verify-20260406-205808](../../logs/exports/live-verify-20260406-205808/), `git check-ignore` confirmed |
| F-011 | Low | api-gateway WS | `VoiceWebSocketProxyHandler` reads `X-User-Id`, `X-Username`, `X-User-Roles` from `clientSession.getHandshakeHeaders()` early, then re-overrides `X-User-Id` from `clientSession.getPrincipal().getName()` later | The override path is implementation-dependent. If a future refactor reads the early-captured value, an attacker who can talk to api-gateway directly inside the cluster could inject delegated identity | Always derive `X-User-*` from the Spring SecurityContext, never from incoming handshake headers; add unit assertion that injected `X-User-*` headers do not propagate | [VoiceWebSocketProxyHandler.java:74-249](../../apps/api-gateway/src/main/java/org/jarvis/apigateway/websocket/VoiceWebSocketProxyHandler.java) |
| F-012 | Info | dependencies | Spring Boot 3.3.13, Spring Cloud 2023.0.3, jjwt 0.12.6, Tomcat 10.1.52, Netty 4.1.131.Final, Log4j2 2.25.3 | CVE status not verified in this audit | Run `mvn org.owasp:dependency-check-maven` (or equivalent) on a CI cadence | [pom.xml:29-33, 76-95](../../pom.xml#L29) |
| F-013 | Info | docs vs runtime | Two parallel K8s trees (`k8s/`, `infra/k8s/`) with overlapping prod overlays | Drift between trees can cause one tree to be hardened while the other is not | Pick a single source of truth or document the boundary explicitly per service | [docs/LEGACY_AND_CLEANUP.md](../LEGACY_AND_CLEANUP.md), [ARCHITECTURE.md:170](../../ARCHITECTURE.md#L170) |

## Secret Scan Summary

Scope: every git-tracked file plus working-tree files outside common ignore directories.

- `secrets/secrets.env` — gitignored, mode `0600`, contains 1 real secret (`PORCUPINE_ACCESS_KEY`); not in git history. **Local-only**.
- `secrets/secrets.example.env` — tracked, placeholders only (`CHANGE-ME-…`).
- `secrets/.gitkeep` — tracked, empty marker.
- `logs/exports/live-verify-…/desktop-auth*.env` — gitignored, contains real bearer tokens from local acceptance runs.
- `k8s/overlays/prod/models-path.env`, `infra/k8s/overlays/prod/models-path.env` — tracked, contains only a path (`JARVIS_MODELS_PATH=…`), not a credential.

No private keys (`BEGIN PRIVATE KEY`, `BEGIN RSA`, `BEGIN EC`) and no certificate material were found in any tracked file. No `.pem`/`.p12`/`.jks`/`.key`/`.crt` files are tracked.

No tracked source file contains a hardcoded `password=`, `secret=`, `token=`, or `api_key=` literal — the only matches are placeholder-format references inside `application*.yaml` for environment-variable resolution and `CHANGE-ME` placeholders in `secrets.example.env`.

## Auth/JWT Review

| Check | Expected | Actual | Status |
|-------|----------|--------|--------|
| Password hashing | BCrypt/Argon2/PBKDF2 | BCrypt | OK |
| Plaintext password ever stored or logged | no | no — `AuthService` only logs `username` and outcome | OK |
| Issuer validation on JWT | required | `JwtParser.requireIssuer(issuer)` | OK |
| `token_type=access` enforced where access expected | required | `ensureTokenType(claims, "access", …)` in `JwtService` and `JwtAuthFilter#isAccessToken` | OK |
| `token_type=refresh` enforced where refresh expected | required | `validateRefreshToken` rejects non-refresh | OK |
| `jti` present | yes | UUID generated for both access and refresh | OK |
| Expiration validated | yes | jjwt validates `exp` automatically | OK |
| Refresh stored server-side | yes | `refresh_tokens` row keyed by `jti` | OK |
| Refresh rotation | yes | `AuthService.refresh` sets `replaced_by_token_id`, marks `REFRESH_ROTATED` | OK |
| Refresh reuse detection | yes | revokes whole family with reason `REFRESH_REUSE_DETECTED` | OK |
| Logout revokes refresh | yes | `AuthService.logout` | OK |
| Password change invalidates all sessions | yes | `AuthService.changePassword` calls `revokeAllRefreshTokens` | OK |
| Gateway rejects refresh as bearer | required | `JwtAuthFilter#isAccessToken` returns false → 401 | OK |
| Gateway strips `Authorization` for non-auth proxied calls | required | `FeignAuthConfig` does not propagate, replaces with `X-Service-Token` | OK |
| Gateway preserves `Authorization` for security-service auth | required | controller-level proxy keeps original header | OK |
| Downstream trusts `X-User-*` only with valid service JWT | required | `GatewayAuthFilter#isServiceAuthentication` | OK |
| `@PreAuthorize` on internal-only endpoints | required | applied to internal voice/orchestrator/planner/PC-control routes | OK |
| Access-token revocation per request | desirable | not implemented; expiry-only | Documented limitation |
| `kid` / multi-key rotation | desirable | not implemented | Documented limitation |

## K8s/Docker Review

- All in-tree Dockerfiles were removed in the working tree; service images are built via Jib (root `pom.xml` `jib.skip=true` default, overridden per service module). The Python AI workers use `Containerfile` (daemonless OCI). No `Dockerfile` is currently shipped.
- Every Spring Boot service deployment in `k8s/base/*/deployment.yaml` declares pod-level `runAsNonRoot: true`, container-level `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop=[ALL]`, and `seccompProfile.type=RuntimeDefault`. Sample evidence: `k8s/base/api-gateway/deployment.yaml`, `k8s/base/observability/grafana.yaml`, `k8s/base/mosquitto/deployment.yaml`.
- Kyverno enforce policies (`k8s/overlays/prod/kyverno/`) reject pods that miss `runAsNonRoot=true`, `RuntimeDefault` seccomp, container capability drops, missing resource requests/limits, host-namespace use, and `:latest` images. They also require image-signature verification.
- Secrets reach pods only via `secretKeyRef` or `envFrom.secretRef: jarvis-secrets`. No secrets are baked into ConfigMaps.
- Network: `k8s/base/observability/networkpolicy.yaml` plus `k8s/overlays/prod/networkpolicy-baseline.yaml` and `networkpolicy-allowlist.yaml`. Exact allow-list completeness was not exhaustively walked in this audit.
- Ingress (`k8s/base/ingress.yaml`): TLS-only, force-ssl-redirect, CORS allow-list, websocket route. Three hostnames exposed: `api.jarvis.local`, `voice.jarvis.local`, `grafana.jarvis.local`.

## API/Gateway Review

- Public endpoints (gateway): `/auth/login`, `/auth/register`, `/auth/logout`, `/auth/refresh`, the same set under `/api/v1/security/auth/*`, plus `/actuator/health`, `/actuator/health/**`, `/actuator/prometheus`. Everything else requires authentication.
- `EnableMethodSecurity` is on at the gateway and on internal services that need it.
- WebSocket endpoints (`/ws/voice`, `/ws/pc-control`) are not in the gateway public path list — handshake is gated by JWT. Allowed origins are taken from `cors.allowed-origins` (`api.jarvis.local`, `voice.jarvis.local`).
- Internal-only Spring controllers are guarded by `@PreAuthorize("hasAuthority('SVC_INTERNAL')")` (`InternalOrchestratorSmartHomeController`, `InternalPlannerVoiceNotificationController`, `PcControlInternalController`, `InternalVoiceCommandController`, `InternalVoiceNotificationController`).

## Logging/PII Review

- `AuthService` logs username on register/login/logout/refresh/password-change. It never logs raw tokens or passwords.
- `JwtAuthFilter` logs error class and message on invalid JWT but does not log the token itself.
- `SecurityUtils.kt` defines `MASK_PASSWORD_KEYS` for desktop-side log masking (referenced in `apps/desktop-javafx`). Worth re-verifying if any new logger writes sensitive HTTP headers.
- No `System.out.println` of secrets, tokens, or passwords found in `apps/`.

PII concerns are limited to `username` (free-text) and `email`-like identifiers that may appear in test fixtures (`live-verify-…@example`). No production logging of email/phone/address/SSN was found.

## Legacy/Insecure Candidates

| Item | Path | Why suspicious | Risk | Recommended action | Confidence |
|------|------|----------------|------|--------------------|------------|
| `apps/desktop-javafx/.../runtime/LocalRuntimeHealthProbe.kt` "legacy persisted endpoint" branch | desktop client | Falls back to a remembered URL when no active runtime is detected | If the remembered URL points to an attacker-controlled host, desktop probes that host | Already pinned to local/known endpoints by `DesktopConfigResolver`; document boundary, no code change | Medium |
| `mosquitto` MQTT broker | `k8s/base/mosquitto/` | Documented as legacy/optional in `ARCHITECTURE.md` | Stale broker exposed without active consumer | Keep deployment behind `replicas: 0` or namespace toggle until smart-home stack is wired | Medium |
| `infra/k8s/overlays/prod` (parallel tree) | `infra/k8s/` | Parallel manifests can drift from `k8s/overlays/prod` | Hardening applied to one tree may not apply to the other | Pick one source of truth before public exposure | Medium |
| `dev` profile for security-service / api-gateway | `DevSecurityConfig` in both | `permitAll()` chain — required for local but dangerous if shipped | If `SPRING_PROFILES_ACTIVE=dev` leaks into a deployed environment, all auth is bypassed | Add a startup guard that refuses `dev` profile when an in-cluster marker is present | Medium |
| `BOOTSTRAP_ADMIN_*` | `apps/security-service/.../BootstrapAdminInitializer.java` | Optional bootstrap admin path | Wrong combination of envvars could create an admin account | Already enforces ≥12 char password and explicit non-default; keep `BOOTSTRAP_ADMIN_ENABLED=false` in prod | High (already mitigated in code) |

## Not Verified

- CVE status of declared dependency versions (Spring Boot 3.3.13, jjwt 0.12.6, Tomcat 10.1.52, Netty 4.1.131.Final, Log4j2 2.25.3, Spring Cloud 2023.0.3, Jackson via Spring BOM). The audit did not run `dependency-check`.
- Runtime traffic shape between api-gateway and downstream services. WebSocket header re-injection (F-011) and any actual handshake-header leakage was not exercised.
- `infra/k8s/overlays/prod` parity with `k8s/overlays/prod`. A line-by-line diff was out of scope.
- Network-policy completeness against the canonical service list.
- Behavior of `PUBLIC_PATHS` startsWith match (F-006) under unusual servlet-container path normalization.
- TLS material on disk in `~/.jarvis/tls/` — host-local, not visible to repo audit.

## Recommended Fix Plan

See [SECURITY_HARDENING_PLAN.md](SECURITY_HARDENING_PLAN.md) for the prioritized backlog.

Headlines:

- **P0**: F-001 (gateway kill switch fail-closed), F-002 (independent service JWT secret), F-005 (CORS headers tightening) before any non-LAN exposure; F-007 short-lived access tokens or introspection if disabled-account abuse is in scope.
- **P1**: F-003 / F-013 (single image-tag and k8s tree story), F-006 (exact-match public paths), F-009 (no actuator via ingress), F-011 (drop handshake-header trust).
- **P2**: F-008 (multi-key JWT rotation), F-012 (CI dependency scan, SBOM, container scanning), pre-commit secret scanning, signed images, security regression tests.
