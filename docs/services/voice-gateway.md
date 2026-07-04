# voice-gateway

## 1. Name

`voice-gateway`

## 2. Type

Backend voice service.

## 3. Purpose

Handles speech-to-text, text-to-speech, voice WebSocket sessions, rule-based command resolution, and voice-facing orchestration/notification flows.

## 4. Current Reality

The service is implemented and actively wired, and it now exposes strict readiness for the real voice path instead of reporting a fake generic `UP`.

Observed HTTP forwarding behavior from code and runtime proof:

- `/api/v1/voice/command` preserves delegated user scope when forwarding raw text to `orchestrator`
- `/api/v1/voice/transcribe` and `/api/v1/voice/transcribe/stream` preserve delegated user scope when forwarding recognized text to `orchestrator`

Observed readiness behavior from code:

- `STT DOWN` => service readiness is `DOWN`
- `WebSocket DOWN` => service readiness is `DOWN`
- `TTS DOWN` => readiness is `DEGRADED` and command handling stays available
- `voice assets DOWN` => readiness is `DEGRADED`
- `orchestrator DOWN` => readiness is `DEGRADED` and bounded local fallback remains available

The effective runtime still depends on Vosk models, TTS binaries, bundled WAV assets, and downstream service reachability, but those dependencies are now surfaced explicitly in `/actuator/health/readiness` and `/api/v1/voice/runtime`.

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
- `GET /api/v1/voice/diagnostics`
- `POST /internal/voice/pc-action`
- `POST /internal/voice/orchestrator-intent`
- `POST /internal/voice/smart-home-action`
- `POST /internal/voice/notify`

Readiness endpoint on `/actuator/health/readiness` returns:

- top-level `status`: `UP`, `DEGRADED`, or `DOWN`
- `components.stt`
- `components.tts`
- `components.assets`
- `components.orchestrator`
- `components.websocket`

Diagnostics endpoint on `/api/v1/voice/diagnostics` returns machine-readable component truth for:

- capture ownership (`desktop-client`, implemented today by the Native Desktop Agent in `desktop-javafx`, not by backend host microphone probing)
- execution boundaries and downstream-capability hand-off points
- STT and TTS configured-vs-working state
- bundled voice asset availability
- orchestrator connectivity
- websocket readiness
- api-gateway route reachability

WebSocket protocol on `/ws/voice` is stateful and rule-enforced:

- client text messages:
  - `START`
  - `CONFIG`
  - `END`
  - `TIMEOUT`
- client binary messages:
  - raw `AUDIO_CHUNK` payloads
- server text messages:
  - `STATE`
  - `TRANSCRIPT_PARTIAL`
  - `TRANSCRIPT_FINAL`
  - `RESPONSE`
  - `ERROR`
- server binary messages:
  - synthesized or pre-recorded WAV audio when available

Server-side session phases:

- `IDLE -> STARTED -> STREAMING -> PROCESSING -> DONE`

Protocol violations such as duplicate `START`, `AUDIO_BEFORE_START`, `NO_AUDIO_RECEIVED`, invalid `CONFIG`, STT unavailability, or missing `END` are surfaced as explicit `ERROR` frames instead of being silently ignored.

## 8. Main Internal Components

