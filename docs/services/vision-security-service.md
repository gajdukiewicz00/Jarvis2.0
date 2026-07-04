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
- `POST /api/v1/vision-security/cv/analyze` — local CV vertical slice; OCR on a file path or fresh screenshot, returns structured JSON.
- `POST /api/v1/vision-security/cv/screen-context` — wide-view screen understanding: screenshot + OCR + active-window probe + semantic tags + clustered regions + local VLM slot (currently `NOT_CONFIGURED`).
- `POST /api/v1/vision-security/cv/ask-screen` — capture, OCR, then ask a local VLM a question about the screen.

No WebSocket endpoint.

### Ask-screen contract

Request body:

```json
{
  "question": "What is on my screen right now?",
  "captureFreshScreenshot": true,
  "imagePath": "/tmp/optional/screenshot/path.png"
}
```

- `question` (required).
- `captureFreshScreenshot` (optional, default `true`) — capture the live screen.
- `imagePath` (optional) — analyze this file instead; **takes precedence**
  over `captureFreshScreenshot`. Setting `captureFreshScreenshot=false`
  without an `imagePath` is rejected (HTTP 400).

Response (`AskScreenResult`):

```json
{
  "question": "What is on my screen right now?",
  "answer": "A terminal window showing OCR output.",   // null when VLM not READY
  "screenContext": { /* full ScreenContextResult, including the embedded CvAnalysisResult */ },
  "vlm": {
    "provider": "ollama",
    "model": "llava",
    "availability": "READY",              // READY | UNAVAILABLE | NOT_CONFIGURED
    "durationMs": 1183,
    "error": null
  },
  "capturedAt": "...",
  "durationMs": 4250,
  "success": true,
  "error": null
}
```

### CV analyze contract

Request body (all fields optional):

```json
{ "source": "file|screenshot", "imagePath": "/abs/path/to/image.png" }
```

Response (structured JSON `CvAnalysisResult`):

```json
{
  "source": "file",
  "imagePath": "/tmp/jarvis-cv/fixture.png",
  "width": 640,
  "height": 200,
  "ocrText": "Hello Jarvis\nLocal OCR Test\nComputer Vision",
  "blocks": [
    { "text": "Hello Jarvis", "confidence": 96.16,
      "bbox": { "x": 23, "y": 25, "width": 174, "height": 27 } }
  ],
  "engine": "tesseract",
  "language": "eng",
  "durationMs": 97,
  "capturedAt": "2026-05-25T16:23:44.044150336Z",
  "success": true,
  "error": null
}
```

## 8. Main Internal Components

- `VisionSecurityManager`
- `CameraCaptureService`
- `VisionPipelineService`
- `FaceVerificationService`
- `EnrollmentStore`
- `IncidentStore`
- `ScreenshotService`
- `ScreenContextService`
- `OcrService` (also exposes structured TSV parsing via `extractStructured`)
- `cv.OcrEngine` + `cv.TesseractOcrEngine` + `cv.OcrEngineSelector` (multi-engine abstraction, selected by `vision-security.cv.engine`)
- `LocalCvService` (CV vertical slice: file/screenshot → OCR → structured JSON, with metrics)
- `cv.ScreenContextCvService` (wide-view: screenshot → OCR → active window/tags/regions → optional VLM slot)
- `cv.LocalVlmAdapter` + `cv.NotConfiguredLocalVlmAdapter` (default placeholder — `NOT_CONFIGURED`, never fabricates)
- `cv.OllamaLocalVlmAdapter` — local-only Ollama backend (`POST {endpoint}/api/generate` with base64 image); rejects cloud hosts at construction
- `cv.LlamaCppLocalVlmAdapter` — local-only llama.cpp server (`POST {endpoint}/v1/chat/completions` with `image_url` data URI); rejects cloud hosts
- `cv.AskScreenCvService` — orchestrates screenshot → screen-context → VLM answer
- `cv.ScreenContextEventPublisher` (Kafka publisher for `jarvis.cv.screen_context.created`; no-op when Kafka unset)
- `CvCliRunner` (headless CLI: `--cv.input=…`, `--cv.screenshot=true`, `--cv.screen-context=true`, `--cv.ask-screen="..."`)
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

### Local CV vertical slice

