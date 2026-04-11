# pc-control

## 1. Name

`pc-control`

## 2. Type

Backend desktop/system control service.

## 3. Purpose

Executes host-level desktop, window, browser, input, system, and file actions for Jarvis.

## 4. Current Reality

This service is real and implemented for a Linux workstation. In Kubernetes it is intentionally deployed in stub mode, so cluster deployment does not imply real desktop control.

## 5. Entry Points

- Spring Boot app: `org.jarvis.pccontrol.PcControlApplication`
- REST base path: `/api/v1/pc`

## 6. Configuration

Main configuration source:

- `apps/pc-control/src/main/resources/application.yml`

Important settings include:

- server port `8084`
- `PC_CONTROL_STUB_MODE`
- allowed desktop/system actions
- shared file root
- optional RabbitMQ/Kafka toggles, disabled by default

## 7. API / WebSocket Surface

REST endpoints:

- `POST /api/v1/pc/action`
- `GET /api/v1/pc/actions`
- `GET /api/v1/pc/desktop/apps`
- `POST /api/v1/pc/desktop/apps/open`
- `POST /api/v1/pc/desktop/url/open`
- `POST /api/v1/pc/desktop/file/open`
- `GET /api/v1/pc/desktop/system-info`
- `GET /api/v1/pc/desktop/volume`
- `POST /api/v1/pc/desktop/volume`
- `POST /api/v1/pc/desktop/window/focus`
- `GET /api/v1/pc/desktop/window/active`
- `GET /api/v1/pc/desktop/window/list`
- `POST /api/v1/pc/desktop/input/keys`
- `POST /api/v1/pc/desktop/input/click`
- `POST /api/v1/pc/desktop/input/move`
- `POST /api/v1/pc/desktop/input/scroll`
- `GET /api/v1/pc/desktop/capabilities`
- `GET /api/v1/pc/files/list`
- `GET /api/v1/pc/files/content`
- `GET /api/v1/pc/files/info`

Desktop execution acknowledgements are routed through `api-gateway` WebSocket `/ws/pc-control`, not through a PC-control-owned WebSocket endpoint.

## 8. Main Internal Components

- `PcActionExecutionService`
- `PcScenarioRegistry`
- `LinuxDesktopControlService`
- `LinuxAudioControl`
- `LinuxBrowserControl`
- `LinuxWindowControl`
- `LinuxInputControl`
- `LinuxFileService`
- `LinuxSystemControlService`
- `StubDesktopControlService`
- `StubSystemControlService`

## 9. Dependencies On Other Services

No mandatory downstream Jarvis service dependency for core actions. Real execution depends on local OS capabilities and external desktop tools.

## 10. Data / Storage

- filesystem access under the configured shared root
- no primary database

## 11. Security Model

Protected by the normal internal auth model. User-scoped desktop delivery is handled through `api-gateway` session tracking.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/pc-control -am test
```

For real desktop control, use the local runtime:

```bash
./scripts/runtime-up.sh
```

## 13. Implementation Status

Implemented locally; intentionally stubbed in Kubernetes.

## 14. Known Gaps / Caveats

- Linux/workstation oriented.
- Kubernetes deployment sets `PC_CONTROL_STUB_MODE=true`.
- Real behavior depends on host permissions and available desktop tooling.
