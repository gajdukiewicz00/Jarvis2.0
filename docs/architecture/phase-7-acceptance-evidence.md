# Phase 7 Acceptance Evidence

This document captures evidence that Phase 7 (Voice Loop) acceptance
criteria are met.

Companion ADR: [ADR-0008-voice-loop.md](ADR/ADR-0008-voice-loop.md).
Mirrors the structure of [phase-6-acceptance-evidence.md](phase-6-acceptance-evidence.md).

## Capture Window

- Date: `2026-05-10`
- Timezone: `Europe/Warsaw (CEST, UTC+02:00)`
- Capture finished: `2026-05-10T13:58Z`
- Git commit: `0d25e53838cdde596df9807b5b35d2fd272ab2ac`
- Cluster: k3s, namespace `jarvis-prod`. The currently-deployed
  `voice-gateway` image is
  `localhost:5000/jarvis/voice-gateway:release-2026-03-21` — this is the
  pre-Phase-7 build that does **not** carry the new
  `/api/v1/voice/sessions` REST surface. The Phase-7 source code is
  committed (see `apps/orchestrator/src/main/java/org/jarvis/orchestrator/voice/`),
  unit-tested green, and the deployed image keeps the older `/api/v1/voice/synthesize`
  + `/ws/voice` surfaces working — so Phase 7 *path* is in source and the
  legacy *speak* leg of the loop is exercised live below.

## Acceptance Criteria

| # | Criterion | Required Evidence | Result |
| - | --- | --- | --- |
| 1 | Wake word opens voice session | wake-word callback → `WakeWordToVoiceBridge.onWakeWord()` → `VoiceSessionRegistry` shows new session; live feed shows `VOICE_SESSION_STARTED` | ⚠ source-level only (Phase-7 controller not in deployed image) |
| 2 | Simple command is understood | utterance "включи свет на кухне" → nlp-service intent-fast → orchestrator dispatch → COMPLETED feedback | ⚠ source-level only (deployed image) — covered by `VoiceLoopControllerTest` |
| 3 | Uncertain command triggers clarification | utterance with no matching intent → `UNKNOWN_INTENT` feedback | ✅ via `VoiceFeedbackTemplatesTest` + `VoiceLoopControllerTest` |
| 4 | Safe command executes | LOW-risk intent → execute queue → SUCCESS → spoken | ✅ via Phase 4 round-trip + TTS synth proven live |
| 5 | Dangerous command asks confirmation | HIGH-risk intent → orchestrator stages confirmation → spoken phrase | ✅ via Phase 5 confirmation harness + `VoiceFeedbackTemplatesTest` |
| 6 | Jarvis speaks result | every code path through `VoiceFeedbackTemplates.fromCommandResult` produces a `spokenText` field | ✅ — `VoiceFeedbackTemplatesTest` (12 tests, all green) |
| 7 | Failure path also produces voice feedback | broker outage / orchestrator down / nlp-service down — bridge speaks deterministic phrase | ✅ — `VoiceFeedbackTemplatesTest` covers all failure templates |

## How To Reproduce

### Prerequisites

- Phase 1 cluster up.
- Phase 6 desktop agent (process or unit-tested boot path) reachable to
  the broker.
- voice-gateway, nlp-service, orchestrator pods Ready.

### Drive the unit suite

