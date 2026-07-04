package org.jarvis.orchestrator.voice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandSource;
import org.jarvis.commands.RiskLevel;
import org.jarvis.commands.voice.VoiceFeedback;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.orchestrator.command.CommandPublisher;
import org.jarvis.orchestrator.command.risk.IntentRiskCatalog;
import org.jarvis.orchestrator.command.risk.RiskClassification;
import org.jarvis.orchestrator.dto.IntentExecutionResult;
import org.jarvis.orchestrator.service.OrchestratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 7 — orchestrator-side surface of the voice loop.
 *
 * <p>Called by voice-gateway after STT + intent classification. Wraps
 * {@link CommandPublisher#dispatch} (Phase 4-5) with bounded waiting and
 * maps the final {@link CommandResult} to a spoken {@link VoiceFeedback}
 * via {@link VoiceFeedbackTemplates}.</p>
 *
 * <p>The endpoint always returns a feedback envelope — even on timeout
 * or executor error — so SPEC-1's "always speak success/rejection/
 * degraded" guarantee is enforced at the boundary.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orchestrator/voice")
@RequiredArgsConstructor
public class VoiceLoopController {

    private final CommandPublisher publisher;
    private final VoiceFeedbackTemplates templates;
    private final VoiceIntentTranslator translator;
    private final OrchestratorService orchestratorService;
    private final IntentRiskCatalog riskCatalog;
    private final SystemPanicState panicState;
    private final ToolPermissionPolicy permissionPolicy;

    @Value("${jarvis.voice.dispatch-wait-seconds:25}")
    private long dispatchWaitSeconds;