Install the OCR engine (Debian/Ubuntu, fully local — no cloud APIs):

```bash
sudo apt install tesseract-ocr             # add tesseract-ocr-rus, etc. for other languages
# optional, for X11 screenshot capture:
sudo apt install gnome-screenshot          # or: scrot, imagemagick
```

Build the fat jar:

```bash
mvn -pl apps/vision-security-service -am -DskipTests package
```

Run OCR on a local image (no web server is needed; uses the dev profile so the
internal JWT filter is off):

```bash
JAR=apps/vision-security-service/target/vision-security-service-1.0.0.jar
SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  java -jar "$JAR" --cv.input=/path/to/image.png
```

Capture the screen and OCR it:

```bash
SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  java -jar "$JAR" --cv.screenshot=true --cv.output=/tmp/screen.png
```

Full screen-context capture (screenshot + OCR + active window + regions +
VLM slot + Kafka event when configured):

```bash
SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  java -jar "$JAR" --cv.screen-context=true --cv.user=owner \
                   --cv.output=/tmp/screen-context.png
```

Exit codes: `0` success, `2` OCR engine unavailable, `3` input file missing,
`4` any other failure. Output is a single structured JSON document on stdout.

HTTP usage (service must be running and authenticated through the gateway):

```bash
curl -X POST http://localhost:8094/api/v1/vision-security/cv/analyze \
  -H 'Content-Type: application/json' \
  -d '{"source":"file","imagePath":"/path/to/image.png"}'

curl -X POST http://localhost:8094/api/v1/vision-security/cv/screen-context \
  -H 'Content-Type: application/json' \
  -d '{}'
```

### Engine selection

Configured via `vision-security.cv.engine` (env: `VISION_SECURITY_CV_ENGINE`).
Currently only `tesseract` is bundled; unknown ids log a `WARN` and fall back
to the first registered engine. Slots are prepared for future
PaddleOCR/EasyOCR engines — add a `@Component` implementing
`service.cv.OcrEngine` and the selector will pick it up.

### Local VLM (ask-screen) — Ollama or llama.cpp

All VLM traffic stays on a loopback/private endpoint. The adapter
construction rejects obvious cloud hosts (`api.openai.com`,
`generativelanguage.googleapis.com`, `anthropic.com`,
`huggingface.co`, …). No cloud APIs are ever called.

Disabled by default (`vision-security.cv.vlm.enabled=false`). When off,
`/cv/ask-screen` still works — it returns the screen context with
`vlm.availability=NOT_CONFIGURED` and `answer=null`, never a fabricated
summary.

#### Ollama (recommended for desktops)

```bash
# 1) install + pull a small local vision model (choose ONE; both are local-only)
curl -fsSL https://ollama.com/install.sh | sh        # local install
ollama pull llava                                    # ~4 GB
# or:  ollama pull minicpm-v                         # ~5 GB
ollama serve &                                       # listens on 127.0.0.1:11434

# 2) point Jarvis at it
export VISION_SECURITY_VLM_ENABLED=true
export VISION_SECURITY_VLM_PROVIDER=ollama
export VISION_SECURITY_VLM_ENDPOINT=http://127.0.0.1:11434
export VISION_SECURITY_VLM_MODEL=llava
export VISION_SECURITY_VLM_TIMEOUT=PT60S
# optional knobs (defaults shown):
export VISION_SECURITY_VLM_MAX_TOKENS=512
export VISION_SECURITY_VLM_TEMPERATURE=0.2
export VISION_SECURITY_VLM_INCLUDE_OCR_CONTEXT=true   # append OCR text to the prompt
export VISION_SECURITY_VLM_INCLUDE_SCREENSHOT=true    # attach the image; false = text-only over OCR
```

#### llama.cpp server (advanced)

```bash
# Build the official llama.cpp + a multimodal GGUF (e.g. llava-1.6, llava-mistral)
llama-server -m models/llava-mistral-7b.Q4_K_M.gguf \
             --mmproj models/llava-mistral-7b-mmproj.gguf \
             --host 127.0.0.1 --port 8080
export VISION_SECURITY_VLM_ENABLED=true
export VISION_SECURITY_VLM_PROVIDER=llamacpp
export VISION_SECURITY_VLM_ENDPOINT=http://127.0.0.1:8080
export VISION_SECURITY_VLM_MODEL=llava-mistral-7b
```

