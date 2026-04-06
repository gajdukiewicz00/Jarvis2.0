# Voice Architecture

Verification snapshot: 2026-03-27

## Public Edge

- Public HTTP base path: `/api/v1/voice`
- Public WebSocket path: `/ws/voice`
- Public owner: `apps/api-gateway`
- Internal voice owner: `apps/voice-gateway`

`api-gateway` is the external edge. It proxies voice traffic. It does not perform voice intelligence. `voice-gateway` owns transport/session behavior, STT/TTS, voice assets, and routing into the orchestrator.

## Real Flows

### Text Command

`client -> api-gateway /api/v1/voice/command -> voice-gateway /api/v1/voice/command -> orchestrator -> text reply`

This is the most mature path. It is verified by controller tests, runtime smoke, and the TLS product smoke.

### WebSocket Voice Session

`client -> api-gateway /ws/voice -> voice-gateway /ws/voice`

Inside `voice-gateway`:

`binary PCM -> selected STT session -> transcript -> intent/rule routing -> orchestrator -> RESPONSE text -> pre-recorded asset or TTS audio`

Transport is verified. Audio recognition and audio playback are verified for the canonical local `vosk + espeak-ng` stack and remain provider-dependent for alternative providers.

### HTTP STT Upload

- `/api/v1/voice/transcribe?language=ru-RU|en-US`: multipart WAV, validated as 16 kHz mono PCM
- `/api/v1/voice/transcribe/stream?language=ru-RU|en-US`: raw PCM octet stream

Both paths call STT, return structured status, and attempt to forward recognized text to the orchestrator.

### Voice Notification

`planner-service -> voice-gateway /internal/voice/notify -> active voice WS session(s)`

The notification is always text-delivered to active sessions. Audio delivery depends on pre-recorded/TTS availability.

### Audio Output

`response key/action -> pre-recorded asset lookup -> if missing, TTS -> if TTS unavailable, text-only`

Pre-recorded assets and TTS are separate layers. They are no longer treated as one opaque "voice works" bucket.

For the canonical local stack, `espeak-ng` synthesis is normalized to `16 kHz mono PCM WAV` so the audio output is directly compatible with the strict HTTP STT upload path.

## Voice Truth Map

| Voice component | Real responsibility | Entry point / path | Depends on | Called by | Calls whom | Real status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| API voice HTTP proxy | Public voice ingress for command/STT/TTS/runtime | `/api/v1/voice/*` | `voice-gateway` availability | clients | `voice-gateway` | `verified` | public edge is `api-gateway`, not `voice-gateway` |
| API voice WS proxy | Public WebSocket transport bridge | `/ws/voice` | `voice-gateway` WS and auth headers | desktop/client | `voice-gateway /ws/voice` | `verified` | forwards user context and service token |
| Voice text command controller | Real text-command route | `voice-gateway /api/v1/voice/command` | orchestrator | api-gateway | orchestrator | `verified` | legacy placeholder reply is rejected by smoke |
| Voice runtime controller | Runtime truth endpoint | `voice-gateway /api/v1/voice/runtime` | STT/TTS/asset registries | api-gateway, operators | none | `verified` | exposes routing and maturity |
| Voice WebSocket handler | Session transport, transcript/result messaging, notification fanout | `voice-gateway /ws/voice` | STT, orchestrator, assets, TTS | api-gateway WS proxy | orchestrator, asset/TTS services | `verified` | canonical local audio path is smoke-proven; initial `STATE` includes `sttAvailable` and `ttsAvailable` |
| HTTP STT controller | WAV validation, raw PCM transcription, transcript forwarding | `/api/v1/voice/transcribe`, `/api/v1/voice/transcribe/stream` | selected STT provider | api-gateway | STT, orchestrator | `verified` for canonical local `vosk`, `provider-dependent` otherwise | explicit `language` query parameter, structured 422/503 responses |
| STT provider bean | Speech recognition | selected by `jarvis.stt.provider` | Vosk models or Whisper model/JNI | voice controllers, WS handler | none | `verified` for canonical local `vosk`, `provider-dependent` otherwise | missing models now surface explicit unavailable errors |
| TTS service | Speech synthesis and degradation reporting | internal service, publicized by `/api/v1/voice/synthesize` | local `espeak-ng` bundle or cloud creds | TTS controller, voice output service | local binary or Google TTS | `verified` for canonical local `espeak`, `provider-dependent` otherwise | configured vs effective provider is explicit; canonical local output is normalized to `16 kHz mono PCM WAV` |
| VoiceOutputService | Pre-recorded first, TTS second | internal service | asset manifest, response registry, TTS | WS command/notification path | asset loader, TTS | `verified` fallback logic | text-only if no asset and no TTS |
| Voice asset loader | Loads classpath WAV assets | `voice-assets/manifest.yaml` | packaged assets | voice output service | none | `verified` | 95 active assets in current manifest |
| WAV response registry | Maps response keys to asset/text variants | `voice-response-registry.yaml` | packaged registry | voice output/rule layer | none | `verified` | 109 response profiles in current registry |
| Rule command layer | Direct routing for known voice commands | packaged YAML command catalog | configured commands and action dispatchers | WS command path | PC/smart-home/orchestrator gateways | `verified` | distinct from orchestrator fallback path |
| Internal notification controller | Push text/audio notifications into active voice sessions | `/internal/voice/notify` | active WS sessions | planner-service | WS handler | `verified-text` | audio is best-effort |
| Planner voice client | Origin of reminder voice notifications | planner internal HTTP client | voice-gateway internal auth | planner scheduler/controller | `voice-gateway /internal/voice/notify` | `verified` | covered by runtime smoke |
| Orchestrator boundary | Real response generation and fallback phrases | `/api/v1/orchestrator/execute` and intent client calls | orchestrator availability | voice-gateway | downstream action handlers | `verified` for text | voice layer is transport/session, not the brain |
| Desktop voice WS client | Consumes transcripts, responses, and binary audio | `apps/desktop-client-javafx` | WS contract | user desktop | api-gateway WS | `verified` contract | now tracks STT and TTS degradation separately |
| Runtime smoke | Operational proof for main voice paths | `scripts/runtime-smoke.sh` | local runtime | operator/CI | public API and WS | `verified` | checks command path, runtime status, and notifications |
| Local full-audio smoke | Operational proof for the canonical local voice stack | `scripts/voice-local-smoke.sh` | local runtime, Vosk models, rootless `espeak-ng` | operator/CI | public API and WS | `verified` | checks setup, runtime truth, synthesize, transcribe, WS `STATE`, and notification audio |

## Contract Notes

- `voice-gateway` is not a public edge even though it exposes `/api/v1/voice/*` internally.
- Full WebSocket audio readiness is not implied by the existence of `/ws/voice`.
- The voice layer is not the primary assistant brain. It transports, recognizes, synthesizes, and routes into the orchestrator.