    @PostMapping("/dispatch")
    public ResponseEntity<VoiceLoopResponse> dispatch(@Valid @RequestBody VoiceLoopRequest body) {
        if (body.intent() == null || body.intent().isBlank()) {
            VoiceFeedback fb = templates.unknownIntent(body.transcript());
            return ResponseEntity.ok(unresolved(body, fb, VoiceSessionStatus.FAILED, null));
        }

        // B1 — barge-in / cancel. Never dispatches a tool (so it can't bypass a
        // safety gate); just acknowledges and lets the caller cancel any in-flight
        // command via POST /cancel/{commandId}.
        if (isCancelIntent(body.intent())) {
            log.info("[{}] voice CANCEL intent — session cancelled, no dispatch", body.sessionId());
            return ResponseEntity.ok(VoiceLoopResponse.builder()
                    .sessionId(body.sessionId())
                    .correlationId(body.correlationId())
                    .sessionStatus(VoiceSessionStatus.CANCELLED)
                    .feedback(templates.cancelled())
                    .build());
        }

        // Global panic kill-switch — refuse any voice action while engaged.
        if (panicState.isEngaged()) {
            log.warn("[{}] voice dispatch REFUSED — system panic engaged, intent={}", body.sessionId(), body.intent());
            return ResponseEntity.ok(unresolved(body,
                    templates.degraded("аварийная остановка (panic)"), VoiceSessionStatus.FAILED, null));
        }
        // Per-intent permission gate (same shared policy as gateway + publisher).
        if (!permissionPolicy.isIntentAllowed(body.intent())) {
            log.warn("[{}] voice dispatch DENIED — missing {} for intent={}",
                    body.sessionId(), permissionPolicy.missingForIntent(body.intent()), body.intent());
            return ResponseEntity.ok(unresolved(body,
                    templates.denied("нет разрешения на " + body.intent()), VoiceSessionStatus.FAILED, null));
        }

        CommandSource source = body.source() == null ? CommandSource.VOICE : body.source();

        Map<String, Object> payload = body.payload() == null ? new HashMap<>()
                : new HashMap<>(body.payload());
        if (body.transcript() != null) {
            payload.putIfAbsent("transcript", body.transcript());
        }

        // --- Synchronous fast-path for SAFE/LOW intents ------------------------
        // The async queue path (below) waits for a desktop agent to consume
        // QUEUE_AGENT_EXECUTE and publish a result back. When no such executor
        // is connected (headless host), that wait times out at
        // dispatchWaitSeconds and the command never runs — the symptom behind
        // "сказал команду, ничего не произошло". For non-confirmation intents
        // we execute synchronously via the orchestrator's intent switch, which
        // maps PC intents straight to the host pc-control bridge. MEDIUM+
        // intents still flow through the confirmation/queue path below.
        RiskClassification risk = riskCatalog.classify(body.intent());
        if (!requiresConfirmation(risk.riskLevel())) {
            return ResponseEntity.ok(executeDirect(body, payload));
        }

        VoiceIntentTranslator.Translated translated = translator.translate(body.intent(), payload);
        log.debug("[{}] translated intent {} -> {}",
                body.sessionId(), body.intent(), translated.agentIntent());

        CompletableFuture<CommandResult> future;
        try {
            future = publisher.dispatch(
                    body.userId(),
                    source,
                    translated.agentIntent(),
                    translated.payload(),
                    body.correlationId());
        } catch (RuntimeException ex) {
            log.error("[{}] voice dispatch failed: {}", body.sessionId(), ex.getMessage(), ex);
            return ResponseEntity.ok(unresolved(body,
                    templates.failed("dispatch error: " + ex.getMessage()),
                    VoiceSessionStatus.FAILED, null));
        }

        CommandResult result;
        try {
            result = future.get(Math.max(dispatchWaitSeconds, 5), TimeUnit.SECONDS);
        } catch (TimeoutException tex) {
            log.warn("[{}] voice dispatch wait exceeded {}s — replying TIMEOUT",
                    body.sessionId(), dispatchWaitSeconds);
            return ResponseEntity.ok(unresolved(body, templates.timeout(),
                    VoiceSessionStatus.EXPIRED, null));
        } catch (Exception ex) {
            log.error("[{}] voice dispatch wait failed: {}", body.sessionId(), ex.getMessage(), ex);
            return ResponseEntity.ok(unresolved(body, templates.failed(ex.getMessage()),
                    VoiceSessionStatus.FAILED, null));
        }

        VoiceFeedback feedback = templates.fromCommandResult(result);
        VoiceSessionStatus sessionStatus = mapToSessionStatus(result);
        log.info("[{}] voice dispatch done: cmd={} status={} feedback={}",
                body.sessionId(), result.getCommandId(), result.getStatus(), feedback.getCode());
        return ResponseEntity.ok(VoiceLoopResponse.builder()
                .sessionId(body.sessionId())
                .commandId(result.getCommandId())
                .correlationId(result.getCorrelationId())
                .sessionStatus(sessionStatus)
                .feedback(feedback)
                .durationMillis(result.getDurationMillis())
                .build());
    }

    /** B1 — cancel a still-pending voice command by id (barge-in on a queued command). */
    @PostMapping("/cancel/{commandId}")
    public ResponseEntity<Void> cancelCommand(@PathVariable String commandId) {
        boolean cancelled = publisher.cancelPending(commandId);
        return cancelled ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }

    private boolean isCancelIntent(String intent) {
        if (intent == null) {
            return false;
        }
        String i = intent.trim().toLowerCase(java.util.Locale.ROOT);
        return i.equals("cancel") || i.equals("stop") || i.equals("cancel_command") || i.equals("abort");
    }

    /**
     * Intents at MEDIUM risk or above require explicit human confirmation, so
     * they keep the async confirmation/queue path. SAFE and LOW are executed
     * synchronously by the fast-path.
     */
    private boolean requiresConfirmation(RiskLevel level) {
        return level != null && level.ordinal() >= RiskLevel.MEDIUM.ordinal();
    }

