package org.jarvis.voicegateway.confirmation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandTopology;
import org.jarvis.commands.ConfirmationDecision;
import org.jarvis.commands.ConfirmationResult;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Phase 5 — voice confirmation entry point.
 *
 * <p>Accepts a confirmation decision from a voice channel (Phase 7 will
 * wire wake-word → STT → "Jarvis, yes/no" → this endpoint) and publishes
 * the resulting {@link ConfirmationResult} on
 * {@code jarvis.commands.confirmation.result}.</p>
 *
 * <p>The endpoint does not own owner verification — that lives in
 * {@code orchestrator.command.confirmation.ConfirmationCoordinator}.
 * voice-gateway only forwards what the speaker said with their identity
 * tag (Phase 6 will replace {@code decidedBy} with the verified owner
 * subject from owner-voice recognition).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voice/confirmations")
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "jarvis.voice.confirmation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class VoiceConfirmationController {

    private final RabbitTemplate rabbitTemplate;

    @PostMapping
    public ResponseEntity<ConfirmationResult> submit(@RequestBody @NotNull VoiceConfirmation body) {
        if (body.commandId() == null || body.commandId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (body.decision() == null) {
            return ResponseEntity.badRequest().build();
        }
        ConfirmationResult result = ConfirmationResult.builder()
                .commandId(body.commandId())
                .correlationId(body.correlationId())
                .decision(body.decision())
                .decidedBy(body.decidedBy())
                .decidedAt(Instant.now())
                .channel("voice")
                .reason(body.reason())
                .build();

        try {
            rabbitTemplate.convertAndSend(
                    "",
                    CommandTopology.QUEUE_CONFIRMATION_RESULT,
                    result);
        } catch (AmqpException e) {
            log.error("[{}] voice confirmation publish failed: {}",
                    result.getCommandId(), e.getMessage(), e);
            return ResponseEntity.status(503).body(result);
        }
        log.info("[{}] voice confirmation forwarded: decision={} decidedBy={} reason='{}'",
                result.getCommandId(), result.getDecision(),
                result.getDecidedBy(), result.getReason());
        return ResponseEntity.accepted().body(result);
    }

    /**
     * Voice confirmation envelope received by this gateway.
     *
     * @param commandId      original command id
     * @param correlationId  trace id (optional)
     * @param decision       APPROVED / DENIED / TIMEOUT (callers usually only send first two)
     * @param decidedBy      speaker identity (Phase 6 wires this to owner recognition)
     * @param reason         optional explanation, surfaced in audit
     */
    public record VoiceConfirmation(
            @NotBlank String commandId,
            String correlationId,
            @NotNull ConfirmationDecision decision,
            @NotBlank String decidedBy,
            String reason
    ) {}
}
