# Computer Vision Security Monitoring

## Architecture Decision

The workstation security feature is now split across two services:

- `vision-service` owns perception: face detection, owner verification, CV runtime loading, verifier/provider selection, and CV health/config reporting.
- `pc-control` owns machine-local behavior: webcam access, screenshot capture, workstation metadata, monitoring policy/debounce, evidence persistence, and email alert delivery.

This keeps Jarvis aligned with its existing service boundaries:

- `orchestrator` remains a coordinator and does not own webcam or screenshot side effects.
- `security-service` remains the authentication/JWT boundary, not a sensor-processing runtime.
- CV/model code no longer lives inside a machine-local adapter that also owns local side effects.

## What Moved Out Of `pc-control`

Moved into `vision-service`:

- OpenCV runtime loading
- Haar-cascade face detection
- baseline owner verification
- owner-reference directory management and enrollment API
- vision-specific DTOs and config
- vision health/config endpoints

Stays in `pc-control`:

- webcam frame capture
- screenshot capture
- workstation metadata collection
- repeated-unknown debounce and cooldown policy
- evidence bundling and local persistence
- alert dispatch and email sending

## Service Interaction

Current flow:

```text
pc-control scheduler/manual trigger
  -> capture webcam frame locally
  -> POST webcam frame to vision-service verify-owner
  -> capture screenshot locally
  -> POST screenshot to vision-service screen analysis
  -> vision-service detects face(s) and verifies owner
  -> vision-service classifies coarse screen risk
  -> pc-control applies local temporal policy/debounce
  -> if alert:
       collect workstation metadata
       persist evidence bundle locally
       attach webcam photo + screenshot + metadata
       send email notification with risk context
```

High-level boundary:

```text
vision-service = perception
pc-control = local device access + evidence + policy
notification path = delivery
orchestrator = coordination only when explicitly needed
```

## API Contracts

Shared request/response DTOs live in `apps/jarvis-common` under `org.jarvis.common.vision`.

Primary endpoints:

- `POST /api/v1/vision/face/verify-owner`
- `POST /api/v1/vision/face/verify-owner/debug`
- `POST /api/v1/vision/screen/analyze`
- `GET /api/v1/vision/health`
- `GET /api/v1/vision/config/status`
- `POST /api/v1/vision/owner-reference/enroll`

### Verify Request

`VisionVerifyOwnerRequest` contains:

- `imageBytes`
- `imageFormat`
- `source`
- `requestId`
- `metadata`

`pc-control` currently sends webcam JPEG bytes plus capture metadata such as timestamp, local provider, and device index.
The request is also accompanied by `X-Request-ID` and `X-Correlation-ID` headers so both services can log the same verification attempt consistently.

### Verify Response

`VisionVerifyOwnerResponse` returns:

- `outcome`: `OWNER`, `UNKNOWN`, `NO_FACE`, or `UNAVAILABLE`
- `operational`
- `provider`
- `message`
- `similarity`
- `referenceImageCount`
- `detectedFaces`
- `diagnostics`

The service boundary only exposes transport-safe DTOs. No OpenCV types cross the wire.
`pc-control` now consumes this shared contract directly instead of maintaining a second local verification DTO model.

## Current Implementation

### `vision-service`

Implemented components:

- `DefaultVisionImagePipelineService`
- `OpenCvFaceDetectionProvider`
- `EmbeddingCosineFaceVerificationProvider`
- `BlockGradientFaceEmbeddingEncoder`
- `ModelBackedEmbeddingCosineFaceVerificationProvider`
- `OpenCvDnnFaceEmbeddingEncoder`
- `BaselineAverageHashFaceVerificationProvider`
- `OwnerReferenceEmbeddingCache`
- `HeuristicFaceAlignmentService`
- `HeuristicFaceLivenessAssessor`
- `IdentitySignalEvaluator`
- `DefaultSecurityIncidentScoringService`
- `DefaultVisionScreenAnalysisService`
- `VisionReferenceCacheWarmup`
- `DefaultVisionVerificationService`
- `FileSystemOwnerReferenceService`
- `DefaultVisionStatusService`
- `VisionController`