CLI smoke-test:

```bash
java -jar "$JAR" --cv.ask-screen="What application is open on my screen?" \
                 --cv.output=/tmp/jarvis-cv/ask.png --cv.user=owner
```

Exit codes: `0` success, `2` OCR engine unavailable, `3` input missing,
`4` other CV failure, and — **ask-screen only** — `5` when the local VLM
was not configured or unreachable, so no answer was produced (the screen
context is still printed). The `vlm` section in the response carries
`availability` and any backend error verbatim — no fake summary is ever
produced.

### Kafka events

When `spring.kafka.bootstrap-servers` is set, the screen-context flow
publishes a JSON event to **`jarvis.cv.screen_context.created`** (configurable
via `vision-security.cv.screen-context-topic`). Payload is the full
`ScreenContextResult` (which embeds `CvAnalysisResult` — screenshot path, OCR
text, blocks, duration, engine). Publishing is fire-and-forget; the request
flow never blocks on the bus. Without Kafka the publisher logs at DEBUG and
returns `false` without raising.

### Observability

Metrics exposed via `/actuator/prometheus`:

- `jarvis_cv_requests_total{source,outcome,engine}`
- `jarvis_cv_failures_total{source,reason,engine}`
- `jarvis_cv_duration_seconds{source,outcome,engine}` (Timer, p50/p95)
- `jarvis_cv_ocr_text_chars` (gauge, characters of the last successful run)
- `jarvis_cv_vlm_requests_total{provider,outcome}` (outcome = ready/not_configured/unavailable)
- `jarvis_cv_vlm_failures_total{provider,reason}`
- `jarvis_cv_vlm_duration_seconds{provider,outcome}` (Timer, p50/p95)
- `jarvis_cv_detection_total{type,availability}` (type = ui/object)

On the consumer side, `memory-service` exposes:

- `jarvis_memory_cv_persist_total{outcome}` (outcome = persisted/duplicate/failure/embed_skip)
- `jarvis_memory_cv_persist_failures_total{reason}`

Each CV request emits structured INFO logs at start and completion with
`source`, `engine`, `blocks`, `chars`, and `durationMs`; failures log `reason`.

### No-cloud guarantee

The CV slice shells out to a single local binary, `tesseract`, and uses
in-process `ImageIO` for dimensions. Screen capture uses
`gnome-screenshot`/`scrot`/`import` or the in-process Java `Robot`. No
network calls are made; no cloud vision APIs are used.

## 13. Implementation Status

Implemented, local-only.

## 14. Known Gaps / Caveats

- Not included in `k8s/base`.
- Real behavior depends on host camera/display/tool availability.
- Screen analysis is local CLI based and may be limited outside a normal desktop session.

### CV slice — known limitations

- Tesseract is the only currently-wired engine (PSM 6, language from
  `vision-security.screen.ocr-language`). PaddleOCR/EasyOCR are not bundled
  by default.
- Object/UI-element detection (DETR/YOLO) is out of scope of this slice; only
  OCR blocks (text + bbox + confidence) are returned.
- The `LocalCvServiceSmokeTest` auto-skips when `tesseract` is not on `PATH`;
  pure tests (`OcrServiceParseTsvTest`, `LocalCvServiceTest`) always run.
- Screenshot capture currently relies on the local desktop toolchain. Tested
  with `gnome-screenshot` on X11; on Wayland, `gnome-screenshot` is preferred,
  otherwise install one of `scrot` / `imagemagick`.

## 15. CV concepts at a glance

