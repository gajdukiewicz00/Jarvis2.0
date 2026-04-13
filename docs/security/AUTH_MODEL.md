# Jarvis Authentication Model

Jarvis currently uses a formal dual-plane security model.

## 1. User Auth Plane

Authority:

- issuer: `security-service`
- token service: `apps/security-service/src/main/java/org/jarvis/security/service/JwtService.java`
- edge validator: `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java`
- self-validator for `/auth/me` and `/auth/password/change`: `security-service`

Canonical contract:

- user bearer header: `Authorization: Bearer <access-token>`
- refresh token transport: request body to `/auth/refresh` and `/auth/logout`
- access-token type: `type=access` / `token_type=access`
- refresh-token type: `type=refresh` / `token_type=refresh`

Semantics:

- access tokens are stateless JWTs signed with `jarvis.jwt.secret`
- refresh tokens are JWTs plus persisted server-side session state in `refresh_tokens`
- refresh uses rotation
- reuse of a rotated refresh token revokes the active refresh-token family
- logout revokes the submitted refresh token
- password change revokes all active refresh tokens and issues a fresh token pair
- access tokens are not introspected on each downstream request
- immediate access-token cut-off is not implemented; existing access tokens remain usable until expiry unless the user JWT secret is rotated

Disabled-user semantics:

- disabled users cannot log in
- disabled users cannot refresh
- `/auth/me` and `/auth/password/change` reject disabled users even with a structurally valid access token
- when a disabled user presents a refresh token, `security-service` revokes the remaining active refresh sessions for that user
- existing access tokens already accepted by `api-gateway` remain usable at the edge until expiry because the gateway does not query user state per request

Secret rotation:

- single active HMAC key only
- no `kid`
- no multi-key verification path
- rotating `JWT_SECRET` is a hard cutover for both access and refresh JWT validation

## 2. Internal Service Trust Plane

Authority:

- issuer/validator contract: `jarvis-common`
- token service: `apps/jarvis-common/src/main/java/org/jarvis/common/security/ServiceJwtProvider.java`
- validator filter: `apps/jarvis-common/src/main/java/org/jarvis/common/security/ServiceJwtFilter.java`

Canonical contract:

- service bearer header: `X-Service-Token: <service-jwt>`
- delegated user context:
  - `X-User-Id`
  - `X-Username` when available
  - `X-User-Roles`
- service token claim contract:
  - `token_type=service`
  - `iss=service.jwt.issuer` (default `jarvis-internal`)
  - `aud=service.jwt.audience` (default `jarvis-services`)
  - `svc=<service-name>`
  - `roles` includes `SVC_INTERNAL`

Semantics:

- internal service JWTs are not issued by `security-service`
- runtime services mint them locally from `service.jwt.secret`
- downstream services authenticate service JWT first, then may switch to delegated user principal via `GatewayAuthFilter`
- delegated user roles must represent user roles only; internal-only authority `SVC_INTERNAL` is not part of delegated user identity

Secret rotation:

- single active HMAC key only
- no `kid`
- no multi-key verification path
- rotating `SERVICE_JWT_SECRET` is a hard cutover for internal service JWT validation

## 3. Plane Boundaries

Gateway rules:

- `api-gateway` accepts user access tokens in `Authorization`
- `api-gateway` rejects refresh tokens as user bearer auth
- for proxied calls to non-`security-service` downstream services, the gateway strips user `Authorization`, issues `X-Service-Token`, and forwards delegated `X-User-*`
- for proxied calls to `security-service` auth endpoints, the gateway preserves user `Authorization` and does not add `X-Service-Token`

Downstream rules:

- internal service endpoints must trust `X-Service-Token`, not `Authorization`
- delegated user context is only meaningful together with a valid `X-Service-Token`
- a valid service JWT presented in `Authorization` is outside contract and must not authenticate internal service calls

## 4. WebSocket Semantics

- external desktop/client connections authenticate to `api-gateway` on the WebSocket handshake via the user-auth plane
- the internal `api-gateway` -> `voice-gateway` hop uses the internal service trust plane:
  - user `Authorization` is stripped
  - gateway injects `X-Service-Token`
  - gateway forwards delegated `X-User-*`

## 5. Current Truthful Statement

The platform is not unified under a single auth authority today.

The truthful production-readable statement is:

- `security-service` is the authoritative user-auth plane
- `jarvis-common` is the authoritative internal service trust plane