`vision-service` now supports three verifier modes behind the same `FaceVerificationProvider` interface:

- `embedding-cosine-model`
- `embedding-cosine-mvp`
- `average-hash-baseline`

`embedding-cosine-model` is the model-backed path. It now expects one explicit ONNX profile:

- ArcFace-style `112x112` RGB input
- OpenCV DNN backend
- normalized float embedding vector
- configured embedding length validation (default `512`)

The encoder validates the configured model file, can run an optional startup warmup/forward-pass validation, exposes preprocessing diagnostics, and compares aligned live/reference embeddings with cosine similarity. This is the recommended path when a compatible ONNX face-embedding model is available on disk.

`embedding-cosine-mvp` remains the fallback/dev embedding mode and the current default. It uses a handcrafted block-gradient embedding plus cosine similarity. It is more structurally meaningful than a perceptual hash, but it is still not a trained biometric model.

`average-hash-baseline` remains the lowest-fidelity fallback. It is intentionally simple and fast, but it is much more sensitive to lighting, crop variance, and pose changes.

Both embedding providers now use a shared owner-reference embedding cache so reference embeddings are loaded once per detector/alignment/encoder combination, refreshed when the owner-reference directory changes, and optionally prewarmed on startup for the model-backed path.

### Production-Oriented Additions In This Pass

Implemented now:

- heuristic face alignment before embedding extraction for both `embedding-cosine-mvp` and `embedding-cosine-model`
- model-profile validation and warmup support for the ONNX-backed encoder
- cache diagnostics with explicit invalidation/prewarm reporting
- heuristic liveness gate integrated into verification diagnostics
- richer internal identity states:
  - `OWNER_CONFIRMED`
  - `UNKNOWN_CONFIRMED`
  - `NO_FACE`
  - `LOW_CONFIDENCE`
  - `UNAVAILABLE`
- first incident-scoring abstraction for low/medium/high/immediate-alert style outcomes
- screen-understanding foundation via `POST /api/v1/vision/screen/analyze`

Still explicitly heuristic or MVP:

- face alignment uses heuristic eye localization, not a landmark model
- liveness is a single-frame texture/quality heuristic, not a trained anti-spoofing model
- screen understanding uses image heuristics for OCR readiness/category/sensitivity, not OCR or classifier inference

## Professor Pipeline

The professor-required classical CV pipeline now lives entirely inside `vision-service`.
It is implemented as a reusable service, `DefaultVisionImagePipelineService`, and runs before the higher-level owner verification decision.

For every processed image, the pipeline produces these explicit stages:

1. `ORIGINAL`
   Captures the original request image unchanged.
2. `ENHANCEMENT`
   Converts to grayscale, applies histogram equalization, gamma correction, and optional Gaussian blur.
3. `SEGMENTATION`
   Builds a candidate mask from adaptive thresholding and Canny edges.
4. `CLEANING`
   Removes noise with morphology opening and closing.
5. `DETECTION`
   Extracts contour-based candidate regions and overlays them together with the configured face-detector boxes.
6. `DECISION`
   Produces the final machine-readable security decision.

This keeps the pipeline visible for the course rubric without moving any CV logic back into `pc-control`.

## Decision Logic

The final professor-visible decision is derived from both the explicit detection stage and the existing owner-verification providers:

- no detected face -> `NO_FACE`
- face detected and owner matched -> `AUTHORIZED`
- face detected and owner not matched -> `UNAUTHORIZED`
- detector or verifier unavailable -> `UNAVAILABLE`

The existing `VisionVerifyOwnerResponse.outcome` still reports the lower-level verification outcome:

- `OWNER`
- `UNKNOWN`
- `NO_FACE`
- `UNAVAILABLE`

The new higher-level decision is exposed separately for demo/debug usage as `VisionSecurityDecision`.

## Demo And Debug Output

The production-style endpoint remains:

