# ADR-0011: Computer Vision capture/analysis split

## Status

Accepted (Phase 10).

## Context

`vision-security-service` was already substantial at Phase 0:
~3K LOC of OpenCV / tesseract / face-enrollment code, real
incident JSON storage, and 27 tests we did not want to break. It runs
inside MicroK8s today.

SPEC-1 § "Native Desktop Agent" splits CV responsibilities:

* **Capture** lives on the host (microphone, webcam, screen, OCR
  hooks) — direct hardware access is needed and the orchestrator should
  not see raw frames cross the cluster boundary unsupervised.
* **Analysis / metadata / incident storage** stays in the
  vision-security-service pod — that's where pgvector / OpenCV / the
  face DB / OCR live.

Phase 10 also requires a Kafka event flow for vision activity, a raw
frame retention policy (don't keep frames forever), and a demo-mode
gate for non-owner contexts.

## Decision

We add a new package `org.jarvis.vision.phase10/` inside
`vision-security-service` and a small Kotlin client in the desktop
agent. **Zero changes** to the existing `CameraCaptureService`,
`FaceVerificationService`, `OcrService`, or `IncidentStore` — they keep
working as-is.

### New surfaces

| Component | Lives in | Purpose |
| --- | --- | --- |
| `DesktopFrameIngestController` | vision-security-service | `POST /api/v1/vision/frames` — capture-side metadata ingest |
| `VisionStatusController` | vision-security-service | `GET /api/v1/vision/status` — desktop-panel surface |
| `VisionEventEmitter` | vision-security-service | wraps `AuditPublisher` to emit to `jarvis.vision.events` (and mirror to `jarvis.audit.events` for privileged ops) |
| `RawFrameRetentionScheduler` | vision-security-service | `@Scheduled` walks `~/.jarvis/data/vision-security/users` and purges frames older than `retention.days` |
| `DemoModeProperties` | vision-security-service | mirrors orchestrator's `jarvis.demo-mode.enabled` flag |
| `VisionUploadClient.kt` | desktop-agent | `OkHttp` client → `POST /api/v1/vision/frames`; emits `CV_EVENT_RECEIVED` to `AgentLiveFeed` |

### Event flow

```
desktop agent (host)
   │  capture (existing JavaFX / Porcupine / xdotool path)
   │
   ▼  POST /api/v1/vision/frames (metadata only in Pass 1)
DesktopFrameIngestController ──▶ VisionEventEmitter
   │                                     │
   │                                     ├─▶ jarvis.vision.events (Kafka)
   │                                     └─▶ jarvis.audit.events (when privileged)
   │
   ├──▶ existing pipeline (face / OCR / incidents)
   └──▶ Postgres metadata (existing IncidentStore + V2 memory_chunk)
```

The retention sweeper closes the loop: it deletes raw artifacts older
than 7 days (default) and emits a single `VISION_FRAMES_PURGED`
summary per sweep — no individual filenames cross the bus, so
analytics consumers never see private paths.

### Demo-mode gate

`DesktopFrameIngestController` refuses with HTTP 403 when
`jarvis.demo-mode.enabled=true`, emitting a
`VISION_DEMO_MODE_BLOCK` audit row. Read-only endpoints (status, list
incidents) keep working so the desktop panel still renders.

### Audit dual emit

`VisionEventEmitter` deliberately publishes BOTH to
`jarvis.vision.events` AND `jarvis.audit.events` for privileged
actions (face enrollment, incident detection, demo-mode block,
retention sweep). The vision topic feeds analytics + the live panel;
the audit topic feeds the Phase 8 projector → Postgres `audit_events`
so an off-host analyst sees the same record.

## Consequences

* The existing 27 vision-security-service tests are untouched and keep
  passing — Phase 10 is strictly additive.
* The desktop agent gains one more sidecar client; the JavaFX shell
  can plug it into wake-word / screenshot triggers in Phase 6 Pass 2
  without touching the Phase 10 wire.
* Frame retention runs at startup + every hour by default. Disabled
  mode (`jarvis.vision.frame-retention.enabled=false`) is the safe
  default for tests.
* The capture boundary is metadata-only in Pass 1 — raw bytes never
  cross the cluster. Pass 2 can add an opt-in binary-upload variant
  for off-host re-analysis when the operator explicitly approves it.
* `audit_events` from Phase 8 now carries a richer trail: every
  enrollment, incident, and demo-mode block lives there with severity
  promoted to WARN/ERROR.

## Alternatives considered

* **Modify existing controllers to emit events.** Rejected for Pass 1
  to keep the existing tests untouched. Pass 2 can drop a
  `@EventListener` on each privileged service method without changing
  any signatures.
* **Stream raw frames over WebSocket from the agent.** Out of scope.
  Pass 1 is metadata-only on the wire; Pass 2 adds opt-in upload.
* **Promote `vision-security-service` to a dedicated `cv-capture` +
  `cv-analysis` split.** Rejected. SPEC-1's "fewer services unless
  data ownership / runtime profile differ" rule applies — split-by-host
  (capture on host, analysis in pod) is enough; we don't need two pods.
* **Write events to Kafka directly from existing services.** Rejected
  for the same reason — touching existing code in this phase.

## References

* SPEC-1 § "Native Desktop Agent" (capture vs analysis split)
* SPEC-1 § Phase 10 task list
* `apps/vision-security-service/.../phase10/`
* `apps/desktop-javafx/.../agent/vision/VisionUploadClient.kt`
* [phase-10-acceptance-evidence.md](../phase-10-acceptance-evidence.md)
