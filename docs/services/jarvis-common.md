# jarvis-common

## 1. Name

`jarvis-common`

## 2. Type

Shared Java library.

## 3. Purpose

Provides reusable Spring configuration, service-to-service security helpers, Feign defaults, and logging/PII guard utilities used by multiple Jarvis services.

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

Implements shared service-to-service auth helpers and common Spring Security building blocks used by downstream services.

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
