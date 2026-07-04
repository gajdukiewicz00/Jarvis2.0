# CV screen-context event contract

`jarvis.cv.screen_context.created`

- **Producer:** `vision-security-service` —
  `org.jarvis.visionsecurity.service.cv.ScreenContextEventPublisher`
  (fires after every `/cv/screen-context` capture, and after `/cv/ask-screen`
  since it captures screen-context internally).
- **Consumer:** `memory-service` —
  `org.jarvis.memory.cv.ScreenContextEventConsumer` →
  `screen_context_observation` table.
- **Topic constant:** `EventTopics.CV_SCREEN_CONTEXT` (shared in
  `libs/event-schema`). This is an **extension topic**, not one of the SPEC-1
  seven; it is deliberately excluded from `EventTopics.all()`.

## Key

The Kafka record **key** is the `userId` (or `anonymous` when absent), so all
of one user's observations land on the same partition and stay ordered.

## Payload schema

The value is the JSON serialisation of `ScreenContextResult`. Fields the
consumer reads (others are tolerated and ignored — `@JsonIgnoreProperties`):

| Field | Type | Notes |
| --- | --- | --- |
| `userId` | string | Kafka key; nullable |
| `capturedAt` | ISO-8601 instant | capture time |
| `durationMs` | long | end-to-end capture duration |
| `screenshotPath` | string | host path to the screenshot file |
| `displayServer` | string | `x11` / `wayland` / `headless` |
| `activeWindowTitle` | string | focused window title (X11; needs `xdotool`) |
| `activeProcessName` | string | focused process |
| `semanticTags` | string[] | rule-based tags |
| `uiElements` | object[] | UI detections (empty unless a detector is wired) |
| `objects` | object[] | object detections (empty unless wired) |
| `detection` | object | `{uiAvailability, objectAvailability}` |
| `analysis.ocrText` | string | full OCR text |
| `analysis.blocks` | object[] | `{text, confidence, bbox{x,y,width,height}}` |
| `analysis.engine` | string | e.g. `tesseract` |
| `analysis.language` | string | e.g. `eng` |
| `vlm` | object | availability/summary (not persisted by memory) |
| `success` / `error` | bool / string | capture outcome |

### Example event

```json
{
  "userId": "owner",
  "capturedAt": "2026-05-25T10:00:05Z",
  "durationMs": 1234,
  "screenshotPath": "/tmp/jarvis-cv/screen.png",
  "displayServer": "x11",
  "activeWindowTitle": "Terminal — bash",
  "activeProcessName": "gnome-terminal",
  "semanticTags": ["DEV", "TERMINAL"],
  "uiElements": [],
  "objects": [],
  "detection": {"uiAvailability": "NOT_CONFIGURED", "objectAvailability": "NOT_CONFIGURED"},
  "analysis": {
    "source": "screenshot", "imagePath": "/tmp/jarvis-cv/screen.png",
    "width": 1920, "height": 1080,
    "ocrText": "Hello Jarvis",
    "blocks": [{"text": "Hello", "confidence": 96.1, "bbox": {"x": 1, "y": 2, "width": 3, "height": 4}}],
    "engine": "tesseract", "language": "eng",
    "durationMs": 97, "success": true, "error": null
  },
  "vlm": {"availability": "NOT_CONFIGURED", "summary": null, "error": "..."},
  "success": true,
  "error": null
}
```

## What memory-service persists

Into `screen_context_observation` (Flyway `V7`):

- All metadata fields above (userId, timestamps, window/process, tags,
  OCR text + blocks, UI/object detections, engine/language, success/error).
- `screenshot_path` always; `screenshot_bytes` only when
  `jarvis.memory.cv.store-raw-screenshot=true` AND the file is readable by the
  consumer AND within `max-screenshot-bytes`.
- `embedding vector(384)` when `jarvis.memory.cv.embed=true` and the local
  embedding-service answers; otherwise null (graceful).

## Idempotency

The consumer derives a key from `userId + capturedAt + screenshotPath`
(SHA-256) and skips a record whose key already exists — a redelivered event is
never double-persisted. Malformed payloads are logged and acked (no partition
block).

## Retention / security / privacy

- **Topology:** declared with 3 partitions / 30-day retention by the producer's
  Kafka topology (auto-created when `spring.kafka.bootstrap-servers` is set;
  `missing-topics-fatal: false`).
- **Privacy:** screenshots and OCR text can contain sensitive on-screen
  content. Per **ADR-0011**, raw frames are kept host-local — in a clustered
  deployment the consumer pod cannot read the producer host's screenshot file,
  so `store-raw-screenshot` is set to `false` in `k8s/overlays/prod` and only
  the path reference + OCR/metadata are persisted there. Raw-byte capture is a
  local single-host convenience.
- **No cloud:** neither producer nor consumer calls any cloud API. Embeddings
  use the local embedding-service only.
- **Deletion:** observations are plain rows; a future retention job can prune
  by `captured_at` (the index `idx_screen_ctx_captured` supports it).

## Status

**READY** — producer publishes (no-op without Kafka); consumer persists with
idempotency, optional raw bytes, optional local embedding; schema verified
against live Postgres+pgvector (Flyway `V7` + Hibernate `validate`).
End-to-end delivery over a live broker is covered by unit tests
(`ScreenContextPersistenceServiceTest`, `ScreenContextEventDeserializationTest`)
plus the wiring/boot verification.
