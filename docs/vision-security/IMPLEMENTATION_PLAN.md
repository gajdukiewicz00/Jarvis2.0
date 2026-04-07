# Implementation Plan

## Implemented

The MVP was implemented as a new isolated module instead of extending unrelated services.

### Backend

- New module: `apps/vision-security-service`
- Standalone Spring Boot API for status, enrollment, monitoring, incidents, pipeline export, and test alerts
- OpenCV-based webcam capture and face detection
- LBPH owner verification with stored grayscale enrollment samples
- explicit debounce/cooldown decision engine
- screenshot, OCR, active window/process collection, and semantic tagging
- incident persistence with evidence directories and retention pruning
- SMTP alert delivery
- actuator health exposure

### Integration

- Root Maven module registration
- runtime script registration for local startup and shutdown
- `api-gateway` REST proxy endpoints under `/api/v1/vision-security`
- shell-native `desktop-app-javafx` Vision Security page and read model

### Test Coverage

- `MonitoringDecisionEngineTest`
- `SemanticTaggerTest`
- `VisionSecurityPropertiesBindingTest`
- `IncidentStoreTest`

## Module / File Map

### New backend module

- `apps/vision-security-service/pom.xml`
- `apps/vision-security-service/src/main/java/org/jarvis/visionsecurity/...`
- `apps/vision-security-service/src/main/resources/application.yml`
- `apps/vision-security-service/src/main/resources/cascade/haarcascade_frontalface_default.xml`
- `apps/vision-security-service/src/test/java/org/jarvis/visionsecurity/...`

### Existing modules updated

- `pom.xml`
- `apps/api-gateway/src/main/resources/application.yaml`
- `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/VisionSecurityClient.java`
- `apps/api-gateway/src/main/java/org/jarvis/apigateway/controller/VisionSecurityProxyController.java`
- `apps/desktop-app-javafx/src/main/kotlin/org/jarvis/desktop/features/vision/...`
- `apps/desktop-app-javafx/src/main/kotlin/org/jarvis/desktop/shell/...`
- `apps/desktop-app-javafx/src/main/resources/css/shell.css`
- `scripts/runtime/common.sh`
- `scripts/runtime-up.sh`
- `scripts/runtime-down.sh`
- `scripts/runtime-status.sh`
- `README.md`
- `docs/architecture.md`

## Design Decisions

### Separate service instead of extending `pc-control`

Reason: `pc-control` already owns desktop command execution. Vision security needs its own scheduler, evidence storage, CV pipeline, enrollment lifecycle, and incident model.

### Haar cascade + LBPH instead of a heavier neural stack

Reason: the MVP needed a reproducible local Ubuntu path, a sane CPU baseline, and explainable classical-CV outputs for the course requirement. Haar + LBPH is not state of the art, but it fits the existing repo and produces report-friendly intermediate stages.

### Binary service decision model

Reason: the product requirement was intentionally narrow. The service emits only:

- `OWNER_PRESENT`
- `UNKNOWN_PERSON`
- `NO_FACE`
- `UNCERTAIN`

This keeps UI, incidents, and alert logic simple.

### Rule-based semantic tags

Reason: the MVP needed screen-context enrichment without depending on a local VLM. Tagging stays transparent and testable.

### Local evidence-first design

Reason: the user asked for their own-device monitoring feature. Incidents are persisted locally first, then email is treated as an optional delivery step.

## Why This Architecture Fits Jarvis

- Follows the repo’s multi-service Maven layout
- Reuses the gateway proxy pattern already used for other services
- Reuses shell-native desktop read-model/view structure
- Fits local runtime script conventions instead of inventing a new launcher
- Keeps desktop-native dependencies confined to one service

## Intentionally Left For V2

- spoof / presentation-attack detection
- better multi-person identity handling beyond “owner present wins”
- richer Wayland-native active-window support
- GPU-accelerated inference path
- non-email alert channels
- optional local VLM enrichment