| Concept | What it answers | Where it lives in Jarvis |
| --- | --- | --- |
| **OCR** | "What characters / words are on this image?" Per-word/line text + bbox + confidence. No semantic understanding. | `OcrService.extractStructured`, `LocalCvService.analyzeFile` / `analyzeScreenshot`. |
| **Screen context** | "What is the user looking at *right now*?" Combines screenshot + OCR + window title + process + clustered regions + semantic tags. Still a deterministic pipeline — no neural reasoning. | `cv.ScreenContextCvService.capture`, endpoint `/cv/screen-context`. |
| **UI element detection** | "Where are the buttons, inputs, tabs, etc.?" A neural model (or rules) producing labelled component bboxes (button/menu/textbox/…). | **Interface + safe default.** `cv.UiElementDetector` + `cv.NotConfiguredUiElementDetector` (returns `NOT_CONFIGURED`, never fabricates). `ScreenContextResult.uiElements` carries results; empty until a local detector bean is registered. |
| **Object detection** | "What objects are in this photo?" Generic models like YOLO/DETR producing classes (person, cat, laptop, …). Useful for camera/world view, not screens. | **Interface + safe default.** `cv.ObjectDetector` + `cv.NotConfiguredObjectDetector`. `ScreenContextResult.objects` carries results; empty until wired. Existing OpenCV haar cascades only detect faces/eyes for security. |
| **Local VLM reasoning** | "Describe / answer questions about the image." End-to-end vision-language model running locally (LLaVA via llama.cpp, Ollama vision model, …). | `cv.LocalVlmAdapter` with **two real local adapters** (`OllamaLocalVlmAdapter`, `LlamaCppLocalVlmAdapter`). Default bean is `NotConfiguredLocalVlmAdapter` (returns `NOT_CONFIGURED`, **never fabricates a summary**) until `vision-security.cv.vlm.enabled=true`. |
| **Memory integration** | "Remember what was on screen at 14:32." Persist `ScreenContextResult` records to `memory-service` for later recall and LLM grounding. | **Implemented.** `memory-service` consumes `jarvis.cv.screen_context.created` and persists OCR text, blocks, tags, window/process, optional raw screenshot + optional pgvector embedding. See [memory-service.md](memory-service.md) and [cv-screen-context-event.md](../architecture/cv-screen-context-event.md). |

## 16. Capability status matrix

| Capability | Status | Where to look |
| --- | --- | --- |
| OCR (text + bbox + confidence) | **READY** | `OcrService`, `LocalCvServiceSmokeTest`, `/cv/analyze` |
| Screenshot capture (X11/Wayland) | **READY** | `ScreenshotService`, `/cv/analyze` with `source=screenshot` |
| Engine abstraction (multi-engine) | **READY (1 engine wired)** | `cv.OcrEngine`, `cv.OcrEngineSelector`; only `tesseract` registered |
| Screen-context endpoint & event | **READY** | `cv.ScreenContextCvService`, `/cv/screen-context`, `jarvis.cv.screen_context.created` |
| Active window / process probe | **PARTIAL** | `ScreenContextService.activeWindowTitle` — needs `xdotool`; Wayland needs work |
| Semantic tags | **PARTIAL** | `SemanticTagger` is rule-based; useful, not exhaustive |
| Region clustering | **PARTIAL** | Pure post-processing of OCR bboxes; no model |
| UI element detection | **NOT READY (interface + safe default)** | `cv.UiElementDetector` + `cv.NotConfiguredUiElementDetector`; `ScreenContextResult.uiElements` + `detection.uiAvailability`. Returns `NOT_CONFIGURED`, never fabricates. `NotConfiguredDetectorsTest` |
| Object detection (general scenes) | **NOT READY (interface + safe default)** | `cv.ObjectDetector` + `cv.NotConfiguredObjectDetector`; `ScreenContextResult.objects` + `detection.objectAvailability`. `NotConfiguredDetectorsTest` |
| Local VLM reasoning (Ollama / llama.cpp adapters) | **READY (adapters), PARTIAL (runtime)** | `cv.OllamaLocalVlmAdapter` + `cv.LlamaCppLocalVlmAdapter` ship; the default bean is still `NotConfiguredLocalVlmAdapter` until `vision-security.cv.vlm.enabled=true`. No local vision model is bundled — install `llava` / `minicpm-v` via Ollama or a GGUF + mmproj for `llama-server`. |
| Ask-screen endpoint (`/cv/ask-screen`) | **READY** | `cv.AskScreenCvService`; CLI `--cv.ask-screen="..."`. Returns honest `NOT_CONFIGURED` / `UNAVAILABLE` when the backend is off or unreachable. |
| Memory integration (consumer) | **READY** | `memory-service` `cv.ScreenContextEventConsumer` → `screen_context_observation`; idempotent, optional raw screenshot + pgvector embedding. See [memory-service.md](memory-service.md) |
| Cloud APIs | **N/A** | Forbidden by design — never used and never will be |