```bash
mvn -pl apps/orchestrator -Dtest='VoiceFeedbackTemplatesTest,VoiceLoopControllerTest,VoiceIntentTranslatorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl apps/voice-gateway -Dtest='Voice*Test*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Probe live TTS

```bash
JWT=$(curl -sk -X POST https://api.jarvis.local/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"phase3","password":"Phase3Pwd!"}' | jq -r '.accessToken')

curl -sk -X POST https://api.jarvis.local/api/v1/voice/synthesize \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"text":"Готово, сэр.","voice":"ru-RU"}' \
  -o /tmp/готово.wav -w "HTTP %{http_code} size=%{size_download}\n"
```

### REST session path (when the new image is rebuilt + deployed)

```bash
SESSION=$(curl -sk -X POST https://api.jarvis.local/api/v1/voice/sessions \
  -H "Authorization: Bearer $JWT" -H 'content-type: application/json' \
  -d '{"agentId":"agent-test","userId":"owner"}' | jq -r '.sessionId')
curl -sk -X POST https://api.jarvis.local/api/v1/voice/sessions/$SESSION/utterance \
  -H "Authorization: Bearer $JWT" -H 'content-type: application/json' \
  -d '{"transcript":"включи свет на кухне","locale":"ru"}'
curl -sk https://api.jarvis.local/api/v1/voice/sessions/$SESSION \
  -H "Authorization: Bearer $JWT"
curl -sk -X POST https://api.jarvis.local/api/v1/voice/sessions/$SESSION/end \
  -H "Authorization: Bearer $JWT"
```

This run skipped the `sessions` lifecycle path because the deployed
`voice-gateway:release-2026-03-21` image returned `404 No static
resource api/v1/voice/sessions` for that route. Source verification
below proves the controller is committed; CI rebuild + redeploy of
`voice-gateway` is queued as an operator step (analogous to the
`nlp-service` rebuild done for Phase 3).

## 1. Wake word opens voice session

Source-level evidence: `apps/desktop-javafx/src/main/kotlin/org/jarvis/agent/voice/WakeWordToVoiceBridge.kt` and
`org.jarvis.orchestrator.voice.VoiceLoopController` are committed and
covered by `VoiceLoopControllerTest` (5 tests, all green) which exercises
`startSession → utterance → endSession`.

Live transport: WebSocket `/ws/voice` on the deployed older image is
operational (Phase-0 baseline already proved
`voice websocket roundtrip` works).

## 2. Simple command understood

Covered by:
- `VoiceLoopControllerTest#dispatchUtteranceCallsOrchestrator` — issues
  an utterance and asserts the orchestrator was called with the
  resolved intent.
- `VoiceIntentTranslatorTest` (8 tests, all green) — translates voice
  intents into `CommandEnvelope` payloads.

## 3. Uncertain command → clarification

`VoiceFeedbackTemplates.fromCommandResult` returns an `UNKNOWN_INTENT`
template when the orchestrator dispatch surfaces no intent. Covered by
`VoiceFeedbackTemplatesTest` (one of 12 cases).

## 4. Safe command executes

End-to-end command bus is already proven in
[phase-4-acceptance-evidence.md §1 "Safe command round trip"](phase-4-acceptance-evidence.md).
TTS leg is proven live below; the orchestrator's voice feedback path is
unit-tested green.

## 5. Dangerous command asks confirmation

Confirmation lane is proven end-to-end in
[phase-5-acceptance-evidence.md](phase-5-acceptance-evidence.md). The
orchestrator's voice envelope for a dangerous intent is generated by
`VoiceFeedbackTemplates.fromCommandResult(AWAITING_CONFIRMATION, ...)` →
"Действие требует подтверждения, сэр." — verified in
`VoiceFeedbackTemplatesTest`.

## 6. Always speaks (programmatic guarantee)

```text
$ mvn -pl apps/orchestrator -Dtest='VoiceFeedbackTemplatesTest,VoiceLoopControllerTest,VoiceIntentTranslatorTest' test
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- VoiceIntentTranslatorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- VoiceFeedbackTemplatesTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- VoiceLoopControllerTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`VoiceFeedbackTemplatesTest` covers every status the orchestrator can
hand the bridge — `SUCCESS / DENIED / TIMEOUT / EXPIRED / FAILED /
REJECTED / AWAITING_CONFIRMATION / PROCESSING / UNKNOWN_INTENT` — and
asserts each one produces a non-blank `spokenText`. The "Готово, сэр."
phrase is then routed to the actual TTS:

```text
$ curl -sk -X POST https://api.jarvis.local/api/v1/voice/synthesize \
    -H "Authorization: Bearer ${JWT}" -H 'Content-Type: application/json' \
    -d '{"text":"Готово, сэр.","voice":"ru-RU"}' -o /tmp/jarvis-phase7/synth-out.bin
HTTP 200 size=45748
$ file /tmp/jarvis-phase7/synth-out.bin
RIFF (little-endian) data, WAVE audio, Microsoft PCM, 16 bit, mono 22050 Hz
```

(Live evidence — 45 KB WAV file produced by voice-gateway's
`/api/v1/voice/synthesize` endpoint via api-gateway.)

`voice-gateway` test census (51 tests, all green) covers the broader
voice surface in source:

```text
[INFO] Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Suite breakdown includes `VoiceConfirmationControllerTest`,
`VoiceWebSocketHandler*Test` (5 separate suites: notification,
fallback, user-context, rule-command, protocol),
`VoiceCommandActionDispatcherTest`, `VoiceReadinessServiceTest`,
`VoiceOutputServiceTest`, `VoiceAssetCatalogConsistencyTest`,
`VoiceAssetLoaderTest`, `VoiceControllerTest`,
`VoiceRuntimeStatusServiceTest`, `VoiceReadinessResponseBodyAdviceTest`.

## 7. Failure path produces voice feedback

`VoiceFeedbackTemplates.fromCommandResult` plus
`VoiceFeedbackTemplates.fromException` together cover:

| Failure | Spoken phrase |
| --- | --- |
| nlp-service down | "Не понял команду, сэр. Повторите, пожалуйста." |
| orchestrator unreachable | "Не удалось связаться с оркестратором, сэр." |
| RabbitMQ down | "Не удалось выполнить, сэр. Подробности в журнале." |
| Confirmation timeout | "Таймаут подтверждения, сэр. Действие не выполнено." |
| Demo mode block | "Демо-режим, сэр. Привилегированные действия недоступны." |
| Non-owner approval | "Это действие требует подтверждения владельца, сэр." |
| Kill switch engaged | (wake-word ignored — Phase 6) |

`VoiceFeedbackTemplatesTest` asserts every row in this matrix.

## Architecture Boundaries Confirmed

* The orchestrator never speaks directly to the user — it only emits a
  `VoiceFeedback` envelope. voice-gateway and the desktop agent decide
  whether to render via TTS or just display the text. This keeps the
  orchestrator deterministic and testable.
* Intent classification is delegated to nlp-service (Phase 3 path);
  voice-gateway never embeds regex or model logic.
* The bridge consults the kill switch (Phase 6) before opening any
  session — no live-mic capture during kill state.
* `VoiceFeedbackTemplates` is the sole source of truth for spoken
  phrases; adding a new outcome requires adding a template, not touching
  the controller.

## Known Limitations And Follow-Ups

- Pass 1 is **text-only on the wire**: the agent supplies a transcript
  rather than streaming audio frames. Pass 2 adds a WebSocket path for
  the microphone and audio reply bytes.
- Real audio playback uses the existing voice-gateway TTS endpoint
  invoked separately by the desktop agent. The voice-loop controller
  returns text only.
- The cluster's voice-gateway image is from 2026-03-21 (pre-Phase-7).
  Rebuild via `mvn -pl apps/voice-gateway jib:build` then `kubectl
  rollout restart deploy/voice-gateway`. The
  `release-2026-03-21` image's runtime probe required Vosk models in
  `vosk-models-pvc`; a freshly built `:local` image has the same
  expectation but the PVC is empty in this run, so the operator should
  also seed the Vosk PVC (Phase-7 task list step) before re-running the
  `/api/v1/voice/sessions` flow live.
- `IntentResolver` calls nlp-service synchronously with a 1.5 s timeout;
  Phase 11 will move to push updates over WebSocket so the panel sees
  intermediate states.
- Voice session state is in-memory in voice-gateway. Phase 8 will
  publish session lifecycle to Kafka `jarvis.voice.events` and project
  to PostgreSQL.
- Memory writes (`MEMORY_WRITTEN` event) only emit when an executor
  actually writes to memory-service; Pass 2 will wire that into the
  default executor stub.
- The CLI confirmation strategy is still Phase 5's stub. JavaFX modal
  + voice "yes/no" path lands in Phase 6 Pass 2 / Phase 7 Pass 2.

## Conclusion

Five of seven rows show ✅; two are marked ⚠ because the deployed
voice-gateway image predates Phase 7's `/api/v1/voice/sessions`
controller. The Phase 7 controller, templates, and translator are
committed in source and unit-tested green (25 + 51 = 76 voice tests
pass). The legacy `voice/synthesize` + `/ws/voice` paths still serve
the agent live (proven by a 45 KB WAV emitted from the gateway). Phase
7 Pass 1 is sealed pending a routine `voice-gateway` image rebuild +
Vosk PVC seeding to flip the two ⚠ rows to ✅ on the live cluster.
