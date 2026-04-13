# desktop-javafx

## 1. Name

`desktop-javafx`

## 2. Type

Unified JavaFX desktop application module.

## 3. Purpose

Provides the production desktop surface for Jarvis: launcher/runtime controls, login/auth bootstrap, shell navigation, feature views, diagnostics, WebSocket clients, and desktop-side config resolution.

## 4. Current Reality

This module replaces the former three-way split in the desktop JavaFX layer.

Single-module responsibilities now include:

- launcher/operator flow and backend bootstrap controls
- desktop login and token bootstrap
- unified shell navigation and feature routes
- HTTP API clients and WebSocket clients
- runtime-mode-aware endpoint/config selection
- diagnostics, health, AI/runtime read models, and local desktop integrations

## 5. Entry Points

- main class: `org.jarvis.launcher.LauncherApplicationKt`
- JavaFX application: `LauncherApplication`
- shell host: `org.jarvis.desktop.app.DesktopShellHost`

The launcher is now the single startup path. Opening the desktop shell happens inside the same JVM/module instead of spawning a second desktop jar process.

## 6. Configuration

Desktop/runtime configuration is resolved in code through:

- `AppConfig`
- `DesktopConfigResolver`
- `DesktopSettings`
- `JarvisPaths`
- `LauncherConfig`

Observed endpoint defaults:

- local default API gateway: `http://127.0.0.1:8080`
- remote default API gateway: `https://api.jarvis.local`
- derived WebSocket URLs:
  - `/ws/voice`
  - `/ws/pc-control`

The module also bootstraps TLS truststore usage from:

- `JARVIS_JAVA_TRUSTSTORE`
- fallback `~/.jarvis/tls/jarvis-cacerts.jks`
- fallback `~/.jarvis/certs/jarvis-truststore.jks`

## 7. API / WebSocket Surface

This module does not serve an API. It consumes Jarvis APIs via:

- gateway auth endpoints such as `/auth/login`, `/auth/register`, `/auth/refresh`
- authenticated REST calls under `/api/v1`
- WebSocket connections to `/ws/voice` and `/ws/pc-control`
- local/runtime health endpoints and launcher-oriented diagnostics sources

## 8. Main Internal Components

- `LauncherApplication`
- `DesktopShellHost`
- `ShellRoot`
- feature views under `org.jarvis.desktop.features.*`
- `ApiClient`
- `AuthService`
- `TokenManager`
- `DesktopRuntimeMonitor`
- `LocalRuntimeHealthProbe`
- `PcControlWebSocketClient`
- `VoiceWebSocketClient`
- `HealthCheckService`
- `ProcessRunner`
- `DiagnosticsCollector`
- `JarvisPaths`

## 9. Dependencies On Other Services

- `api-gateway`
- `voice-gateway` via gateway WebSocket proxy
- `security-service` indirectly through gateway auth routes
- runtime scripts such as `jarvis-launch.sh`, `jarvis-stop.sh`, and local runtime scripts

## 10. Data / Storage

Client-side settings, token state, runtime summaries, lock files, and logs live under desktop-side storage such as:

- `~/.jarvis/config/`
- `~/.jarvis/run/`
- `~/.jarvis/logs/`

## 11. Security Model

Authenticates against the gateway, stores desktop-side token state through `TokenManager`, and masks sensitive data in launcher diagnostics/log previews through `SecurityUtils`.

## 12. How To Run / Test

Build/test:

```bash
mvn -pl apps/desktop-javafx -am test
```

JavaFX run path:

```bash
mvn -f apps/desktop-javafx/pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run
```

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Some feature screens still wrap legacy tab implementations inside the unified shell, but they now live in one module and no longer cross module boundaries.
- Real runtime/launcher behavior still depends on local machine state, scripts, and backend availability.
