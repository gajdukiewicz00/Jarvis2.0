package org.jarvis.orchestrator.command.confirmation;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.CommandTopology;
import org.jarvis.commands.ConfirmationRequest;
import org.jarvis.commands.ConfirmationResult;
import org.jarvis.commands.DangerousAction;
import org.jarvis.orchestrator.command.CommandPublisher;
import org.jarvis.orchestrator.command.risk.IntentRiskCatalog;
import org.jarvis.orchestrator.command.risk.RiskClassification;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 5 — owns the confirmation lifecycle for dangerous commands.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>{@link CommandPublisher} calls {@link #stageConfirmation} when
 *       {@code envelope.requiresConfirmation == true}.</li>
 *   <li>If demo mode is on, the coordinator immediately completes the
 *       caller future with {@code REJECTED / BLOCKED_DEMO_MODE} — the
 *       {@code ConfirmationRequest} is not even published.</li>
 *   <li>Otherwise, the coordinator registers a pending future, publishes
 *       a {@link ConfirmationRequest} to
 *       {@code jarvis.commands.confirmation.request}, and returns the
 *       future to the caller.</li>
 *   <li>{@link ConfirmationResultListener} delivers the
 *       {@link ConfirmationResult} into {@link #handleDecision}.</li>
 *   <li>On APPROVED + matching owner: the original envelope is published
 *       on the agent execute queue via the regular
 *       {@link CommandPublisher#publish(CommandEnvelope)} path; the
 *       caller future is chained to the resulting execution future.</li>
 *   <li>On DENIED / TIMEOUT / non-owner: the caller future completes with
 *       a {@code REJECTED} {@link CommandResult} carrying the reason.</li>
 * </ol>
 */
@Slf4j
@Service
public class ConfirmationCoordinator {

    private final RabbitTemplate rabbitTemplate;
    private final PendingConfirmationRegistry pendingConfirmations;
    private final DemoModeProperties demoMode;
    private final IntentRiskCatalog catalog;
    private final CommandPublisher publisher; // @Lazy to break circular dep
    private final ConfirmationAuditor auditor;

    public ConfirmationCoordinator(RabbitTemplate rabbitTemplate,
                                   PendingConfirmationRegistry pendingConfirmations,
                                   DemoModeProperties demoMode,
                                   IntentRiskCatalog catalog,
                                   @Lazy CommandPublisher publisher,
                                   ConfirmationAuditor auditor) {
        this.rabbitTemplate = rabbitTemplate;
        this.pendingConfirmations = pendingConfirmations;
        this.demoMode = demoMode;
        this.catalog = catalog;
        this.publisher = publisher;
        this.auditor = auditor;
    }

    /**
     * Entry point for the publisher when a command needs confirmation.
     */
    public CompletableFuture<CommandResult> stageConfirmation(CommandEnvelope envelope) {
        if (demoMode.isEnabled()) {
            log.warn("[{}] demo mode active — auto-blocking dangerous intent={} risk={}",
                    envelope.getCommandId(), envelope.getIntent(), envelope.getRiskLevel());
            audit(envelope, ConfirmationResult.demoModeBlock(
                    envelope.getCommandId(), envelope.getCorrelationId()));
            return CompletableFuture.completedFuture(reject(envelope, "BLOCKED_DEMO_MODE",
                    demoMode.getReason()));
        }

        envelope.setStatus(CommandStatus.AWAITING_CONFIRMATION);
        CompletableFuture<CommandResult> future = pendingConfirmations.register(envelope);
        publishConfirmationRequest(envelope);
        return future;
    }

    /**
     * Called by the result listener for every ConfirmationResult that
     * arrives from a desktop / voice / mobile channel.
     */
    public void handleDecision(ConfirmationResult result) {
        if (result == null || result.getCommandId() == null) {
            log.warn("ignoring null/blank confirmation result");
            return;
        }
        var envelopeOpt = pendingConfirmations.lookup(result.getCommandId());
        if (envelopeOpt.isEmpty()) {
            log.debug("[{}] decision {} for unknown/late command — ignoring",
                    result.getCommandId(), result.getDecision());
            return;
        }
        CommandEnvelope envelope = envelopeOpt.get();
        CompletableFuture<CommandResult> future = pendingConfirmations.takeFuture(result.getCommandId());
        if (future == null) {
            log.debug("[{}] decision {} but future already completed", result.getCommandId(),
                    result.getDecision());
            return;
        }

        switch (result.getDecision()) {
            case APPROVED -> handleApproved(envelope, result, future);
            case DENIED -> {
                String reason = "DENIED by " + safeChannel(result) + (result.getReason() != null
                        ? ": " + result.getReason() : "");
                log.info("[{}] {}", envelope.getCommandId(), reason);
                audit(envelope, result);
                future.complete(reject(envelope, "DENIED", reason));
            }
            case TIMEOUT -> {
                String reason = "TIMEOUT before owner decided";
                log.warn("[{}] {}", envelope.getCommandId(), reason);
                audit(envelope, result);
                future.complete(reject(envelope, "TIMEOUT", reason));
            }
            case BLOCKED_DEMO_MODE -> {
                audit(envelope, result);
                future.complete(reject(envelope, "BLOCKED_DEMO_MODE",
                        result.getReason() == null ? demoMode.getReason() : result.getReason()));
            }
            case BLOCKED_NON_OWNER -> {
                audit(envelope, result);
                future.complete(reject(envelope, "BLOCKED_NON_OWNER",
                        result.getReason() == null ? "non-owner cannot confirm" : result.getReason()));
            }
        }
    }

    private void handleApproved(CommandEnvelope envelope,
                                ConfirmationResult result,
                                CompletableFuture<CommandResult> callerFuture) {
        // Owner check — the user who decided MUST match the user who issued the command.
        String decidedBy = result.getDecidedBy();
        if (decidedBy == null || decidedBy.isBlank() || !decidedBy.equals(envelope.getUserId())) {
            log.warn("[{}] non-owner approval attempt by '{}' (expected '{}') — blocking",
                    envelope.getCommandId(), decidedBy, envelope.getUserId());
            ConfirmationResult blocked = ConfirmationResult.nonOwnerBlock(
                    envelope.getCommandId(), envelope.getCorrelationId(),
                    decidedBy, envelope.getUserId());
            audit(envelope, blocked);
            callerFuture.complete(reject(envelope, "BLOCKED_NON_OWNER",
                    "speaker '" + decidedBy + "' is not '" + envelope.getUserId() + "'"));
            return;
        }

        log.info("[{}] APPROVED by {} via {} — forwarding to execute queue",
                envelope.getCommandId(), decidedBy, safeChannel(result));
        audit(envelope, result);

        // Forward to the agent execute queue (Phase 4 path).
        CompletableFuture<CommandResult> executionFuture;
        try {
            executionFuture = publisher.publish(envelope);
        } catch (RuntimeException ex) {
            log.error("[{}] failed to forward approved command: {}",
                    envelope.getCommandId(), ex.getMessage(), ex);
            callerFuture.complete(CommandResult.failed(
                    envelope.getCommandId(), envelope.getCorrelationId(),
                    "post-approval publish failed: " + ex.getMessage(), 0));
            return;
        }
        executionFuture.whenComplete((execResult, ex) -> {
            if (ex != null) {
                callerFuture.completeExceptionally(ex);
            } else {
                callerFuture.complete(execResult);
            }
        });
    }

    private void publishConfirmationRequest(CommandEnvelope envelope) {
        RiskClassification classification = catalog.classify(envelope.getIntent());
        DangerousAction action = classification.dangerousAction();
        ConfirmationRequest request = ConfirmationRequest.builder()
                .commandId(envelope.getCommandId())
                .correlationId(envelope.getCorrelationId())
                .userId(envelope.getUserId())
                .intent(envelope.getIntent())
                .riskLevel(envelope.getRiskLevel())
                .dangerousAction(action)
                .source(envelope.getSource())
                .payload(envelope.getPayload())
                .prompt(buildPrompt(envelope, classification))
                .requestedAt(Instant.now())
                .expiresAt(envelope.getExpiresAt())
                .build();

        long ttlMillis = computeTtlMillis(envelope);
        MessagePostProcessor postProcessor = msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            msg.getMessageProperties().setExpiration(Long.toString(ttlMillis));
            msg.getMessageProperties().setHeader(CommandTopology.HEADER_COMMAND_ID, envelope.getCommandId());
            msg.getMessageProperties().setHeader(CommandTopology.HEADER_CORRELATION_ID, envelope.getCorrelationId());
            msg.getMessageProperties().setHeader(CommandTopology.HEADER_USER_ID, envelope.getUserId());
            return msg;
        };

        rabbitTemplate.convertAndSend(
                "",
                CommandTopology.QUEUE_CONFIRMATION_REQUEST,
                request,
                postProcessor);
        log.info("[{}] confirmation request published: action={} prompt='{}'",
                envelope.getCommandId(), action, request.getPrompt());
    }

    private CommandResult reject(CommandEnvelope envelope, String code, String reason) {
        return CommandResult.builder()
                .commandId(envelope.getCommandId())
                .correlationId(envelope.getCorrelationId())
                .status(CommandStatus.REJECTED)
                .completedAt(Instant.now())
                .errorReason(code + ": " + reason)
                .build();
    }

    private long computeTtlMillis(CommandEnvelope envelope) {
        if (envelope.getExpiresAt() == null) {
            return 30_000L;
        }
        long remaining = envelope.getExpiresAt().toEpochMilli() - System.currentTimeMillis();
        return Math.max(remaining, 1L);
    }

    private String safeChannel(ConfirmationResult result) {
        return result.getChannel() == null ? "unknown" : result.getChannel();
    }

    private String buildPrompt(CommandEnvelope envelope, RiskClassification classification) {
        StringBuilder sb = new StringBuilder();
        sb.append("Confirm ").append(classification.riskLevel());
        if (classification.dangerousAction() != null) {
            sb.append('/').append(classification.dangerousAction());
        }
        sb.append(" action: ").append(envelope.getIntent());
        if (envelope.getPayload() != null && !envelope.getPayload().isEmpty()) {
            sb.append(' ').append(envelope.getPayload());
        }
        return sb.toString();
    }

    private void audit(CommandEnvelope envelope, ConfirmationResult result) {
        auditor.audit(envelope, result);
    }
}