- `POST /api/v1/vision/face/verify-owner`

It continues returning the compact verification response used by `pc-control`.

The new professor/demo endpoint is:

- `POST /api/v1/vision/face/verify-owner/debug`

It returns a `VisionVerifyOwnerDebugResponse` containing:

- `finalDecision`
- `verification`
- `stages`
- `artifacts`
- `diagnostics`

The stage artifacts are transport-safe PNG images encoded as base64 and include:

- original image
- enhanced image
- segmentation mask
- cleaned mask
- detection overlay

The detection overlay shows:

- contour-derived candidate regions
- face detector bounding boxes

The debug/verification diagnostics now also surface:

- alignment mode and rotation estimate
- liveness availability/pass/fail/confidence
- identity signal state and confidence
- incident score/disposition
- cache state, cache key, and prewarm/invalidation timestamps

This makes the pipeline easy to capture in screenshots, demo recordings, and course-report figures.

## Screen Understanding Foundation

`vision-service` now also exposes:

- `POST /api/v1/vision/screen/analyze`

This keeps screenshot capture inside `pc-control` while moving analysis into `vision-service`.

Current first-slice capabilities:

- OCR-readiness heuristics
- coarse screen category prediction
- sensitive-screen scoring foundation

Current categories:

- `TERMINAL`
- `DOCUMENT`
- `CHAT`
- `BROWSER`
- `MEDIA`
- `UNKNOWN`

This is intentionally a foundation, not a full UI parser.

## Internal Models

To keep OpenCV types inside `vision-service`, the explicit pipeline uses internal stage models and shared DTOs:

Internal `vision-service` models:

- `VisionPipelineExecution`
- `VisionPipelineArtifactImage`
- `VisionPipelineStageSnapshot`

Shared transport-safe DTOs in `jarvis-common`:

- `VisionImageArtifact`
- `VisionPipelineStage`
- `VisionPipelineStageResult`
- `VisionSecurityDecision`
- `VisionVerifyOwnerDebugResponse`

No OpenCV `Mat`, `Rect`, or other runtime-specific types cross the service boundary.

### `pc-control`

Implemented components:

- `DefaultWebcamFrameSource`
- `RemoteVisionVerificationService`
- `RemoteVisionScreenAnalysisService`
- `SecurityRiskScorer`
- `SecurityMonitoringPolicy`
- `SecurityMonitoringService`
- `SecurityMonitoringController`
- `DefaultEvidenceCollector`
- `EmailAlertDispatcher`
- `SecurityMonitoringScheduler`

`pc-control` now calls `vision-service` through a Feign client and degrades safely when the remote service is unavailable.

The current monitoring loop is:

1. `DefaultWebcamFrameSource` captures a warmed-up webcam frame locally.
2. `RemoteVisionVerificationService` sends the webcam JPEG to `POST /api/v1/vision/face/verify-owner`.
3. `RemoteVisionScreenAnalysisService` captures a local screenshot and sends it to `POST /api/v1/vision/screen/analyze`.
4. `SecurityRiskScorer` combines identity diagnostics, liveness diagnostics, screen sensitivity/category, and service availability into an observation score plus rolling risk score.
5. `SecurityMonitoringPolicy` applies temporal thresholds plus cooldown rules to decide whether to stay quiet, wait for more evidence, or alert.
6. `DefaultEvidenceCollector` persists `webcam.jpg`, `desktop.png`, and `metadata.json`.
7. `EmailAlertDispatcher` sends the email alert with attachments when email is enabled and recipients are configured.

### Active Security Path Audit

- Scheduler / entrypoint
  Implemented and used. `SecurityMonitoringScheduler` runs when monitoring is enabled, and `SecurityMonitoringController` exposes `POST /check` plus `GET /status`. The scheduler now catches unexpected runtime failures so one bad check does not kill future scheduled monitoring.
- Webcam frame source
  Implemented and used. `DefaultWebcamFrameSource` performs real local capture and now honors configured warmup frames. It is still hardware/runtime dependent.
