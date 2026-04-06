# Voice Gaps

Verification snapshot: 2026-03-27

## Fixed In This Pass

- Public voice contract now includes `/api/v1/voice/synthesize` and `/api/v1/voice/runtime` at `api-gateway`.
- Voice command smoke no longer accepts the old placeholder reply.
- HTTP STT upload paths now return structured success/failure instead of vague strings.
- Missing Vosk or Whisper models now fail honestly instead of quietly acting like empty recognition.
- TTS now reports configured provider, effective provider, degraded state, and explicit unavailability.
- `voice-gateway` exposes a runtime truth endpoint instead of forcing operators to infer readiness from logs.
- WebSocket `STATE` now advertises both `sttAvailable` and `ttsAvailable`.
- Desktop voice runtime now tracks TTS degradation as text-only mode.
- Local env checking now warns when TTS/STT providers are configured but not actually usable.
- Runtime smoke now checks the live voice runtime status document.
- A canonical local full-audio stack is now fixed as `vosk + espeak-ng`.
- `scripts/setup-voice-local.sh` now bootstraps local voice dependencies, model paths, and a rootless `espeak-ng` bundle without sudo.
- `scripts/voice-local-smoke.sh` now proves the canonical local full-audio path end-to-end.
- Local `espeak` synthesis is normalized to `16 kHz mono PCM WAV`, so the default TTS output is directly accepted by the strict HTTP STT upload path.
- STT upload paths now accept an explicit `language` query parameter instead of silently hardcoding Russian.

## Remaining Blockers

### 1. Non-default providers are still provider-dependent

The canonical local path is now verified. Alternative providers are still optional and external.

- Whisper still depends on its own model/JNI path
- Google TTS needs credentials

This means non-default provider paths should still be described as `provider-dependent`, not universally ready.

### 2. WebSocket transport is verified, but not every long-running audio scenario is equally proven

What is verified:

- connection
- transcript/result messaging
- text response routing
- notification delivery
- canonical local audio delivery with `vosk + espeak-ng`

What is still only partial:

- stable audio quality across real microphones and real long-running sessions
- every per-command binary-audio edge case across provider failures and reconnect storms

### 3. Notification audio is still best-effort per response key

Reminder text delivery is verified. Audio delivery depends on pre-recorded coverage or a working TTS provider.

### 4. Legacy OpenAPI files remain skeletal

`docs/openapi/voice-gateway.yaml` and `docs/openapi/orchestrator.yaml` are not complete API truth today. Treat the new `VOICE_*` docs and `/api/v1/voice/runtime` as authoritative until the OpenAPI specs are fully rebuilt.

### 5. Wake-word is still optional

Porcupine and always-on behavior should not be used as a readiness argument for the core voice layer.

## Separate Projects, Not Voice-Core Blockers

- wake-word hardening
- production-grade cloud TTS rollout
- bundling the canonical local STT models into a release artifact instead of downloading them at setup time
- large-scale voice UX tuning on the desktop client

## Recommended Next Work

1. Rebuild the voice OpenAPI specs from real controllers and query parameters.
2. Add a dedicated end-to-end WS test that asserts text-only degradation when TTS is unavailable.
3. Add environment-specific verification profiles: local-text-only, local-full-audio, cluster-TLS.
4. Decide whether the canonical local `vosk` models should remain download-on-setup or become a packaged artifact in release tooling.
