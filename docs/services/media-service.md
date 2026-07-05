# media-service

## 1. Name

`media-service`

## 2. Type

Backend media inspection / Russian dubbing pipeline service (EPIC 8).

## 3. Purpose

Inspects media files (stream/audio detection), extracts audio, transcribes it,
generates Russian subtitles, and produces a safe, neutral-voice Russian dub — without
touching the original file (mux always writes a separate output; `-c copy` preserves
every original stream). Every provider stage (ffprobe/ffmpeg/ASR/translation/TTS)
defaults to a mock implementation so a fresh checkout runs with zero native
dependencies; real implementations exist behind explicit flags for ffprobe, ffmpeg,
ASR (whisper.cpp), and translation (llm-service). Real neutral TTS (Piper) is not
wired up yet.

## 4. Current Reality

Implemented with a real safety posture even in mock mode: `MediaTextGuard`
neutralizes injection markers in transcripts/subtitles/titles and wraps them in an
`UNTRUSTED_DATA` envelope before any translation/LLM use, unconditionally regardless
of provider mode. Every ffprobe/ffmpeg call is built as an argument list and run via
`ProcessRunner` (`ProcessBuilder`, no shell, hard timeout) — a hostile filename stays
one argument. `WorkspaceManager` is the single chokepoint for path safety: artifacts
are written only inside the workspace, and `..`/null-byte/escape attempts are
rejected (400). The container base image ships no ffmpeg/whisper.cpp/Piper binaries
by default, so production runs mock providers unless explicitly built with the
opt-in `real-media-image` Maven profile (or a sidecar container) and the
corresponding `media.*.mode=real`/`whisper`/`llm` flags. TTS remains mock-only; no
real (Piper) implementation exists yet, and voice cloning is never implemented —
default dubbing is always a generic neutral synthetic voice, and a `USER_OWNED`
voice profile requires both an explicit flag and per-request consent, even then only
synthesizing a neutral placeholder.

## 5. Entry Points

- Spring Boot app: `org.jarvis.media.MediaServiceApplication`
- REST base path: `/api/v1/media`

## 6. Configuration

Main configuration source:

- `apps/media-service/src/main/resources/application.yml`

Important settings include:

- server port `8095` (`JARVIS_MEDIA_PORT`)
- `media.enabled` (default `true`, env `MEDIA_ENABLED`) — feature flag; when `false`
  job endpoints return 503
- `media.workspace.dir` (default `/tmp/jarvis-media`, env `MEDIA_WORKSPACE_DIR`) —
  writable workspace root (pod runs `readOnlyRootFilesystem=true`); `media.workspace.input-roots`
  — additional read-only roots media may be read from
- `media.executor.pool-size` / `media.executor.queue-capacity` — bounded async job
  executor (HTTP threads never block on media work)
- Provider mode flags (all default to `mock`):
  - `media.ffprobe.mode` / `media.ffprobe.binary` / `media.ffprobe.timeout-seconds`
  - `media.ffmpeg.mode` / `media.ffmpeg.binary` / `media.ffmpeg.timeout-seconds`
  - `media.asr.mode` (`mock` / `whisper`) / `media.asr.binary` (default
    `whisper-cli`) / `media.asr.model-path` / `media.asr.timeout-seconds` — falls
    back to the mock transcript with a logged warning if the binary or model file is
    missing, rather than failing every job
  - `media.translation.mode` (`mock` / `llm`) / `media.translation.llm-service-url`
    (default `http://llm-service:8091`) — routes already-neutralized text to
    [llm-service](llm-service.md)'s `/api/v1/llm/chat` with a short-lived
    `SVC_INTERNAL` service JWT; fails closed (throws rather than shipping
    untranslated text as if it were Russian) if llm-service is unreachable or replies
    empty; requires a NetworkPolicy allowlist entry to llm-service (not included by
    default)
  - `media.tts.mode` (`mock` only today) / `media.tts.allow-user-voice-profile`
    (default `false`)
- `media.subtitle.max-segment-seconds` / `media.subtitle.min-confidence` —
  quality-warning thresholds