- Verify-owner client path
  Implemented and used. `RemoteVisionVerificationService` calls the stable `verify-owner` endpoint and now applies configured fallback behavior consistently even for unexpected client-side failures.
- Screenshot capture and screen analysis
  Implemented and used. `RemoteVisionScreenAnalysisService` captures a real screenshot locally, sends it to `vision-service`, and returns a degraded `UNAVAILABLE` result with diagnostics if capture or remote analysis fails.
- Temporal decision policy
  Implemented and used. `SecurityMonitoringPolicy` and `SecurityRiskScorer` maintain rolling risk and explicit local states:
  `OWNER_CONFIRMED`, `OBSERVING`, `SUSPICIOUS`, `HIGH_RISK`, `ALERT_TRIGGERED`, `NO_FACE`, `UNAVAILABLE`, `DEGRADED`.
- Evidence collector
  Implemented and used. `DefaultEvidenceCollector` reuses the screenshot already captured for screen analysis whenever available, performs fallback capture only when needed, and writes an explicit evidence schema version `jarvis-security-evidence-v1`.
- Email dispatcher
  Implemented and used when configured. MIME attachments are real, but live delivery still depends on SMTP configuration and infrastructure outside the codebase.

### Real Workstation Incident Contract

`pc-control` now keeps one explicit alert/evidence context model:

- `WorkstationIncidentContext`

This contract is the local source of truth for:

- trigger
- captured timestamp
- decision block
- identity/verification block
- screen-analysis block
- evidence-attachment block
- workstation block
- warnings

It is carried in three places without changing any cross-service wire format:

- `MonitoringCheckReport`
- `AlertPayload`
- `EvidenceMetadata`

Even when the run is degraded, the contract is still present with explicit empty/default values instead of missing sections. That keeps status output, email content, and persisted metadata aligned.

## Boundary Hardening

The service split now includes these guardrails:

- request DTO validation on the `vision-service` API for image payload size, image format, and identifier lengths
- provider selection by configuration inside `vision-service`, so `detector-provider` and `verifier-provider` are real runtime selectors
- correlation/request ID propagation from `pc-control` to `vision-service`
- MDC-backed request logging in `vision-service`
- Feign timeouts configured in `pc-control`
- `Retryer.NEVER_RETRY` for the remote vision call to avoid hidden stalls or duplicate checks
- service-to-service JWT auth on the Feign call
- explicit fallback behavior in `pc-control` when `vision-service` is unavailable or returns an unexpected error
- explicit fallback behavior when screenshot capture or screen analysis is unavailable

## Configuration

### `vision-service`

Configured under `vision` in `apps/vision-service/src/main/resources/application.yml`.

Key properties:

- `enabled`
- `detector-provider`
- `verifier-provider`
- `similarity-threshold`
- `minimum-face-size-pixels`
- `face-cascade-path`
- `owner-reference-directory`
- `reference-extensions`
- `embedding.face-image-size`
- `embedding.pooling-grid-size`
- `embedding.model.backend`
- `embedding.model.profile`
- `embedding.model.path`
- `embedding.model.input-width`
- `embedding.model.input-height`
- `embedding.model.scale`
- `embedding.model.mean-blue`
- `embedding.model.mean-green`
- `embedding.model.mean-red`
- `embedding.model.swap-red-blue`
- `embedding.model.output-name`
- `embedding.model.expected-embedding-length`
- `embedding.model.validate-on-startup`
- `embedding.model.similarity-threshold`
- `alignment.enabled`
- `alignment.minimum-eye-separation-ratio`
- `alignment.maximum-roll-angle-degrees`
- `reference-cache.prewarm-on-startup`
- `liveness.enabled`
- `liveness.minimum-sharpness`
- `liveness.minimum-contrast`
- `liveness.pass-threshold`
- `screen.enabled`
- `screen.ocr-ready-contrast-threshold`
- `screen.text-edge-density-threshold`
- `screen.sensitive-threshold`
- `pipeline.gamma`
- `pipeline.blur-kernel-size`
- `pipeline.adaptive-threshold-block-size`
- `pipeline.adaptive-threshold-constant`
- `pipeline.canny-low-threshold`
- `pipeline.canny-high-threshold`
- `pipeline.morphology-kernel-size`
- `pipeline.minimum-candidate-area-pixels`
- `enrollment.enabled`