- `VoiceWebSocketHandler`
- `SttService`
- `TtsService`
- `IntentService`
- `RuleBasedVoiceCommandService`
- `VoiceCommandActionDispatcher`
- `LocalIntentExecutionService`
- `OrchestratorClient`
- `SmartHomeActionGateway`
- `PcControlActionGateway`
- `VoiceReadinessService`

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
./scripts/voice-local-smoke.sh
./scripts/runtime-smoke.sh
```

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Command handling remains primarily rule-based by design; LLM usage is not required for the core voice path.
- Wake-word detection is not a `voice-gateway` responsibility. It lives in the Native Desktop Agent implementation (`desktop-javafx`) and is optional.
- Runtime quality still depends on installed STT/TTS assets and binaries, but readiness and runtime endpoints now report those gaps explicitly instead of masking them behind a generic healthy state.
- `voice-gateway` readiness proves the voice path itself. It does not by itself prove that a real workstation desktop executor is connected on `/ws/pc-control`.

## 15. Demo-Proven Command Surface

The explicitly advertised demo-safe phrases should stay narrow and evidence-backed:

| Phrase | Source of advertising | Primary route | Current proof |
|---|---|---|---|
| `прибавь громкость` | desktop `PcControlTab` prompt example | `local-basic-intent -> orchestrator -> pc-control` | unit tests plus `runtime-smoke.sh` and `voice-local-smoke.sh` |
| `good morning` | voice-gateway demo-safe phrase | `rule-catalog -> internal -> pre-recorded/TTS voice output` | rule-catalog test plus runtime scenario with TTS disabled |
| `what's up` | voice-gateway bounded fallback phrase | `local-basic-intent -> orchestrator` with bounded `local_fallback` when orchestrator is down | unit test plus orchestrator-down runtime scenario |

Phrases that still depend on downstream capability and are therefore intentionally not part of the guaranteed demo surface:

- `be quiet` / `STANDBY_MODE` is recognized, but local fallback is explicitly unsupported when the orchestrator is down.
- wake-word activation phrase `Jarvis` belongs to the Native Desktop Agent implementation in `desktop-javafx`; it is not a `voice-gateway` responsibility.

## 16. Local / Docker / K8s Parity Notes

Observed parity from runtime scripts, Dockerfile, and manifests:

| Area | Local runtime scripts | Docker image | K8s base / overlays | Drift handling |
|---|---|---|---|---|
| Public client path | `api-gateway` on `:8080`, `/ws/voice` | same route shape through gateway deployment | same route shape; internal TLS overlays switch internal hops to HTTPS | unified contract, different transport scheme only |
| Voice readiness probe | `/actuator/health/readiness`, accepts `UP` or `DEGRADED` as healthy | Docker `HEALTHCHECK` hits readiness | K8s probes hit readiness; TLS overlay changes probe scheme to `HTTPS` | unified semantics |
| STT model path | `~/.jarvis/models/...` via `scripts/runtime/common.sh` | image copies repo `models/` to `/app/models`, but a production/demo container still needs real Vosk content via repo-populated `models/` or an explicit mount such as `~/.jarvis/models:/models` plus `JARVIS_VOSK_MODEL_PATH_*` | PVC mounted at `/models` with explicit `JARVIS_VOSK_MODEL_PATH_*` | same model requirement, different mount source; not hidden |
| TTS provider | local canonical stack expects `espeak` | image installs `espeak-ng` | depends on cluster image/env; same app config keys | readiness/diagnostics expose missing TTS explicitly |
| Orchestrator URL | local scripts force `JARVIS_ORCHESTRATOR_URL=http://127.0.0.1:8083` for direct module run | default app config uses `http://orchestrator:8083` | internal TLS overlay patches `JARVIS_ORCHESTRATOR_URL=https://orchestrator.jarvis-prod.svc.cluster.local:8083` plus truststore | explicit config drift, behavior documented |
| JWT secret assumptions | local runtime collapses `SERVICE_JWT_SECRET` to `JWT_SECRET` unless `JARVIS_ALLOW_DISTINCT_LOCAL_SERVICE_JWT_SECRET=true` | container uses supplied env as-is and requires explicit non-empty 32+ byte `JWT_SECRET` / `SERVICE_JWT_SECRET` values | cluster secrets provide independent values | local convenience only; container and cluster are explicit |
| Desktop control execution | requires a connected authenticated desktop client on `/ws/pc-control` | unchanged | real desktop control is runtime-mode sensitive; check `api-gateway /api/v1/capabilities` and `pc-control` deployment mode | not hidden by voice readiness; capability boundary is explicit |

For runtime-mode-sensitive downstream execution, `/api/v1/capabilities` is the authoritative ingress-level disclosure. `voice-gateway` diagnostics now expose that boundary instead of implying that voice readiness alone proves workstation-local executors.
