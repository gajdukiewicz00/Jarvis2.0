# security-service

## 1. Name

`security-service`

## 2. Type

Backend authentication service.

## 3. Purpose

Handles user registration, login, token refresh, and current-user lookup for Jarvis.

## 4. Current Reality

This is the authoritative JWT issuing service in the repository. Downstream auth validation mostly happens elsewhere, especially in `api-gateway`, but token issuance and user auth logic live here.

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
- `POST /auth/refresh`
- `GET /auth/me`
- `GET /auth/health`

No WebSocket endpoint.

## 8. Main Internal Components

- `AuthController`
- `AuthService`
- JWT/token support classes
- global exception handling for auth errors

## 9. Dependencies On Other Services

No mandatory downstream Jarvis service dependency was confirmed.

## 10. Data / Storage

Uses PostgreSQL with Flyway migrations in schema `security` for user/auth-related persistence.

## 11. Security Model

- `/auth/**` is explicitly `permitAll`
- the service itself is stateless
- JWT secrets and expirations are configured here

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/security-service -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- This service issues tokens, but it is not the only place where auth behavior matters; gateway and downstream service security config still shape real request behavior.