### `pc-control`

Configured under `pc-control.security-monitoring` in `apps/pc-control/src/main/resources/application.yml`.

Key properties:

- `enabled`
- `sampling-interval`
- `similarity-threshold`
- `consecutive-unknown-detections-required`
- `cooldown-between-alerts`
- `evidence-directory`
- `webcam.*`
- `screenshot.enabled`
- `vision.skip-on-unavailable`
- `screen-analysis.enabled`
- `decision.suspicious-score-threshold`
- `decision.alert-score-threshold`
- `decision.high-risk-score-threshold`
- `decision.high-risk-observations-required`
- `alert.email.*`

Remote client settings:

- `jarvis.vision-service.url`
- `spring.cloud.openfeign.client.config.vision-service.connect-timeout`
- `spring.cloud.openfeign.client.config.vision-service.read-timeout`

`pc-control.security-monitoring.similarity-threshold` remains local on purpose. It is no longer a model threshold; it is the local policy threshold applied to the similarity score returned by `vision-service`.

## Health And Demoability

Useful runtime endpoints:

- `GET /actuator/health` on both services
- `GET /api/v1/vision/health`
- `GET /api/v1/vision/config/status`
- `POST /api/v1/vision/face/verify-owner/debug`
- `GET /api/v1/pc/security-monitoring/status`
- `POST /api/v1/pc/security-monitoring/check`

That makes the feature demoable without waiting for the scheduled sampler.

The `vision-service` health/config diagnostics now include:

- configured verifier provider
- configured embedding backend for embedding-based providers
- whether the selected embedding backend is available
- whether the configured model profile is validated
- alignment provider/limitations
- liveness provider/thresholds
- whether reference embeddings are cached or still cold
- how many reference embeddings are currently loaded
- whether cache prewarming is enabled
- screen-analysis endpoint/support flags
- whether the debug endpoint is supported
- the ordered pipeline stage list used for professor-visible processing

## Obsolete Classes Removed

Removed from `pc-control` as obsolete local inference artifacts:

- `org.jarvis.pccontrol.securitymonitoring.model.FaceRegion`
- `org.jarvis.pccontrol.securitymonitoring.model.FaceVerificationOutcome`
- `org.jarvis.pccontrol.securitymonitoring.model.FaceVerificationResult`
- `org.jarvis.pccontrol.securitymonitoring.model.FaceDetectionResult`
- `org.jarvis.pccontrol.securitymonitoring.service.FaceDetectionProvider`
- `org.jarvis.pccontrol.securitymonitoring.service.FaceVerificationProvider`
- `org.jarvis.pccontrol.securitymonitoring.service.impl.OpenCvFaceDetectionProvider`
- `org.jarvis.pccontrol.securitymonitoring.service.impl.AverageHashFaceVerificationProvider`
- `org.jarvis.pccontrol.securitymonitoring.service.impl.OpenCvRuntime`
- `org.jarvis.pccontrol.securitymonitoring.service.impl.OpenCvWebcamFrameSource`
- `org.jarvis.pccontrol.securitymonitoring.util.OpenCvImageUtils`

Current `pc-control` runtime code has no detector or verifier implementation left. The remaining vision-facing code is limited to:

- webcam capture
- remote contract call
- local policy/debounce
- evidence capture
- alert dispatch

## Honest Status Matrix

Implemented and operational:

- professor-visible CV pipeline with artifacts and debug endpoint
- stable `verify-owner` contract used by `pc-control`
- `pc-control` monitoring scheduler, manual check endpoint, and runtime status snapshot
- webcam capture, remote verify-owner call, screenshot capture, and remote screen analysis
- temporal alert decisioning with rolling risk, suspicious/high-risk streaks, and cooldown
- evidence bundles containing webcam photo, screenshot, metadata JSON, and the shared workstation incident context
- email alert generation with attachments and real MIME dispatch when SMTP is configured