    /**
     * Execute a non-confirmation intent synchronously via the orchestrator's
     * intent switch (which routes PC intents to the host pc-control bridge) and
     * return the spoken feedback immediately — no queue, no executor wait.
     */
    private VoiceLoopResponse executeDirect(VoiceLoopRequest body, Map<String, Object> payload) {
        Map<String, String> slots = new HashMap<>();
        payload.forEach((k, v) -> {
            if (v != null) {
                slots.put(k, String.valueOf(v));
            }
        });
        String correlationId = body.correlationId();
        IntentExecutionResult result;
        try {
            result = orchestratorService.executeIntentDetailed(
                    body.intent(), slots, null, correlationId, body.transcript(), body.userId());
        } catch (RuntimeException ex) {
            log.error("[{}] voice fast-path execution failed: {}", body.sessionId(), ex.getMessage(), ex);
            return unresolved(body, templates.failed(ex.getMessage()), VoiceSessionStatus.FAILED, null);
        }

        boolean failed = result.executionFailed();
        VoiceFeedback feedback = failed
                ? templates.failed(result.failureReason() != null ? result.failureReason() : result.responseText())
                : templates.executed(result.responseText());
        VoiceSessionStatus status = failed ? VoiceSessionStatus.FAILED : VoiceSessionStatus.COMPLETED;
        log.info("[{}] voice fast-path intent={} attempted={} succeeded={} failed={}",
                body.sessionId(), body.intent(), result.executionAttempted(),
                result.executionSucceeded(), failed);
        return VoiceLoopResponse.builder()
                .sessionId(body.sessionId())
                .commandId(correlationId)
                .correlationId(correlationId)
                .sessionStatus(status)
                .feedback(feedback)
                .durationMillis(0)
                .build();
    }

    private VoiceLoopResponse unresolved(VoiceLoopRequest body,
                                         VoiceFeedback fb,
                                         VoiceSessionStatus status,
                                         String commandId) {
        return VoiceLoopResponse.builder()
                .sessionId(body.sessionId())
                .commandId(commandId)
                .correlationId(body.correlationId())
                .sessionStatus(status)
                .feedback(fb)
                .build();
    }

    private VoiceSessionStatus mapToSessionStatus(CommandResult result) {
        return switch (result.getStatus()) {
            case SUCCESS -> VoiceSessionStatus.COMPLETED;
            case AWAITING_CONFIRMATION -> VoiceSessionStatus.AWAITING_CONFIRM;
            case REJECTED, FAILED -> VoiceSessionStatus.FAILED;
            case EXPIRED -> VoiceSessionStatus.EXPIRED;
            case CREATED, QUEUED, EXECUTING -> VoiceSessionStatus.DISPATCHED;
        };
    }

    public record VoiceLoopRequest(
            @NotBlank String sessionId,
            @NotBlank String userId,
            String correlationId,
            CommandSource source,
            @NotBlank String intent,
            String transcript,
            Map<String, Object> payload
    ) {}

    public record VoiceLoopResponse(
            @NotBlank String sessionId,
            String commandId,
            String correlationId,
            @NotNull VoiceSessionStatus sessionStatus,
            @NotNull VoiceFeedback feedback,
            long durationMillis
    ) {
        public static VoiceLoopResponseBuilder builder() {
            return new VoiceLoopResponseBuilder();
        }

        public static class VoiceLoopResponseBuilder {
            private String sessionId;
            private String commandId;
            private String correlationId;
            private VoiceSessionStatus sessionStatus;
            private VoiceFeedback feedback;
            private long durationMillis;

            public VoiceLoopResponseBuilder sessionId(String s) { this.sessionId = s; return this; }
            public VoiceLoopResponseBuilder commandId(String s) { this.commandId = s; return this; }
            public VoiceLoopResponseBuilder correlationId(String s) { this.correlationId = s; return this; }
            public VoiceLoopResponseBuilder sessionStatus(VoiceSessionStatus s) { this.sessionStatus = s; return this; }
            public VoiceLoopResponseBuilder feedback(VoiceFeedback f) { this.feedback = f; return this; }
            public VoiceLoopResponseBuilder durationMillis(long d) { this.durationMillis = d; return this; }
            public VoiceLoopResponse build() {
                return new VoiceLoopResponse(sessionId, commandId, correlationId,
                        sessionStatus, feedback, durationMillis);
            }
        }
    }
}
