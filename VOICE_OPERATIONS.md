# Voice Operations

Verification snapshot: 2026-03-27

## What You Need

Required for the core verified voice runtime:

- Java
- Maven
- curl
- python3
- the local runtime env file at `~/.jarvis/run/local-runtime/local.env`

Required for the canonical local full-audio stack:

- `JARVIS_STT_PROVIDER=vosk`
- Vosk RU and EN models under `~/.jarvis/models/stt/vosk/...`
- `TTS_PROVIDER=espeak`
- `JARVIS_TTS_ESPEAK_BINARY=~/.jarvis/tools/bin/espeak-ng`

Optional provider-dependent alternatives:

- `JARVIS_STT_PROVIDER=whisper` plus a Whisper model file
- `TTS_PROVIDER=google` plus valid `GOOGLE_APPLICATION_CREDENTIALS`

Optional only:

- `PORCUPINE_ACCESS_KEY` for wake-word

## Important Env Knobs

- `JARVIS_USE_TLS`
- `JARVIS_STT_PROVIDER`
- `JARVIS_MODELS_DIR`
- `JARVIS_VOSK_MODEL_PATH_RU`
- `JARVIS_VOSK_MODEL_PATH_EN`
- `JARVIS_VOICE_WHISPER_MODEL_PATH`
- `TTS_ENABLED`
- `TTS_PROVIDER`
- `JARVIS_TTS_ESPEAK_BINARY`
- `GOOGLE_APPLICATION_CREDENTIALS`

The canonical local env is generated and normalized by `scripts/runtime/common.sh`.

## Start And Inspect

1. Bootstrap the canonical local full-audio stack:

```bash
./scripts/setup-voice-local.sh
```

2. Validate the machine honestly:

```bash
./scripts/check-local-env.sh
```

3. Bring up the local runtime:

```bash
./scripts/runtime-up.sh
```

4. Inspect the live voice truth endpoint:

```bash
curl http://127.0.0.1:8080/api/v1/voice/runtime
```

If `JARVIS_USE_TLS=true`, use `https://127.0.0.1:8080/api/v1/voice/runtime` and the local CA/truststore created by the runtime scripts.

5. Run the dedicated local full-audio smoke:

```bash
JARVIS_VOICE_LOCAL_SMOKE_STOP_ON_EXIT=true ./scripts/voice-local-smoke.sh
```

6. Run the broader runtime smoke:

```bash
JARVIS_RUNTIME_SMOKE_SKIP_LLM=true JARVIS_SKIP_BUILD=true ./scripts/runtime-smoke.sh
```

7. Run the relevant service tests:

```bash
mvn -q -pl apps/voice-gateway,apps/api-gateway,apps/orchestrator,apps/planner-service test
```

## Public Paths

HTTP:

- `/api/v1/voice/command`
- `/api/v1/voice/transcribe?language=ru-RU|en-US`
- `/api/v1/voice/transcribe/stream?language=ru-RU|en-US`
- `/api/v1/voice/synthesize`
- `/api/v1/voice/runtime`

WebSocket:

- `/ws/voice`

Internal voice paths:

- `/internal/voice/notify`
- `/internal/voice/pc-action`
- `/internal/voice/orchestrator-intent`
- `/internal/voice/smart-home-action`

## What The Smoke Actually Proves

`scripts/runtime-smoke.sh` currently proves:

- public voice WebSocket transport connects
- public `/api/v1/voice/command` returns a real orchestrator-backed reply
- `/api/v1/voice/runtime` reports a coherent voice status document
- planner reminders reach the voice WebSocket notification path

It does not prove studio-grade audio quality. It proves wiring and honest degradation.

`scripts/voice-local-smoke.sh` proves the canonical local full-audio stack:

- `setup-voice-local.sh` leaves the machine in `full-audio ready`
- `/api/v1/voice/runtime` reports `status=ready` and `localDefaultStack.id=vosk+espeak-ng`
- `/api/v1/voice/synthesize` returns `audio/wav` from the local `espeak` path
- canonical local synthesis is normalized to `16 kHz mono PCM WAV`
- `/api/v1/voice/transcribe?language=en-US` accepts that WAV and returns a non-empty transcript
- voice WS `STATE` advertises `sttAvailable=true` and `ttsAvailable=true`
- planner reminders produce both text and binary audio frames on the voice WebSocket

## How To Read Runtime Status

Use `/api/v1/voice/runtime`.

Important fields:

- `status`: `ready` or `partial`
- `localDefaultStack`: canonical supported local audio stack and whether it is ready on this machine
- `routing`: public/internal truth
- `maturity`: verified vs provider-dependent vs optional
- `stt`: selected provider and language/model state
- `tts`: configured provider, effective provider, degraded/unavailable reason
- `preRecorded`: whether assets are enabled and how many are loaded

## How To Read TTS Responses

`POST /api/v1/voice/synthesize` now returns these headers on success:

- `X-Jarvis-Tts-Configured-Provider`
- `X-Jarvis-Tts-Actual-Provider`
- `X-Jarvis-Tts-Status`
- `X-Jarvis-Tts-Reason`

If no provider is available, the endpoint fails honestly instead of pretending to synthesize.

For the canonical local stack, the response body is normalized to `16 kHz mono PCM WAV` so it is directly compatible with the strict HTTP STT upload path.

## Honest Failure Modes

- Missing STT model: `503 STT_UNAVAILABLE` or `ttsAvailable/sttAvailable=false` in runtime/WS state, depending on path
- Missing TTS binary or cloud credentials: `503 TTS_UNAVAILABLE` for synthesize, text-only degradation for WS responses when no pre-recorded asset exists
- Missing WAV validation requirements: `400 INVALID_REQUEST`
- No speech recognized: `422 NO_SPEECH_RECOGNIZED`

## Current Workspace Snapshot

Checked on 2026-03-27:

- local STT was configured as `vosk`
- local Vosk models were normalized to `~/.jarvis/models/stt/vosk`
- local TTS was configured as `espeak`
- `./scripts/setup-voice-local.sh` installed a rootless `espeak-ng` bundle under `~/.jarvis/tools`
- `./scripts/voice-local-smoke.sh` passed end-to-end

In other words: this workstation now has one real default local full-audio path that is runnable and checkable without manual setup.