- `jarvis.media.job-store` (env `JARVIS_MEDIA_JOB_STORE`; no YAML default —
  `memory`/`file`/`postgres`, see §10)

## 7. API / WebSocket Surface

All endpoints require gateway/service authentication except `/actuator/health`; job
endpoints are disabled (503) when `media.enabled=false`.

- `POST /api/v1/media/probe` — **synchronous** stream detection + main-audio
  selection (fast enough it doesn't need a job)
- `POST /api/v1/media/jobs/extract-audio` — extract selected audio → WAV/FLAC (async, 202)
- `POST /api/v1/media/jobs/transcribe` — ASR transcript with timed segments (async, 202)
- `POST /api/v1/media/jobs/russian-subtitles` — RU SRT + VTT + RU transcript, with
  quality warnings (async, 202)
- `POST /api/v1/media/jobs/russian-dub-audio` — neutral RU dub audio + quality report
  (async, 202)
- `POST /api/v1/media/jobs/mux` — add RU subtitle/audio to a **copy** of the original
  (async, 202)
- `GET /api/v1/media/jobs` — list the caller's jobs
- `GET /api/v1/media/jobs/{id}` — job status + artifacts + details
- `POST /api/v1/media/jobs/{id}/cancel` — cancel (cooperative `CancellationToken` +
  worker interrupt)
- `GET /api/v1/media/jobs/{id}/artifacts/{index}` — download one produced artifact by
  its position in the job's `outputFiles` list; the client never supplies a raw path
  — the recorded path is re-validated against the workspace root before anything is
  opened, and an out-of-range index or missing file both surface as a
  non-distinguishing 404

No WebSocket endpoint.

Typical pipeline: `probe → extract-audio → transcribe → russian-subtitles →
russian-dub-audio → mux` (each job stage's output feeds the next; `mux` leaves the
original untouched).

Job model: `id, userId, type, status (CREATED/RUNNING/COMPLETED/FAILED/CANCELLED),
inputFile, outputFiles[], createdAt, updatedAt, errorMessage, details{}`.

## 8. Main Internal Components

- `ProbeController`, `MediaJobController`, `MediaPipelineController`
- `ProbeService` — ffprobe-backed stream detection
- `AudioExtractionService`, `TranscriptionService`, `SubtitleService`,
  `DubbingService`, `MuxService` — one async job-submitting service per pipeline stage
- Provider abstractions with mock + real implementations: `AsrProvider`
  (`MockAsrProvider` / `WhisperCppAsrProvider`), a translation provider
  (mock echo `[RU] …` / `LlmTranslationProvider`), `TtsProvider`
  (`NeutralRussianTtsProvider` only — no real implementation yet)
- `MediaTextGuard` — neutralizes injection markers in media text before any
  translation/LLM use (mirrors `llm-service`'s `UntrustedTextGuard`)
- `WorkspaceManager` — the single chokepoint for workspace path validation
  (`validateArtifactPath`) and artifact size lookups
- `FFprobeCommandBuilder` / `FFmpegCommandBuilder` + `ProcessRunner` — no-shell,
  argument-list, hard-timeout subprocess execution
- `MediaJobService` + `MediaJobStore` implementations (`InMemoryMediaJobStore`,
  `FileBackedMediaJobStore`, `PostgresMediaJobStore`) — see §10
- `VoiceProfileFactory` — the only path that can mint a `USER_OWNED` voice profile,
  gated on both a config flag and per-request consent

## 9. Dependencies On Other Services

- `jarvis-common` — shared service-JWT/gateway-delegation security filter chain
- [`llm-service`](llm-service.md) — only when `media.translation.mode=llm`; requires
  a NetworkPolicy allowlist entry (not included by default) and carries a
  short-lived `SVC_INTERNAL` service JWT
- No mandatory downstream Jarvis service dependency when running with default mock
  providers
- [`agent-service`](agent-service.md)'s MEDIA role can prepare a job spec for this
  service but does not currently dispatch it (no cross-service wiring yet)

## 10. Data / Storage

`jarvis.media.job-store` selects the `MediaJobStore` implementation:

| Value | Survives pod restart? | Shared across replicas? | Notes |
|---|---|---|---|
| `memory` (default) | no | no | `InMemoryMediaJobStore` |
| `file` | yes (same pod/volume) | no | `FileBackedMediaJobStore` — one JSON file per job under `jarvis.media.job-store.dir` |
| `postgres` | yes | yes | `PostgresMediaJobStore` — one JSON-payload row per job, Flyway-migrated (`db/media/migration/V1__create_media_jobs.sql`); configure via `JARVIS_MEDIA_JOB_STORE=postgres`, `_JDBC_URL`, `_JDBC_USERNAME`, `_JDBC_PASSWORD` |

All three implement the same interface and are wired via `@ConditionalOnProperty`, so
switching stores never touches `MediaJobService` or any controller.
`DataSourceAutoConfiguration`/`FlywayAutoConfiguration` are excluded by default in
`application.yml` so the `postgres`/`jdbc` jars on the classpath have no effect while
the in-memory store is active.

Media artifacts (extracted audio, subtitles, dub audio, muxed output) live under
`media.workspace.dir` (`/tmp/jarvis-media` by default, an `emptyDir` — not persisted
across pod restarts regardless of which job store is used).

## 11. Security Model

- `/actuator/health|info|prometheus` is public; every media job endpoint requires
  authentication (shared `BaseSecurityConfig` — internal service, reached through the
  gateway or another service's token)
- All job/artifact access is owner-scoped (`userId`); artifact downloads re-validate
  the stored path against the workspace root before opening any file
- Media text (transcripts/subtitles/titles) is treated as untrusted data —
  `MediaTextGuard` neutralization and the `UNTRUSTED_DATA` envelope run
  unconditionally, independent of provider mode
- No unauthorized voice cloning: default dubbing is a generic neutral voice; a
  `USER_OWNED` profile requires both `media.tts.allow-user-voice-profile=true` and
  per-request `consentConfirmed=true`, and even then only synthesizes a neutral
  placeholder — never a real person's voice
- Every job is cancellable; jobs run async on a bounded executor
- Pod runs `runAsNonRoot`, `readOnlyRootFilesystem=true`, all capabilities dropped

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/media-service -am test
```

Build & deploy (from the module README):

```bash
mvn -pl apps/media-service -DskipTests -Dspotless.check.skip=true clean install \
  jib:build -Djib.to.image=localhost:5000/jarvis/media-service:<tag>
sudo k3s kubectl create -f k8s/base/media-service/deployment.yaml   # first deploy only
sudo k3s kubectl -n jarvis-prod set image deploy/media-service media-service=localhost:5000/jarvis/media-service:<tag>
```

Real ffmpeg/ffprobe/whisper.cpp binaries require the opt-in `real-media-image`
Maven profile (see `apps/media-service/docker/real-media-binaries/README.md`); the
default `jib:build` image ships none of them.

## 13. Implementation Status

Implemented and deployed (`k8s/base/media-service/deployment.yaml`, namespace
`jarvis-prod`, port 8095, replicas 1). ffprobe/ffmpeg/ASR/translation each have a
real implementation behind a flag (off by default); TTS remains mock-only.

## 14. Known Gaps / Caveats

- `memory` remains the default job store (resets on pod restart); `file`/`postgres`
  are opt-in.
- Real ffmpeg/ffprobe/whisper.cpp require the opt-in `real-media-image` build — the
  default image ships none of them, so prod runs mock unless explicitly built and
  deployed with the real image + flags.
- `media.translation.mode=llm` requires a NetworkPolicy allowlist entry to
  `llm-service` that is not included by default.
- Real neutral-TTS (Piper) is a flagged extension point, not wired yet — output-format
  alignment with the mux stage and the same opt-in native-binary story as ASR would
  both be needed first.
- The desktop JavaFX Control Center could host a media-jobs panel, but wiring it
  needs a gateway route + NetworkPolicy allowlist from the desktop app to this
  service — out of scope so far; the API in §7 is the integration surface for a
  future panel.
- `MediaTextGuard` mirrors `llm-service`'s `UntrustedTextGuard`; consolidating both
  into `jarvis-common` is a follow-up.
