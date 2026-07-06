package org.jarvis.voicegateway.voiceloop;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.voice.VoiceFeedback;
import org.jarvis.commands.voice.VoiceSession;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Phase 7 — voice-loop entry point on voice-gateway.
 *
 * <ol>
 *   <li>Wake-word fires on the desktop agent → POST /sessions → returns sessionId.</li>
 *   <li>STT done locally or via existing voice-gateway path → POST
 *       /sessions/{id}/utterance with the transcript text.</li>
 *   <li>This controller calls {@link IntentResolver} (nlp-service intent-fast),
 *       then {@link OrchestratorVoiceClient} (orchestrator dispatch),
 *       updates the session, returns a {@link VoiceFeedback} with spoken text.</li>
 *   <li>Caller (desktop agent) speaks the {@code spokenText} via local TTS
 *       (existing voice-gateway TTS pipeline can also serve it on demand).</li>
 *   <li>POST /sessions/{id}/end closes the session explicitly; otherwise
 *       the registry sweeper expires it after the TTL.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voice/sessions")
@RequiredArgsConstructor
public class VoiceSessionController {

    private final VoiceSessionRegistry registry;
    private final IntentResolver intents;
    private final OrchestratorVoiceClient orchestratorClient;

    @PostMapping
    public ResponseEntity<VoiceSession> start(@RequestBody StartRequest body) {
        VoiceSession session = registry.start(
                body.agentId() == null ? "unknown-agent" : body.agentId(),
                body.userId() == null ? "anonymous" : body.userId());
        return ResponseEntity.ok(session);
    }

    @PostMapping("/{sessionId}/utterance")
    public ResponseEntity<UtteranceResponse> utterance(@PathVariable String sessionId,
                                                       @RequestBody UtteranceRequest body) {
        VoiceSession session = registry.get(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.transcript() == null || body.transcript().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        registry.update(sessionId, s -> {
            s.setStatus(VoiceSessionStatus.TRANSCRIBED);
            s.setTranscript(body.transcript());
        });

        IntentResolver.Resolution resolution = intents.resolve(body.transcript(), body.locale());
        if (!resolution.isResolved()) {
            registry.update(sessionId, s -> s.setStatus(VoiceSessionStatus.FAILED));
            VoiceFeedback fb = VoiceFeedback.builder()
                    .code("UNKNOWN_INTENT")
                    .level(VoiceFeedback.Level.WARN)
                    .spokenText("Не понял команду, сэр. Повторите, пожалуйста.")
                    .displayText("Не распознан интент: \"" + body.transcript() + "\"")
                    .build();
            log.info("[{}] utterance unresolved: '{}'", sessionId, body.transcript());
            return ResponseEntity.ok(new UtteranceResponse(sessionId, null, null,
                    VoiceSessionStatus.FAILED, fb, resolution));
        }

        registry.update(sessionId, s -> {
            s.setStatus(VoiceSessionStatus.CLASSIFIED);
            s.setIntent(resolution.intent());
            s.setIntentConfidence(resolution.confidence());
            s.setIntentSource(resolution.source());
        });

        String correlationId = "voice-" + sessionId + "-" + UUID.randomUUID();
        OrchestratorVoiceClient.VoiceLoopReply reply = orchestratorClient.dispatch(
                sessionId, session.getUserId(), correlationId,
                resolution.intent(), body.transcript());

        // B1 — barge-in guard: if the user cancelled while dispatch() was in flight,
        // the session is already CANCELLED by the time we get here. Skip applying the
        // orchestrator's (now-stale) reply so a cancelled command doesn't silently
        // flip back to COMPLETED/FAILED and get reported as having succeeded.
        registry.update(sessionId,
                s -> s.getStatus() != VoiceSessionStatus.CANCELLED,
                s -> {
                    s.setStatus(reply.status());
                    s.setCommandId(reply.commandId());
                    s.setCorrelationId(reply.correlationId());
                    s.setReplyText(reply.feedback() == null ? null : reply.feedback().getSpokenText());
                });

        log.info("[{}] utterance done: intent={} source={} status={} feedback={}",
                sessionId, resolution.intent(), resolution.source(), reply.status(),
                reply.feedback() == null ? "null" : reply.feedback().getCode());
        return ResponseEntity.ok(new UtteranceResponse(sessionId, reply.commandId(),
                reply.correlationId(), reply.status(), reply.feedback(), resolution));
    }

    @PostMapping("/{sessionId}/end")
    public ResponseEntity<Void> end(@PathVariable String sessionId) {
        registry.end(sessionId);
        return ResponseEntity.accepted().build();
    }

    /** B1 — barge-in / cancel an active voice session. */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<VoiceSession> cancel(@PathVariable String sessionId,
            @RequestBody(required = false) CancelRequest body) {
        String reason = body == null || body.reason() == null ? "user_cancel" : body.reason();
        return registry.cancel(sessionId, reason)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CancelRequest(String reason) {}

    @GetMapping("/{sessionId}")
    public ResponseEntity<VoiceSession> get(@PathVariable String sessionId) {
        return registry.get(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public Collection<VoiceSession> list() {
        return registry.list();
    }

    public record StartRequest(String agentId, String userId) {}

    public record UtteranceRequest(@NotBlank String transcript, String locale) {}

    public record UtteranceResponse(
            String sessionId,
            String commandId,
            String correlationId,
            VoiceSessionStatus sessionStatus,
            VoiceFeedback feedback,
            IntentResolver.Resolution intent
    ) {}
}
