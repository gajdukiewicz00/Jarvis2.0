# vision-security-service

## 1. Name

`vision-security-service`

## 2. Type

Backend workstation-local security service.

## 3. Purpose

Monitors the local workstation via webcam and optional screen context to determine whether the enrolled owner is present, capture incidents, and send alerts.

## 4. Current Reality

This is a real Spring Boot service, but it is not a normal cluster service in this repository. It is wired for the local workstation runtime and desktop path. The code depends on local camera/display/tooling access.

## 5. Entry Points

- Spring Boot app: `org.jarvis.visionsecurity.VisionSecurityApplication`
- REST base path: `/api/v1/vision-security`

## 6. Configuration

Main configuration source:

- `apps/vision-security-service/src/main/resources/application.yml`

Important settings include:

- server port `8094`
- monitoring interval, debounce, and cooldown
- camera device index and capture size
- enrollment sample/quality thresholds
- verification thresholds and OpenCV-related tuning
- storage root, defaulting to `${user.home}/.jarvis/data/vision-security`
- OCR language
- SMTP recipient/from config
- GPU preference flag

## 7. API / WebSocket Surface

REST endpoints:

- `GET /api/v1/vision-security/status`
- `GET /api/v1/vision-security/config`
- `POST /api/v1/vision-security/monitoring/start`
- `POST /api/v1/vision-security/monitoring/stop`
- `POST /api/v1/vision-security/enrollment/capture`
- `POST /api/v1/vision-security/enrollment/import`
- `POST /api/v1/vision-security/enrollment/reset`
- `GET /api/v1/vision-security/incidents`
- `GET /api/v1/vision-security/incidents/{incidentId}`
- `POST /api/v1/vision-security/pipeline/capture`
- `POST /api/v1/vision-security/alerts/test`

No WebSocket endpoint.

## 8. Main Internal Components

- `VisionSecurityManager`
- `CameraCaptureService`
- `VisionPipelineService`
- `FaceVerificationService`
- `EnrollmentStore`
- `IncidentStore`
- `ScreenshotService`
- `ScreenContextService`
- `OcrService`
- `SemanticTagger`
- `EmailAlertService`
- `GpuStatusService`
- `OpenCvRuntime`

## 9. Dependencies On Other Services

- typically accessed through `api-gateway`
- depends on local webcam/OpenCV runtime
- optionally depends on local screenshot/OCR tools
- optionally depends on reachable SMTP

## 10. Data / Storage

- incident artifacts and enrollment data are stored on local disk
- default storage root: `${user.home}/.jarvis/data/vision-security`
- no database

## 11. Security Model

Protected by authenticated user context. Incident/enrollment state is user-scoped inside the service.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/vision-security-service -am test
```

Use the local runtime, not `k8s/base`:

```bash
./scripts/runtime-up.sh
```

## 13. Implementation Status

Implemented, local-only.

## 14. Known Gaps / Caveats

- Not included in `k8s/base`.
- Real behavior depends on host camera/display/tool availability.
- Screen analysis is local CLI based and may be limited outside a normal desktop session.
