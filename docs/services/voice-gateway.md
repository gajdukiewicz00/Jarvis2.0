# voice-gateway

## 1. Name

`voice-gateway`

## 2. Type

Backend voice service.

## 3. Purpose

Handles speech-to-text, text-to-speech, voice WebSocket sessions, rule-based command resolution, and voice-facing orchestration/notification flows.

## 4. Current Reality

The service is implemented and actively wired, but much of the behavior is resource- and tool-dependent: Vosk models, TTS binaries, voice asset files, and downstream services determine what actually works at runtime.

## 5. Entry Points

- Spring Boot app: `org.jarvis.voicegateway.VoiceGatewayApplication`
- REST base path: `/api/v1/voice`
- WebSocket endpoint: `/ws/voice`

## 6. Configuration

Main configuration source:

- `apps/voice-gateway/src/main/resources/application.yaml`

Important settings include:

- server port `8081`
- STT provider and Vosk model paths
- TTS provider settings (`espeak` by default, optional Google TTS config)
- orchestrator and smart-home base URLs
- CORS allowed origins
- command catalog and response asset resources

## 7. API / WebSocket Surface

REST endpoints:

- `POST /api/v1/voice/transcribe`
- `POST /api/v1/voice/transcribe/stream`
- `POST /api/v1/voice/command`
- `POST /api/v1/voice/synthesize`
- `GET /api/v1/voice/synthesize/test`
- `GET /api/v1/voice/runtime`
- `POST /internal/voice/pc-action`
- `POST /internal/voice/orchestrator-intent`
- `POST /internal/voice/smart-home-action`
- `POST /internal/voice/notify`

WebSocket protocol on `/ws/voice`:

- incoming text messages: `START`, `CONFIG`, `END`, `TIMEOUT`
- incoming binary messages: audio chunks
- outgoing state/transcript payloads including `STATE`, `TRANSCRIPT_PARTIAL`, and `TRANSCRIPT_FINAL`

## 8. Main Internal Components

- `VoiceWebSocketHandler`
- `SttService`
- `TtsService`
- `IntentService`
- `RuleBasedVoiceCommandService`
- `VoiceCommandActionDispatcher`
- `OrchestratorClient`
- `SmartHomeActionGateway`
- `PcControlActionGateway`

## 9. Dependencies On Other Services

- `orchestrator`
- `smart-home-service`
- `api-gateway` desktop path for PC control routing
- local STT/TTS assets and binaries

## 10. Data / Storage

- no database
- command catalogs, intents, routing rules, and WAV assets are stored as bundled resources under `src/main/resources`

## 11. Security Model

REST calls are protected by the standard service security configuration. External WebSocket traffic normally arrives through `api-gateway`.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/voice-gateway -am test
```

Helpful local bootstrap:

```bash
./scripts/setup-voice-local.sh
./scripts/runtime-up.sh
```

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Command handling is heavily rule-based.
- Runtime quality depends on installed STT/TTS assets and binaries.
- The service can be up while some capabilities are degraded, for example TTS unavailable or STT model missing.
