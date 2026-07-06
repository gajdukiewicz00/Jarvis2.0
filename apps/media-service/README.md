# Media Service (EPIC 8 — Media / Russian Dubbing Assistant)

Inspects media files, detects audio/subtitle streams, generates Russian subtitles, and
prepares a **safe, neutral-voice** Russian dubbing pipeline. Increment C (C1–C8).

Port `8095` · package `org.jarvis.media` · Java 21 / Spring Boot 3.3 · **no database
by default** (job history persists to local disk via the file-backed job store;
Postgres and the ephemeral in-memory store are both opt-in alternatives — see "Job
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
| `media.tts.mode` | neutral placeholder audio | `real` — Piper CLI via `PiperTtsProvider` |

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

### Increment G: real Piper TTS, duration-matching, audio merge, and quality reporting

**TTS — `media.tts.mode=real` (`PiperTtsProvider`).** Shells out to a Piper CLI
binary (`media.tts.binary`, PATH-resolved like `ffmpeg`/`whisper-cli` — default
`piper`) against a Russian neutral-voice ONNX model (`media.tts.voice-model-path`),
the same no-shell/argument-list/hard-timeout posture as the other real providers.
Text is written to a scratch file and piped into Piper's stdin via
`ProcessRunner`'s stdin-redirect overload (`piper --model <voice.onnx>
--output_file <out.wav> < text-file`) — never a shell, never a command-line
argument. Piper always synthesizes with the single configured neutral voice
model regardless of the requested `VoiceProfile`; this MVP never clones a real
person's voice, even for an otherwise-authorized `USER_OWNED` profile (see
"Safety & legal posture" above). Exactly like `WhisperCppAsrProvider`, this
provider checks that BOTH the binary and the voice model actually exist on disk
**before** ever spawning a subprocess; if either is missing it logs a warning and
falls back to `NeutralRussianTtsProvider`'s placeholder rather than failing every
dub job. The produced WAV header is parsed by `WavAudioUtil` (dependency-free RIFF
chunk walker) to compute the real synthesized duration, replacing the
text-length heuristic the mock uses. See `PiperTtsProviderTest` /
`WavAudioUtilTest`.

**Duration-matching (`SegmentTimingPlanner`, pure logic).** For each dub segment,
decides how its synthesized clip should fit its cue window: when the clip
overruns, playback speed is raised just enough to fit (clamped to `2.0`, ffmpeg's
single-stage `atempo` ceiling) — speech is only ever **sped up, never slowed
down**, since an artificially slowed voice reads as unnatural. When the clip
finishes early, the remainder becomes trailing silence instead. Any residual
overrun past the speed clamp is surfaced on the plan so the quality report can
flag a genuine desync risk. See `SegmentTimingPlannerTest`.

**Audio-track merge (`DubAudioMerger` / `DubAudioMergeCommandBuilder`).** Combines
every per-segment clip onto one continuous dub track: each clip is delayed
(`adelay`) to its planned timeline offset and sped up (`atempo`) per its timing
plan, then every delayed stream is combined with `amix` — no explicit silence
generation needed, since each clip's own delayed position naturally leaves the
gaps. Gated by the same `media.ffmpeg.mode` flag as `RealFFmpegClient` (it's the
same ffmpeg binary dependency): `MockDubAudioMerger` (default) writes a
placeholder; `RealDubAudioMerger` runs the real ffmpeg filter graph. See
`DubAudioMergeCommandBuilderTest` / `RealDubAudioMergerTest`.

**Quality report (`DubQualityChecker`).** Flags, per dub run: `missingTts`
(no audio produced), `durationMismatches` (synthetic duration drifts materially
from the cue), `overlappingSpeakers` (adjacent cues from different speakers
overlap), `tooLongSegments` (a cue itself exceeds `media.subtitle.max-segment-seconds`),
`lowConfidenceSegments` (source ASR confidence below `media.subtitle.min-confidence`),
and an overall `badSyncRisk` (large accumulated drift, or any segment still
overrunning its cue after the maximum speed-up). See `DubQualityCheckerTest`.

**Workspace cleanup (`WorkspaceCleanupService`).** A scheduled sweep (default every
30 minutes, `media.workspace.cleanup-interval-ms`) deletes first-level workspace
sub-directories whose newest file is older than `media.workspace.artifact-ttl-hours`
(default 24h) — job lifecycle/records themselves live in the separate
`MediaJobStore`, not on disk, so this never touches job history, only artifacts.
`cleanupExpiredArtifacts()` is also callable directly (with an injected `Clock`),
which is how it's unit tested. See `WorkspaceCleanupServiceTest`.

All three of the above (TTS, merge, cleanup) remain **off/safe by default**:
`media.tts.mode` stays `mock`, `media.ffmpeg.mode` stays `mock`, and the cleanup
sweep only ever deletes artifacts past their TTL.

### Real ffmpeg/ffprobe/whisper.cpp/Piper binaries in the image

The container base image still ships none of these by default — `mvn ... jib:build`
produces the same mock-only image as before. To build an image that CAN run
`media.ffprobe.mode=real` / `media.ffmpeg.mode=real` / `media.asr.mode=whisper` /
`media.tts.mode=real` for real, use the opt-in `real-media-image` Maven profile,
which copies statically-linked binaries (no Dockerfile needed — see
`docker/real-media-binaries/README.md`) straight into the image via Jib's
`extraDirectories`:

```bash
# 1) populate docker/real-media-binaries/{bin,models,voices}/ per its README, then:
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

## Decision: every provider now has a flagged real mode; mock stays the safe default everywhere

**Status: intentional, documented scope decision — not a bug or an oversight.**

As of Increment G, `AsrProvider`, `TranslationProvider`, and `TtsProvider` each have
a second, real implementation (`WhisperCppAsrProvider`, `LlmTranslationProvider`,
`PiperTtsProvider`), all off by default and all falling back safely
(ASR/TTS — degrade to the mock's deterministic output when the binary/model isn't
on disk) or failing closed (translation — raises rather than silently shipping
untranslated text). `media.tts.mode=real` requires BOTH a Piper binary
(`media.tts.binary`) AND a Russian voice model (`media.tts.voice-model-path`) to
exist on disk before it ever spawns a subprocess — exactly the same posture as
`WhisperCppAsrProvider` for ASR. Real Piper/ffmpeg binaries and models are a
hardware/deploy concern (see "Real ffmpeg/ffprobe/whisper.cpp/Piper binaries in the
image" above), not something this module's default test suite depends on.

This mirrors the safety posture in the section above: the mock-MVP is not a shortcut
that skips guardrails — `MediaTextGuard` neutralization and the untrusted-data envelope
run unconditionally regardless of provider mode, so turning on a real translation (or
ASR, or TTS) backend does not require re-deriving the injection-safety story.

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
| GET | `/api/v1/media/status` | current `enabled` flag, effective job-store mode, and each provider's `mock`/real mode — lets a UI clearly show mock vs real |

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

## Job store: file (default) / memory / Postgres

`jarvis.media.job-store` selects the `MediaJobStore` implementation:

| Value | Implementation | Survives pod restart? | Shared across replicas? |
|---|---|---|---|
| `file` (default) | `FileBackedMediaJobStore` — one JSON file per job under `jarvis.media.job-store.dir` | yes (same pod/volume) | no |
| `memory` | `InMemoryMediaJobStore` | no | no |
| `postgres` | `PostgresMediaJobStore` — one JSON-payload row per job, migrated via Flyway (`db/media/migration`) | yes | yes |

Leaving the property unset (the common case) now gets you the file-backed store —
job history is durable across a pod restart with no database to provision, mirroring
the `jarvis.agent.task-store` pattern in agent-service. Set
`JARVIS_MEDIA_JOB_STORE=memory` to opt back into the ephemeral in-memory store (e.g.
a throwaway dev container with no writable volume).

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

- `file` is the default job store (persists to `jarvis.media.job-store.dir`, default
  `/tmp/jarvis-media-jobs`); `memory` (ephemeral) and `postgres` (shared across
  replicas) are both opt-in (see above) — no DB is provisioned unless explicitly
  configured, keeping the service's default footprint unchanged.
- Real ffmpeg/ffprobe/whisper.cpp/Piper require the opt-in `real-media-image` build
  (see above); the default image still ships none of them, so prod runs mock unless
  explicitly built and deployed with the real image + flags.
- `media.translation.mode=llm` requires a NetworkPolicy allowlist entry to
  `llm-service`, not included in this change (a deliberately separate, reviewable
  change, same reasoning as before).
- `MediaTextGuard` mirrors `llm-service`'s `UntrustedTextGuard`; consolidating both into
  `jarvis-common` is a follow-up.
- `WorkspaceCleanupService`'s TTL sweep only considers a directory's newest file
  mtime, not job status — a genuinely long-running job whose workspace directory
  goes untouched for longer than the TTL (default 24h) could in principle be swept;
  in practice per-segment TTS/merge writes keep the directory's mtime fresh for the
  duration of any real job, but this is a known simplification, not a hard guarantee.
