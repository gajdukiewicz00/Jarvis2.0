# desktop-client-javafx

## 1. Name

`desktop-client-javafx`

## 2. Type

JavaFX desktop application/module.

## 3. Purpose

Provides a tab-based desktop client and a reusable desktop integration layer for auth, API access, runtime monitoring, and WebSocket clients.

## 4. Current Reality

This module is a real build target and produces a shaded desktop jar. It also acts as a dependency of `desktop-app-javafx`, so it is both an application and a reusable desktop module.

## 5. Entry Points

- main class: `org.jarvis.desktop.DesktopApplicationKt`
- JavaFX application: `DesktopApplication`

## 6. Configuration

Important configuration is resolved in code through:

- `AppConfig`
- `DesktopConfigResolver`
- `DesktopSettings`

Observed endpoint defaults:

- local default API gateway: `http://127.0.0.1:8080`
- remote default API gateway: `https://api.jarvis.local`
- derived WebSocket URLs:
  - `/ws/voice`
  - `/ws/pc-control`

The module also contains wake-word support dependencies through Picovoice Porcupine.

## 7. API / WebSocket Surface

This module does not serve an API. It consumes Jarvis APIs:

- auth via gateway paths such as `/auth/login`, `/auth/register`, `/auth/refresh`
- authenticated API calls under `/api/v1`
- WebSocket connections to `/ws/voice` and `/ws/pc-control`

## 8. Main Internal Components

- `DesktopApplication`
- `ApiClient`
- `AuthService`
- `TokenManager`
- `DesktopRuntimeMonitor`
- `LocalRuntimeHealthProbe`
- `PcControlWebSocketClient`
- tab UIs for home, voice, devices, PC control, life, analytics, and settings

## 9. Dependencies On Other Services

- `api-gateway`
- `voice-gateway` via gateway WebSocket proxy
- `security-service` indirectly through gateway auth routes

## 10. Data / Storage

Client-side settings, token, and runtime resolution are handled locally in desktop-side code. No server-side storage is owned by this module.

## 11. Security Model

Authenticates against the gateway and keeps desktop-side auth state through `TokenManager`.

## 12. How To Run / Test

Build/test:

```bash
mvn -pl apps/desktop-client-javafx -am test
```

JavaFX run path:

```bash
mvn -pl apps/desktop-client-javafx -am javafx:run
```

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- This module overlaps with `desktop-app-javafx`; it is not the only desktop surface in the repo.
- Wake-word behavior depends on external model assets and environment setup.
