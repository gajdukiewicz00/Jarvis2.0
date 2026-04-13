# security-service

## 1. Name

`security-service`

## 2. Type

Backend authentication service.

## 3. Purpose

Handles user registration, login, logout, password change, token refresh/rotation,
and current-user lookup for Jarvis.

## 4. Current Reality

This is the authoritative user-auth service in the repository.

- it is the only service that issues user access/refresh JWTs
- it stores refresh-token session state and revocation
- it defines bootstrap-admin behavior
- `api-gateway` validates access tokens at the edge
- it does not issue internal service JWTs
- downstream services trust gateway-delegated user context plus service JWTs, not user refresh tokens
- the platform-wide split is documented in `docs/security/AUTH_MODEL.md`

## 5. Entry Points

- Spring Boot app: `org.jarvis.security.SecurityServiceApplication`
- REST base path: `/auth`

## 6. Configuration

Main configuration source:

- `apps/security-service/src/main/resources/application.yml`

Important settings include:

- server port `8088`
- PostgreSQL datasource
- Flyway schema `security`
- JWT secret, issuer, and expiration settings
- optional bootstrap-admin configuration

## 7. API / WebSocket Surface

REST endpoints:

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/logout`
- `POST /auth/refresh`
- `POST /auth/password/change`
- `GET /auth/me`
- `GET /auth/health`

No WebSocket endpoint.

## 8. Main Internal Components

- `AuthController`
- `AuthService`
- `JwtService`
- refresh-token persistence/revocation storage
- global exception handling for auth errors

## 9. Dependencies On Other Services

No mandatory downstream Jarvis service dependency was confirmed.

## 10. Data / Storage

Uses PostgreSQL with Flyway migrations in schema `security` for:

- `users`
- `refresh_tokens`

## 11. Security Model

- `/auth/**` is network-public at the Spring Security layer, but protected endpoints such as
  `/auth/me` and `/auth/password/change` still require a valid access token in `Authorization`
- access tokens are stateless JWTs signed with `jarvis.jwt.secret`
- refresh tokens are JWTs signed by the same user-auth secret, but also persisted server-side
- refresh uses rotation; reused rotated refresh tokens revoke the active refresh-token family
- refresh tokens without persisted server-side session state are rejected and require re-login
- logout revokes the submitted refresh token
- password change revokes all active refresh tokens for the user and issues a fresh token pair
- self-service registration only creates `USER`; admin creation is via bootstrap-admin config
- current access tokens are not introspected on every request; immediate global cut-off is done via secret rotation
- rotating `JWT_SECRET` invalidates both access and refresh JWTs signed by the old key and forces re-authentication
- disabled users cannot log in, cannot refresh, and `/auth/me` rejects them even with an old access token
- refresh attempts by disabled users revoke the remaining active refresh sessions for that user
- there is no multi-key / `kid` support; secret rotation is hard cutover only
- internal service JWTs belong to the separate `jarvis-common` trust plane, not to this service

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/security-service -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Implemented with explicit auth lifecycle.

## 14. Known Gaps / Caveats

- `api-gateway` is the edge validator for access tokens, so issuer/type rules there must stay aligned with this service.
- There is no standalone user-disable or password-reset API in this module yet; the implemented lifecycle covers register/login/refresh/me/logout/password-change.
- Existing access tokens remain usable until expiry unless the user JWT signing secret is rotated.
