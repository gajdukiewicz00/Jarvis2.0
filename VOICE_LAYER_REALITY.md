# Voice Layer Reality

Verification snapshot: 2026-03-27

This file is the source of truth for Jarvis voice maturity. It is based on real code paths, runtime scripts, tests, and a checked local environment. It is not based on README claims or architecture sketches.

## Scope

In scope:

- `apps/api-gateway` voice HTTP and WebSocket proxy paths
- `apps/voice-gateway`
- STT provider selection and runtime behavior
- TTS provider selection and runtime behavior
- pre-recorded voice assets and their fallback path
- planner -> voice notification delivery
- desktop/backend WebSocket contract where it participates in the real voice loop
- voice-related runtime scripts, smoke checks, and tests

Out of core scope:

- wake-word / Porcupine readiness
- Android as a separate product
- LLM internals outside the real voice boundary
- unrelated smart-home, memory, or CV features

## Status Vocabulary

- `verified`: confirmed by code plus tests or smoke without hidden provider assumptions
- `partial`: real path exists, but only part of it is verified or it still depends on external setup
- `provider-dependent`: path is real, but only works when declared models, binaries, or cloud credentials are present
- `optional`: not required for core voice readiness

## Definition Of Done

| Criterion | Required state | Current status |
| --- | --- | --- |
| Public voice entrypoint is unambiguous | `api-gateway` owns public HTTP and WS voice ingress | `verified` |
| Internal voice boundary is unambiguous | `voice-gateway` owns voice session/STT/TTS/notification internals | `verified` |
| Public and internal routes agree | `api-gateway` proxy paths match `voice-gateway` endpoints | `verified` |
| WebSocket path is real and honest | `/ws/voice` transport works and advertises STT/TTS availability honestly | `verified` for transport, `provider-dependent` for audio |
| Text command path is not fake | `/api/v1/voice/command` returns orchestrator-backed text, not placeholders | `verified` |
| HTTP STT upload path is explicit | returns structured success/failure and does not hide missing providers | `verified` contract, `provider-dependent` runtime |
| STT selection is honest | missing models produce explicit unavailable state, not silent empty success | `verified` |
| TTS selection is honest | configured vs effective provider and degraded mode are visible | `verified` |
| Pre-recorded assets are separate from TTS | asset lookup is distinct from synthesis fallback | `verified` |
| Voice notifications are routed and checkable | planner -> voice-gateway -> active WS session path exists and is smoke-tested | `verified` |
| Placeholder reply paths are removed | legacy `Processed: ...` path is rejected by smoke | `verified` |
| Runtime bring-up is understandable | env file, models, binaries, TLS, and smoke commands are documented | `verified` |
| Voice docs match real maturity | new doc-set reflects verified vs partial vs optional | `verified` |
| One default local full-audio path is proven end-to-end | canonical `vosk + espeak-ng` path is bootstrapable and smoke-verified on a real workstation | `verified` |

## Current Reality

Verified now:

- public HTTP voice edge is `/api/v1/voice/*` on `api-gateway`
- public WebSocket voice edge is `/ws/voice` on `api-gateway`
- internal voice service boundary is `voice-gateway`
- `/api/v1/voice/command` returns real orchestrator text
- canonical local full-audio stack is `vosk + espeak-ng`
- `./scripts/setup-voice-local.sh` brings a machine to the canonical local voice stack without manual package work
- `./scripts/voice-local-smoke.sh` proves runtime up -> runtime truth -> synthesize -> transcribe -> WS state -> notification audio delivery
- `/internal/voice/notify` routes planner reminders into active voice sessions
- runtime smoke checks voice command and voice notification delivery
- `/api/v1/voice/runtime` exposes routing, maturity, STT, TTS, and pre-recorded asset status

Provider-dependent now:

- non-default STT provider paths such as Whisper
- non-default TTS provider paths such as Google Cloud TTS
- audio behavior outside the canonical local stack when external models or credentials differ from the supported local bootstrap

Optional now:

- wake-word / Porcupine
- always-listening story
- cloud TTS via Google credentials

## Checked Local Snapshot

On this workspace at 2026-03-27:

- `./scripts/setup-voice-local.sh` created the canonical local env under `~/.jarvis/run/local-runtime/local.env`
- canonical local models now live under `~/.jarvis/models/stt/vosk/...`
- canonical local TTS binary now lives at `~/.jarvis/tools/bin/espeak-ng`
- `./scripts/check-local-env.sh` reports `Canonical voice stack: vosk+espeak-ng` and `Voice readiness: full-audio ready`
- `./scripts/voice-local-smoke.sh` passed end-to-end on this machine

That means this workstation is now a real verified target for the canonical local full-audio path.
