# ADR-0008: Voice loop wiring

## Status

Accepted (Phase 7).

## Context

Phases 0-6 delivered the building blocks of a voice-driven assistant:

* desktop agent with wake-word capture (Porcupine), kill switch, live feed
  (Phase 6);
* voice-gateway with real Whisper STT and TTS (Phase 0 baseline);
* nlp-service with deterministic regex intents and a fast-router client
  (Phases 0 + 3);
* orchestrator with command publisher, risk catalog, and confirmation
  flow (Phases 4 + 5);
* host model daemon for reasoning behind {@code llm-service} (Phase 3).

What was missing was the *last-mile wiring*: nothing actually drove the
sequence
{@code wake → STT → intent → reasoning → command → confirmation → speech}
end-to-end, and there was no enforced "always speak" guarantee for
SPEC-1's "Jarvis must always provide voice feedback for completed or
rejected actions".

## Decision

We add three thin layers, all driven by REST so each side can be
deployed and tested independently:

```
desktop agent
  └── WakeWordToVoiceBridge.kt
        ├── VoiceLoopClient.kt        ─── HTTP ──▶  voice-gateway
        │                                              ├── VoiceSessionRegistry
        │                                              ├── IntentResolver       ─── HTTP ──▶ nlp-service /intent-fast
        │                                              └── OrchestratorVoiceClient ─── HTTP ──▶ orchestrator
        └── (TTS callback)                                                                          └── VoiceLoopController
                                                                                                         ├── CommandPublisher (Phase 4-5)
                                                                                                         └── VoiceFeedbackTemplates
```

* New module-shared schema in `libs/command-schema/voice/`:
  `VoiceSession`, `VoiceSessionStatus` (10 lifecycle states), `VoiceFeedback`
  (code/level/spokenText/displayText).
* `VoiceFeedbackTemplates` (orchestrator) maps every {@link
  org.jarvis.commands.CommandStatus} and every {@code REJECTED} reason
  prefix (`BLOCKED_DEMO_MODE`, `BLOCKED_NON_OWNER`, `TIMEOUT`, `DENIED`)
  to a deterministic Iron-Man-Jarvis Russian phrase. The boundary
  guarantees we never return silently — every dispatch returns a
  feedback envelope.
* `VoiceLoopController` (orchestrator) wraps `CommandPublisher.dispatch`
  with a bounded wait (default 25s; clamped to ≥5s). Dispatch errors,
  timeouts, and exceptions are all converted to a feedback envelope so
  the voice-gateway always has a phrase to speak.
* `VoiceSessionController` (voice-gateway) drives the per-session state
  machine. It calls nlp-service first (Phase 3 already routes regex →
  router); if no intent resolves, it short-circuits with `UNKNOWN_INTENT`
  feedback rather than dispatching nothing.
* `WakeWordToVoiceBridge` (desktop agent) composes the client with a
  caller-supplied transcribe lambda and TTS lambda — Pass 1 is text-only
  on the wire, Pass 2 will plug in real audio capture and playback
  without changing the bus.

## Consequences

* The voice-gateway now owns voice-session ownership and an
  `IntentResolver`; the controller is the single place that decides
  "regex" vs "router" (delegated to nlp-service) and "OK to dispatch"
  vs "UNKNOWN_INTENT".
* The orchestrator gets a small, explicit voice surface (`/voice/dispatch`)
  separate from the existing direct-REST `/orchestrator/execute` path,
  so future changes to the voice loop don't disturb desktop-side direct
  callers.
* `WakeWordToVoiceBridge` is intentionally synchronous and tiny so the
  JavaFX shell can wire it in Phase 6 Pass 2 with no agent rewrite.
* The kill switch (Phase 6) is honoured at the bridge layer: a wake-word
  fired while the kill switch is engaged never opens a session, never
  posts a transcript, and emits a `KILL_SWITCH_ENGAGED` live-feed entry.
* Phase 8 (Kafka audit) has clean hooks: `VoiceFeedback` is already a
  stable POJO; the audit projector can subscribe to live-feed events
  (`VOICE_SESSION_STARTED`, `INTENT_CLASSIFIED`, `COMMAND_QUEUED`,
  `COMMAND_EXECUTED`) and publish them to `jarvis.voice.events`.

## Alternatives considered

* **Single fat WebSocket from agent to voice-gateway carrying audio
  frames.** Rejected for Pass 1 — testable surface area is much smaller
  with REST. Phase 7 Pass 2 adds the streaming WS for the actual
  microphone path.
* **Have the orchestrator own intent classification.** Rejected.
  nlp-service is the dedicated classification surface (Phase 3), and
  voice-gateway is the right place to fan in transcripts from any
  channel (voice / text / mobile). The orchestrator stays focused on
  command execution.
* **Skip the explicit feedback layer; let callers format their own
  spoken text.** Rejected. Centralising templates is the only way to
  guarantee "always speak" with consistent style — caller-side strings
  drift and inevitably miss edge cases (`TIMEOUT`, `BLOCKED_NON_OWNER`).

## References

* SPEC-1 § "Must Have" — voice feedback always
* SPEC-1 § "Minimum Production Slice" — closes the loop
* `libs/command-schema/.../voice/`
* `apps/orchestrator/.../voice/VoiceLoopController.java`
* `apps/voice-gateway/.../voiceloop/`
* `apps/desktop-javafx/.../agent/voice/`
* [phase-7-acceptance-evidence.md](../phase-7-acceptance-evidence.md)