Implemented but environment-dependent:

- local webcam capture depends on hardware, drivers, and desktop runtime
- local screenshot capture depends on a usable desktop environment and fails gracefully in headless mode
- live email delivery depends on SMTP configuration and infrastructure
- model-backed embedding verification depends on a compatible ONNX file being present and loadable

Heuristic or MVP:

- heuristic face alignment before embedding extraction
- heuristic liveness gate
- `embedding-cosine-mvp`
- `average-hash-baseline`
- file-based enrollment flow
- screen understanding heuristics for OCR readiness, category, and sensitivity

Production-oriented but not yet production-ready:

- `embedding-cosine-model` with aligned crops and explicit ArcFace-style preprocessing
- cache prewarming/diagnostics
- identity-state abstraction
- incident scoring
- screenshot-risk integration and temporal alerting in `pc-control`
- evidence-rich email alerting with degraded-path reporting
- explicit workstation incident contract kept consistent across report, email, and metadata

Still missing for true production readiness:

- landmark-based face alignment or a detector that emits stable facial landmarks
- bundled and benchmarked production embedding model with calibrated thresholds
- trained anti-spoofing / liveness model
- multi-frame temporal smoothing inside the vision decision itself
- OCR and stronger screen-content classifiers
- multi-face tracking/selection instead of only using the largest detected face
- a hardened enrollment/rotation UX and operational tooling
- mail-delivery observability/retry handling beyond the current single-send path
- field validation from real workstation captures to calibrate score thresholds and alert frequency

## Operational Readiness

- Professor demo
  Strong. The classical CV pipeline, debug artifacts, and explorable diagnostics are intact and more visible than before.
- Personal workstation MVP
  Usable with honest caveats. The local monitoring loop, screen-aware temporal policy, evidence capture, and email path are real. The main remaining risks are false positives/false negatives from heuristic alignment, heuristic liveness, and heuristic screen understanding.
- Production biometric security
  Not ready. The system still lacks landmark-grade alignment, trained anti-spoofing, calibrated biometric thresholds, richer screen models, multi-face tracking, and hardened delivery/ops guarantees.

## Verifier Tradeoffs

- `average-hash-baseline`
  Fastest and simplest. Good for smoke tests, poor under lighting and pose variation.
- `embedding-cosine-mvp`
  Better structured than average-hash and works without external model files. Still handcrafted and not truly model-backed.
- `embedding-cosine-model`
  Best architectural path going forward. Uses a real embedding model, aligned crops, explicit preprocessing, and cached reference embeddings, but still requires a compatible ONNX model plus threshold/runtime tuning.

## How To Reproduce The Pipeline

1. Start `vision-service` with OpenCV available.
2. Optionally configure owner references and a model path for `embedding-cosine-model`.
3. Send a webcam image to `POST /api/v1/vision/face/verify-owner/debug`.
4. Inspect the returned stage artifacts:
   - original
   - enhanced
   - segmentation
   - cleaning
   - detection overlay
5. Inspect `finalDecision`, `verification`, and per-stage diagnostics.

That gives a fully automatic, reproducible pipeline run suitable for a professor demo or report screenshot set.

## Recommended Next Step

The next quality upgrades should still happen inside `vision-service`:

- replace heuristic alignment with landmark-based alignment
- integrate a trained anti-spoofing model behind the existing liveness abstraction
- calibrate and document a single recommended ONNX embedding model/threshold pair from real workstation captures
- upgrade screen analysis from heuristics to OCR/classifier-backed content understanding while keeping screenshot capture in `pc-control`
- keep `embedding-cosine-mvp` and `average-hash-baseline` available for degraded/demo environments

That preserves the service boundary while improving CV quality without pushing model concerns back into `pc-control`.
