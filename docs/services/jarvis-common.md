# jarvis-common

## 1. Name

`jarvis-common`

## 2. Type

Shared Java library.

## 3. Purpose

Provides reusable Spring configuration, service-to-service security helpers,
gateway-delegated user propagation, Feign defaults, and logging/PII guard utilities
used by multiple Jarvis services.

## 4. Current Reality

This module is not a standalone application. It is packaged as a dependency and consumed by other Spring Boot services in the Maven reactor.

## 5. Entry Points

- no `main` method
- Spring auto-configuration via `JarvisCommonAutoConfiguration`

## 6. Configuration

Configuration is supplied by consuming services. The code exposes shared support for:

- service JWT validation and generation
- gateway-auth propagation
- Feign client defaults
- PII-aware logging controls

## 7. API / WebSocket Surface

None. This module exposes no REST or WebSocket endpoints.

## 8. Main Internal Components

- `BaseSecurityConfig`
- `ServiceJwtFilter`
- `ServiceJwtProvider`
- `GatewayAuthFilter`
- `ServiceFeignAutoConfiguration`
- `LogSanitizer`
- `PiiLoggingGuard`

## 9. Dependencies On Other Services

None at runtime by itself. Dependencies are inverted: runtime services depend on this library.

## 10. Data / Storage

None.

## 11. Security Model

Implements the shared internal-auth contract used by downstream services:

- `X-Service-Token` is the canonical internal service-auth header
- `X-User-Id` / `X-User-Roles` carry gateway-delegated user context only after a valid service JWT
- downstream services authenticate internal callers via service JWT first, then optionally switch to the delegated user principal
- service JWTs are issued and validated locally from `service.jwt.*`; `security-service` is not in this validation path
- delegated user roles intentionally exclude internal-only authority `SVC_INTERNAL`
- rotation is single-key hard cutover only; there is no `kid`/multi-key support
- the platform-wide split is documented in `docs/security/AUTH_MODEL.md`

## 12. How To Run / Test

Build/test it through Maven:

```bash
mvn -pl apps/jarvis-common -am test
```

## 13. Implementation Status

Implemented shared library.

## 14. Known Gaps / Caveats

- Behavior is only meaningful in the context of consuming services.
- Real auth/runtime behavior must be verified in each application that imports this module.
- This module does not issue user JWTs; `security-service` remains the user-auth authority.
