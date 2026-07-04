# Media Service (EPIC 8 — Media / Russian Dubbing Assistant)

Inspects media files, detects audio/subtitle streams, generates Russian subtitles, and
prepares a **safe, neutral-voice** Russian dubbing pipeline. Increment C (C1–C8).

Port `8095` · package `org.jarvis.media` · Java 21 / Spring Boot 3.3 · **no database**
(in-memory job store) · feature flag `media.enabled`.

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

| Property | `mock` (default) | `real` |
|---|---|---|
| `media.ffprobe.mode` | canned stream fixture | `ffprobe` via ProcessRunner |
| `media.ffmpeg.mode` | writes placeholder outputs | `ffmpeg` via ProcessRunner |
| `media.asr.mode` | deterministic transcript | (whisper/Vosk — follow-up) |
| `media.translation.mode` | echo `[RU] …` | (LLM-backed — flagged follow-up) |
| `media.tts.mode` | neutral placeholder audio | (Piper/neutral RU TTS — follow-up) |

## Decision: ASR / translation / TTS ship as deterministic mocks (mock-MVP)

**Status: intentional, documented scope decision — not a bug or an oversight.**

`MockAsrProvider`, `MockTranslationProvider`, and `NeutralRussianTtsProvider` are the
**only** implementations of `AsrProvider` / `TranslationProvider` / `TtsProvider` that
exist in this codebase today. Each is deterministic (fixed/derived output, no model
inference, no network call) and is wired as the default via
`@ConditionalOnProperty(..., matchIfMissing = true)`. Unlike `media.ffprobe.mode` /
`media.ffmpeg.mode` — which already have working `real` implementations
(`RealFFprobeClient`, `RealFFmpegClient`) that are simply not the default — there is
**no `real` mode to switch to** for ASR/translation/TTS yet; setting e.g.
`media.asr.mode=whisper` yields zero `AsrProvider` beans (fails closed, see
`MediaProviderSelectionTest`) rather than silently degrading.

What "real" would require, per stage:

| Stage | Real implementation | Needs |
|---|---|---|
| ASR (`media.asr.mode`) | `whisper.cpp` (or Vosk, matching `voice-gateway`'s STT choices) | model weights on disk, a native/JNI or subprocess binding, decoded PCM/WAV input (which itself needs real `ffmpeg`) |
| Translation (`media.translation.mode`) | Local/self-hosted translation (or routed through `llm-service`, already `MediaTextGuard`-neutralized) | a translation model or an internal network call to `llm-service`, plus a NetworkPolicy allowlist change |
| TTS (`media.tts.mode`) | Piper neutral RU voice | Piper binary/model on disk, a subprocess/JNI binding, and output-format alignment with the mux stage |

Why deferred rather than built now:

- **No `ffmpeg`/native binaries in the base image.** The container image ships no
  `ffmpeg`, and by extension no `whisper.cpp`/Piper binaries either — the same
  constraint that already keeps `media.ffprobe.mode`/`media.ffmpeg.mode` on mock in
  production (see table above). Real ASR/TTS have the same dependency, plus their own
  multi-hundred-MB model files, so adding them means growing the base image for every
  dependent build/CI run, not just this service.
- **Cluster/disk constraints.** `k3s` (jarvis-prod) is the deploy target; adding model
  weights (whisper/Piper, hundreds of MB–GBs) and native binaries multiplies image size
  and node disk pressure across every media-service pod, with no autoscaling story yet.
- **Cross-service blast radius.** Real translation likely means calling `llm-service`,
  which means new NetworkPolicy allowlist entries — a deliberately separate, reviewable
  change (see `MediaTextGuard` consolidation note below), not bundled into this
  increment.

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

Job model: `id, userId, type, status (CREATED/RUNNING/COMPLETED/FAILED/CANCELLED),
inputFile, outputFiles[], createdAt, updatedAt, errorMessage, details{}`.

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

## Known limitations

- Jobs are in-memory (reset on pod restart) — no DB by design (keeps the service
  isolated: no migrations, no NetworkPolicy allowlist changes).
- Real ffmpeg/ffprobe/ASR/TTS require a base image with those binaries; prod runs mock.
- `MediaTextGuard` mirrors `llm-service`'s `UntrustedTextGuard`; consolidating both into
  `jarvis-common` is a follow-up.
- LLM-backed translation and real neutral-TTS are flagged extension points, not wired
  to avoid cross-service NetworkPolicy changes this increment.
