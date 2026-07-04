package org.jarvis.orchestrator.command;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandSource;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.CommandTopology;
import org.jarvis.commands.RiskLevel;
import org.jarvis.commands.CommandFactory;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.events.AuditEventType;
import org.jarvis.orchestrator.command.confirmation.ConfirmationCoordinator;
import org.jarvis.orchestrator.command.risk.IntentRiskCatalog;
import org.jarvis.orchestrator.command.risk.RiskClassification;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 4 — orchestrator-side facade for publishing commands onto the
 * agent-execute queue.
 *
 * <p>Steps for every dispatch:</p>
 * <ol>
 *   <li>Build a {@link CommandEnvelope} via {@link CommandFactory}.</li>
 *   <li>Register with {@link PendingCommandRegistry} so the result listener
 *       can correlate the eventual {@link CommandResult}.</li>
 *   <li>Publish the JSON-serialised envelope to
 *       {@link CommandTopology#QUEUE_AGENT_EXECUTE} with per-message
 *       {@code expiration} = remaining TTL → broker drops the message
 *       if the consumer can't pick it up in time, and DLX captures it.</li>
 * </ol>
 */
@Slf4j
@Service
public class CommandPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final PendingCommandRegistry registry;
    private final IntentRiskCatalog riskCatalog;
    private final ConfirmationCoordinator confirmationCoordinator;
    private final ObjectProvider<AuditPublisher> auditProvider;
    private final SystemPanicState panicState;
    private final ToolPermissionPolicy permissionPolicy;

    @Value("${jarvis.command.default-ttl-seconds:30}")
    private long defaultTtlSeconds;

    public CommandPublisher(RabbitTemplate rabbitTemplate,
                            PendingCommandRegistry registry,
                            IntentRiskCatalog riskCatalog,
                            ConfirmationCoordinator confirmationCoordinator,
                            ObjectProvider<AuditPublisher> auditProvider,
                            SystemPanicState panicState,
                            ToolPermissionPolicy permissionPolicy) {
        this.rabbitTemplate = rabbitTemplate;
        this.registry = registry;
        this.riskCatalog = riskCatalog;
        this.confirmationCoordinator = confirmationCoordinator;
        this.auditProvider = auditProvider;
        this.panicState = panicState;
        this.permissionPolicy = permissionPolicy;
    }

    /** B1 — cancel a still-pending command by id (no-op if already done). */
    public boolean cancelPending(String commandId) {
        return registry.cancel(commandId);
    }

    /**
     * Phase 5 entry point — caller does not have to know the risk level;
     * the {@link IntentRiskCatalog} classifies it. Dangerous intents are
     * routed via {@link ConfirmationCoordinator} before reaching the
     * execute queue.
     */
    public CompletableFuture<CommandResult> dispatch(String userId,
                                                     CommandSource source,
                                                     String intent,
                                                     Map<String, Object> payload,
                                                     String correlationId) {
        return dispatch(userId, source, intent, payload,
                Duration.ofSeconds(defaultTtlSeconds), correlationId);
    }

    public CompletableFuture<CommandResult> dispatch(String userId,
                                                     CommandSource source,
                                                     String intent,
                                                     Map<String, Object> payload,
                                                     Duration ttl,
                                                     String correlationId) {
        RiskClassification classification = riskCatalog.classify(intent);
        return dispatch(userId, source, intent, classification.riskLevel(),
                payload, ttl, correlationId);
    }

    /**
     * Phase 4 entry point retained for callers that want to pin a specific
     * risk level (typically tests or admin tooling). The
     * {@link IntentRiskCatalog} is still consulted and wins on mismatch —
     * the caller's hint cannot down-grade the catalog's classification.
     */
    public CompletableFuture<CommandResult> dispatch(String userId,
                                                     CommandSource source,
                                                     String intent,
                                                     RiskLevel hintedRiskLevel,
                                                     Map<String, Object> payload,
                                                     String correlationId) {
        return dispatch(userId, source, intent, hintedRiskLevel, payload,
                Duration.ofSeconds(defaultTtlSeconds), correlationId);
    }

    public CompletableFuture<CommandResult> dispatch(String userId,
                                                     CommandSource source,
                                                     String intent,
                                                     RiskLevel hintedRiskLevel,
                                                     Map<String, Object> payload,
                                                     Duration ttl,
                                                     String correlationId) {
        RiskClassification classification = riskCatalog.classify(intent);
        RiskLevel effective = hintedRiskLevel == null
                ? classification.riskLevel()
                : highestOf(hintedRiskLevel, classification.riskLevel());

        if (hintedRiskLevel != null && effective != hintedRiskLevel) {
            log.info("intent={} hint={} catalog={} — using catalog (cannot downgrade risk)",
                    intent, hintedRiskLevel, classification.riskLevel());
        }

        CommandEnvelope envelope = CommandFactory.create(
                userId, source, intent, effective, payload, ttl, correlationId);

        // Global panic kill-switch: refuse to publish anything while engaged.
        if (panicState.isEngaged()) {
            log.warn("[{}] command REFUSED — system panic engaged, intent={}", envelope.getCommandId(), intent);
            return CompletableFuture.completedFuture(CommandResult.failed(
                    envelope.getCommandId(), envelope.getCorrelationId(), "system_panic_engaged", 0));
        }
        // Per-intent permission gate (same shared policy as the gateway executor).
        if (!permissionPolicy.isIntentAllowed(intent)) {
            log.warn("[{}] command DENIED — missing permissions {} for intent={}",
                    envelope.getCommandId(), permissionPolicy.missingForIntent(intent), intent);
            return CompletableFuture.completedFuture(CommandResult.failed(
                    envelope.getCommandId(), envelope.getCorrelationId(),
                    "permission_denied:" + permissionPolicy.missingForIntent(intent), 0));
        }

        if (envelope.isRequiresConfirmation()) {
            return confirmationCoordinator.stageConfirmation(envelope);
        }
        return publish(envelope);
    }

    private RiskLevel highestOf(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * Publish a pre-built envelope. Useful for tests and advanced callers
     * that need exact control over the {@code commandId}.
     */
    public CompletableFuture<CommandResult> publish(CommandEnvelope envelope) {
        CompletableFuture<CommandResult> future = registry.register(envelope);
        envelope.setStatus(CommandStatus.QUEUED);

        long ttlMillis = computeRemainingTtlMillis(envelope);
        MessagePostProcessor postProcessor = msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            msg.getMessageProperties().setExpiration(Long.toString(ttlMillis));
            msg.getMessageProperties().setHeader(CommandTopology.HEADER_COMMAND_ID, envelope.getCommandId());
            msg.getMessageProperties().setHeader(CommandTopology.HEADER_CORRELATION_ID, envelope.getCorrelationId());
            msg.getMessageProperties().setHeader(CommandTopology.HEADER_USER_ID, envelope.getUserId());
            if (envelope.getRiskLevel() != null) {
                msg.getMessageProperties().setHeader(CommandTopology.HEADER_RISK_LEVEL, envelope.getRiskLevel().name());
            }
            if (envelope.getIntent() != null) {
                msg.getMessageProperties().setHeader(CommandTopology.HEADER_INTENT, envelope.getIntent());
            }
            return msg;
        };

        try {
            rabbitTemplate.convertAndSend(
                    "",
                    CommandTopology.QUEUE_AGENT_EXECUTE,
                    envelope,
                    postProcessor);
            log.info("[{}] published intent={} ttlMs={}",
                    envelope.getCommandId(), envelope.getIntent(), ttlMillis);
            emitAudit(AuditEventType.COMMAND_QUEUED, envelope, null);
        } catch (RuntimeException ex) {
            log.error("[{}] failed to publish: {}",
                    envelope.getCommandId(), ex.getMessage(), ex);
            registry.complete(CommandResult.failed(
                    envelope.getCommandId(),
                    envelope.getCorrelationId(),
                    "publish failure: " + ex.getMessage(),
                    0));
        }
        return future;
    }

    private void emitAudit(AuditEventType type, CommandEnvelope envelope, String reason) {
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("intent", envelope.getIntent());
        payload.put("source", envelope.getSource() == null ? null : envelope.getSource().name());
        payload.put("riskLevel", envelope.getRiskLevel() == null ? null : envelope.getRiskLevel().name());
        payload.put("requiresConfirmation", envelope.isRequiresConfirmation());
        if (reason != null) payload.put("reason", reason);
        publisher.audit(type, envelope.getCorrelationId(), null,
                envelope.getUserId(), envelope.getCommandId(), payload);
    }

    private long computeRemainingTtlMillis(CommandEnvelope envelope) {
        if (envelope.getExpiresAt() == null) {
            return Duration.ofSeconds(defaultTtlSeconds).toMillis();
        }
        long remaining = Duration.between(Instant.now(), envelope.getExpiresAt()).toMillis();
        return Math.max(remaining, 1L);
    }
}
