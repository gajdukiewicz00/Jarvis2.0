# desktop-app-javafx

## 1. Name

`desktop-app-javafx`

## 2. Type

JavaFX desktop application.

## 3. Purpose

Provides the current shell-style desktop UI for Jarvis, combining multiple feature areas into a single desktop application.

## 4. Current Reality

This is a real application module built on top of `desktop-client-javafx` and `launcher-javafx`. It is the shell-based desktop surface with feature panes for AI, planner, diagnostics, smart home, vision, and other areas.

## 5. Entry Points

- main class: `org.jarvis.desktop.app.DesktopAppApplicationKt`
- JavaFX application: `DesktopAppApplication`

## 6. Configuration

The application bootstraps TLS trust if a local Jarvis truststore exists:

- `JARVIS_JAVA_TRUSTSTORE`
- fallback `~/.jarvis/tls/jarvis-cacerts.jks`
- fallback `~/.jarvis/certs/jarvis-truststore.jks`

It also reuses login/auth resources from `desktop-client-javafx`.

## 7. API / WebSocket Surface

This module does not serve an API. It consumes gateway-backed desktop functionality through shared desktop-client components.

## 8. Main Internal Components

- `DesktopAppApplication`
- `ShellRoot`
- shell navigation and route classes
- feature views under `features/ai`, `features/analytics`, `features/diagnostics`, `features/home`, `features/life`, `features/pccontrol`, `features/planner`, `features/settings`, `features/smarthome`, `features/vision`, `features/voice`

## 9. Dependencies On Other Services

Indirectly depends on the Jarvis backend through reused `desktop-client-javafx` network/auth code.

## 10. Data / Storage

No server-side storage. Uses local desktop-side auth/config/truststore state through shared modules.

## 11. Security Model

Uses the same desktop auth/token flow as `desktop-client-javafx`.

## 12. How To Run / Test

Build:

```bash
mvn -pl apps/desktop-app-javafx -am package
```

JavaFX run path:

```bash
mvn -pl apps/desktop-app-javafx -am javafx:run
```

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- The module depends on backend availability through shared desktop client code.
- Many capabilities are read-model/UI wrappers around backend services rather than standalone local features.
