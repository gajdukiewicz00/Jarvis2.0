# launcher-javafx

## 1. Name

`launcher-javafx`

## 2. Type

JavaFX launcher application.

## 3. Purpose

Starts/stops Jarvis runtime components, monitors health, launches the desktop UI, and exposes diagnostics from a GUI launcher.

## 4. Current Reality

This module is implemented and tightly connected to the repo’s runtime scripts. It is the GUI/operator surface for bringing up backend/runtime state rather than a backend service.

## 5. Entry Points

- main class: `org.jarvis.launcher.LauncherApplicationKt`
- JavaFX application: `LauncherApplication`
- product wrapper script: `scripts/product/jarvis-launcher.sh`

## 6. Configuration

Observed environment/config flags include:

- `JARVIS_AUTO_START`
- `JARVIS_AUTO_BOOTSTRAP`
- `JARVIS_AUTO_INSTALL_DEPS`
- `JARVIS_ENABLE_LLM`
- `JARVIS_ENABLE_MEMORY`
- `JARVIS_ENABLE_GPU`
- `JARVIS_RUNTIME_MODE`

The launcher reads and writes runtime state under `~/.jarvis/run/` and related Jarvis home paths.

## 7. API / WebSocket Surface

None. This module is a GUI/operator tool and does not expose server endpoints.

## 8. Main Internal Components

- `LauncherApplication`
- `HealthCheckService`
- `ProcessRunner`
- `DiagnosticsCollector`
- `JarvisPaths`
- log viewer and runtime/bootstrap helpers

`HealthCheckService` treats `api-gateway` and `security-service` as the minimum core baseline, with additional optional checks for services such as `voice-gateway`, `smart-home`, `life`, `analytics`, `llm-service`, and `memory-service`.

## 9. Dependencies On Other Services

- runtime scripts such as `jarvis-launch.sh`
- the backend runtime health endpoints
- desktop launch artifacts

## 10. Data / Storage

Uses local files such as:

- launcher lock under `~/.jarvis/run/`
- logs under `~/.jarvis/logs/`
- runtime summaries such as `~/.jarvis/run/last-run.json`

## 11. Security Model

No server-side auth surface. The launcher operates as a local desktop/operator application.

## 12. How To Run / Test

Build/test:

```bash
mvn -pl apps/launcher-javafx -am test
```

JavaFX run path:

```bash
mvn -pl apps/launcher-javafx -am javafx:run
```

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Health/readiness semantics are launcher-defined and intentionally simpler than a full service inventory.
- Real behavior depends on the repo scripts and local machine state.
