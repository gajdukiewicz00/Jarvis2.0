# Media Service (EPIC 8 — Media / Russian Dubbing Assistant)

Inspects media files, detects audio/subtitle streams, generates Russian subtitles, and
prepares a **safe, neutral-voice** Russian dubbing pipeline. Increment C (C1–C8).

Port `8095` · package `org.jarvis.media` · Java 21 / Spring Boot 3.3 · **no database
by default** (in-memory job store; Postgres is an opt-in alternative — see "Job
store" below) · feature flag `media.enabled`.

## Safety & legal posture (non-negotiable)

- **No unauthorized voice cloning.** Default dubbing uses a generic neutral Russian
  synthetic voice. A `USER_OWNED` voice profile is only mintable through
  `VoiceProfileFactory`, which requires BOTH `media.tts.allow-user-voice-profile=true`
  AND a per-request `consentConfirmed=true`. Even then the MVP synthesizes a neutral
  placeholder — it never reproduces a real person's voice and never claims to be the
  original actor.
- **Originals are preserved.** Extraction and muxing read the source as an input only
  and always write a separate output file. Mux copies (`-c copy`) every original stream.
- **Media text is untrusted DATA.** Transcripts/subtitles/titles pass through
  `MediaTextGuard` (neutralizes injection markers, can wrap in an UNTRUSTED_DATA
  envelope) before any translation/LLM use. They never become instructions.
- **No shell.** Every ffprobe/ffmpeg call is built as an argument list
  (`FFprobeCommandBuilder` / `FFmpegCommandBuilder`) and run via `ProcessRunner`
  (`ProcessBuilder`, no shell, hard timeout). A hostile filename stays one argument.
- **Path traversal blocked.** `WorkspaceManager` is the single chokepoint: artifacts
  are written only inside the workspace; inputs must resolve inside the workspace or a
  configured input root. `..`, null bytes, and escapes are rejected (400).
- **Every job is cancellable** (cooperative `CancellationToken` + worker interrupt).
- **Async only.** Jobs run on a bounded media executor; HTTP threads never block.

## Provider modes (all default to `mock`)

The container image ships no ffmpeg binary, so production defaults to mock providers;
the real implementations exist and are unit-tested for safe argument construction.

| Property | `mock` (default) | real value |
|---|---|---|
| `media.ffprobe.mode` | canned stream fixture | `real` — `ffprobe` via ProcessRunner |
| `media.ffmpeg.mode` | writes placeholder outputs | `real` — `ffmpeg` via ProcessRunner |
| `media.asr.mode` | deterministic transcript | `whisper` — whisper.cpp CLI via `WhisperCppAsrProvider` |
| `media.translation.mode` | echo `[RU] …` | `llm` — routed to llm-service via `LlmTranslationProvider` |
| `media.tts.mode` | neutral placeholder audio | (Piper/neutral RU TTS — still a follow-up) |

### Increment F: real ASR and real translation (both still off by default)

**ASR — `media.asr.mode=whisper` (`WhisperCppAsrProvider`).** Shells out to a
whisper.cpp CLI binary (`media.asr.binary`, PATH-resolved like `ffmpeg`/`ffprobe` —
default `whisper-cli`) against a GGML model (`media.asr.model-path`), the same
no-shell/argument-list/hard-timeout posture as `RealFFmpegClient`. Output is parsed
from whisper.cpp's `-oj` JSON format by `WhisperJsonParser`. Critically, this
provider checks that BOTH the binary and the model file actually exist on disk
**before** ever spawning a subprocess; if either is missing it logs a warning and
falls back to the exact same deterministic output as `MockAsrProvider` rather than
failing every job — so flipping this flag in an environment that hasn't opted into
the real-binary image yet (see below) degrades gracefully instead of breaking
transcription. See `MediaProviderSelectionTest`-style coverage in
`WhisperCppAsrProviderTest`.

**Translation — `media.translation.mode=llm` (`LlmTranslationProvider`).** Routes
each already-neutralized transcript segment (see `MediaTextGuard`) to llm-service's
`/api/v1/llm/chat`, re-wrapping it in the UNTRUSTED_DATA envelope before it enters
the prompt (defense in depth — the caller already neutralizes, this provider never
assumes that always happens upstream). Requires a NetworkPolicy allowlist entry to
`llm-service` and carries a short-lived `SVC_INTERNAL` service JWT
(`RestClientConfig`, gated on this same property so the `RestTemplate` bean and its
network dependency don't exist at all while translation stays on `mock`). Fails
closed: an unreachable llm-service or an empty reply throws `TranslationException`,
failing that job rather than silently shipping untranslated English text as if it
were Russian.

Both remain **off by default**; `MockAsrProvider`/`MockTranslationProvider` are
still what a fresh checkout runs with zero configuration.

### Real ffmpeg/ffprobe/whisper.cpp binaries in the image

The container base image still ships none of these by default — `mvn ... jib:build`
produces the same mock-only image as before. To build an image that CAN run
`media.ffprobe.mode=real` / `media.ffmpeg.mode=real` / `media.asr.mode=whisper` for
real, use the opt-in `real-media-image` Maven profile, which copies
statically-linked binaries (no Dockerfile needed — see
`docker/real-media-binaries/README.md`) straight into the image via Jib's
`extraDirectories`:

```bash
# 1) populate docker/real-media-binaries/{bin,models}/ per its README, then:
mvn -pl apps/media-service -am -DskipTests clean install \
  -Preal-media-image jib:build -Djib.image.tag=<tag>
```

An alternative to baking binaries into the media-service image at all is a
**sidecar container** in the same pod: run `ffmpeg`/`whisper.cpp` in a separate
container image (built from an upstream ffmpeg/whisper.cpp image) sharing an
`emptyDir` volume with media-service, with `media.ffmpeg.binary` /
`media.asr.binary` pointed at the shared mount path. Not wired up here (it's a
`k8s/` manifest change, outside this module), but it is a reasonable alternative to
the `real-media-image` profile if keeping the JVM image itself minimal matters more
than a one-image deploy.

## Decision: TTS still ships as a deterministic mock; ASR/translation now have flagged real modes

**Status: intentional, documented scope decision — not a bug or an oversight.**

As of Increment F, `AsrProvider` and `TranslationProvider` each have a second, real
implementation (`WhisperCppAsrProvider`, `LlmTranslationProvider` — see "Increment F"
above), both off by default and both falling back safely (whisper) or failing closed
(translation) rather than silently degrading. `TtsProvider` still has only
`NeutralRussianTtsProvider`; setting `media.tts.mode=piper` still yields zero
`TtsProvider` beans (fails closed, see `MediaProviderSelectionTest`) because no real
implementation exists yet.

What "real" TTS would require:

| Stage | Real implementation | Needs |
|---|---|---|
| TTS (`media.tts.mode`) | Piper neutral RU voice | Piper binary/model on disk, a subprocess/JNI binding, and output-format alignment with the mux stage |

Why TTS is deferred rather than built now:

- **No native binaries in the default base image.** The default image still ships no
  `ffmpeg`/`whisper.cpp`/Piper binaries — real ASR now follows the same opt-in image
  pattern (`real-media-image` Maven profile) that `media.ffprobe.mode=real` /
  `media.ffmpeg.mode=real` already used; Piper would need the same treatment plus its
  own multi-hundred-MB model files.
- **Cluster/disk constraints.** `k3s` (jarvis-prod) is the deploy target; adding Piper
  model weights and native binaries multiplies image size and node disk pressure
  across every media-service pod opting into the real-binary image, with no
  autoscaling story yet.
- **Output-format alignment.** Piper's output needs to line up with the mux stage's
  expected dub-audio format; that's a small but real integration surface not yet
  built, unlike ASR/translation which slot directly into existing artifact contracts
  (`Transcript` / translated segment text).

This mirrors the safety posture in the section above: the mock-MVP is not a shortcut
that skips guardrails — `MediaTextGuard` neutralization and the untrusted-data envelope
run unconditionally regardless of provider mode, so turning on a future real
translation backend does not require re-deriving the injection-safety story.

## API

All endpoints require gateway/service authentication except `/actuator/health`.
Job endpoints are disabled (503) when `media.enabled=false`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/media/probe` | **synchronous** stream detection + main-audio selection |
| POST | `/api/v1/media/jobs/extract-audio` | extract selected audio → WAV/FLAC |
| POST | `/api/v1/media/jobs/transcribe` | ASR transcript (timed segments) |
| POST | `/api/v1/media/jobs/russian-subtitles` | RU SRT + VTT + RU transcript, with quality warnings |
| POST | `/api/v1/media/jobs/russian-dub-audio` | neutral RU dub audio + quality report |
| POST | `/api/v1/media/jobs/mux` | add RU sub/audio to a **copy** of the original |
| GET | `/api/v1/media/jobs` | list caller's jobs |
| GET | `/api/v1/media/jobs/{id}` | job status + artifacts + details |
| POST | `/api/v1/media/jobs/{id}/cancel` | cancel a job |
| GET | `/api/v1/media/jobs/{id}/artifacts/{index}` | download one produced artifact (subtitle/audio/muxed file) |

Job model: `id, userId, type, status (CREATED/RUNNING/COMPLETED/FAILED/CANCELLED),
inputFile, outputFiles[], createdAt, updatedAt, errorMessage, details{}`.

### Artifact download

`GET /api/v1/media/jobs/{id}/artifacts/{index}` streams the artifact at that
position in the job's (owner-scoped) `outputFiles` list as a file download, with a
best-effort `Content-Type` derived from the artifact's recorded `contentType` and a
sanitized `Content-Disposition: attachment; filename="..."`. The client never
supplies a raw path — only a job id (ownership-checked, same as every other job
endpoint) and a small integer index — and the artifact's recorded path is
re-validated against the workspace root (`WorkspaceManager.validateArtifactPath`)
before anything is opened, so a tampered/out-of-workspace path in a persisted job
record is rejected (400) rather than served. An out-of-range index or a missing
file on disk both surface as 404 (`ArtifactNotFoundException`) without
distinguishing the two, so a caller can't fingerprint workspace layout by trial and
error.

### Typical pipeline

```
probe(movie.mkv) -> pick audio index
extract-audio -> audio.wav
transcribe(audio.wav) -> transcript.json
russian-subtitles(transcript.json) -> subtitles.ru.srt + .vtt + transcript.ru.json
russian-dub-audio(transcript.ru.json) -> dub.ru.wav (+ quality report)
mux(movie.mkv, subtitles.ru.srt, dub.ru.wav) -> output.mkv  (original untouched)
```

## Desktop HUD (C8)

The JavaFX Control Center could host a media-jobs panel, but wiring it requires the
desktop app to reach this service (gateway route + NetworkPolicy allowlist), which is
out of scope for Increment C. **Decision: document the API (this file) rather than force
a HUD panel.** The job endpoints above are the integration surface for a future panel.

## Build & deploy

```bash
# build image
mvn -pl apps/media-service -DskipTests -Dspotless.check.skip=true clean install \
  jib:build -Djib.to.image=localhost:5000/jarvis/media-service:<tag>

# first deploy (new service — create, never `kubectl apply` the whole overlay)
sudo k3s kubectl create -f k8s/base/media-service/deployment.yaml
# subsequent image updates
sudo k3s kubectl -n jarvis-prod set image deploy/media-service media-service=localhost:5000/jarvis/media-service:<tag>
```

## Job store: memory (default) / file / Postgres

`jarvis.media.job-store` selects the `MediaJobStore` implementation:

| Value | Implementation | Survives pod restart? | Shared across replicas? |
|---|---|---|---|
| `memory` (default) | `InMemoryMediaJobStore` | no | no |
| `file` | `FileBackedMediaJobStore` — one JSON file per job under `jarvis.media.job-store.dir` | yes (same pod/volume) | no |
| `postgres` | `PostgresMediaJobStore` — one JSON-payload row per job, migrated via Flyway (`db/media/migration`) | yes | yes |

All three implement the same `MediaJobStore` interface and are wired via
`@ConditionalOnProperty`, so switching stores never touches `MediaJobService` or any
controller. `PostgresMediaJobStore` deliberately stores each job as a single JSON
payload column (mirroring `FileBackedMediaJobStore`'s one-file-per-job approach)
rather than modeling `details{}`/`outputFiles[]` as relational columns — there is
exactly one access pattern (save / find-by-id / find-by-user-sorted), so a wider
schema would add migration churn without adding query power. Configure with
`JARVIS_MEDIA_JOB_STORE=postgres`, `JARVIS_MEDIA_JOB_STORE_JDBC_URL`,
`_JDBC_USERNAME`, `_JDBC_PASSWORD`.

## Known limitations

- `memory` remains the default job store (resets on pod restart); `file` and
  `postgres` are opt-in (see above) — no DB is provisioned unless explicitly
  configured, keeping the service's default footprint unchanged.
- Real ffmpeg/ffprobe/whisper.cpp require the opt-in `real-media-image` build (see
  above); the default image still ships none of them, so prod runs mock unless
  explicitly built and deployed with the real image + flags.
- `media.translation.mode=llm` requires a NetworkPolicy allowlist entry to
  `llm-service`, not included in this change (a deliberately separate, reviewable
  change, same reasoning as before).
- `MediaTextGuard` mirrors `llm-service`'s `UntrustedTextGuard`; consolidating both into
  `jarvis-common` is a follow-up.
- Real neutral-TTS (Piper) remains a flagged extension point, not wired yet — see
  the TTS section above.
