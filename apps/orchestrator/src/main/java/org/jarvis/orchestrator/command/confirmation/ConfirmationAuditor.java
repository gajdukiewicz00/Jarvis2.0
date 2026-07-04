package org.jarvis.orchestrator.command.confirmation;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.ConfirmationDecision;
import org.jarvis.commands.ConfirmationResult;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 8 — single audit emission point for confirmation lifecycle events.
 *
 * <p>Lifted out of {@link ConfirmationCoordinator} so the registry's
 * timeout sweeper can also write audit events without re-implementing the
 * Kafka payload contract. Both paths must produce the same audit shape
 * — the safety model relies on every rejection being traceable.</p>
 */
@Slf4j
@Component
public class ConfirmationAuditor {

    private final ObjectProvider<AuditPublisher> auditProvider;

    public ConfirmationAuditor(ObjectProvider<AuditPublisher> auditProvider) {
        this.auditProvider = auditProvider;
    }

    public void audit(CommandEnvelope envelope, ConfirmationResult result) {
        if (envelope == null || result == null) return;
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("intent", envelope.getIntent());
            payload.put("riskLevel", envelope.getRiskLevel() == null ? null : envelope.getRiskLevel().name());
            payload.put("decision", result.getDecision() == null ? null : result.getDecision().name());
            payload.put("decidedBy", result.getDecidedBy());
            payload.put("channel", safeChannel(result));
            if (result.getReason() != null) payload.put("reason", result.getReason());
            publisher.audit(decisionToType(result.getDecision()),
                    envelope.getCorrelationId(),
                    null,
                    envelope.getUserId(),
                    envelope.getCommandId(),
                    payload);
        }
        log.info("AUDIT confirmation: cmd={} user={} intent={} risk={} decision={} decidedBy={} channel={} reason={}",
                envelope.getCommandId(), envelope.getUserId(), envelope.getIntent(),
                envelope.getRiskLevel(), result.getDecision(),
                result.getDecidedBy() == null ? "" : result.getDecidedBy(),
                safeChannel(result),
                result.getReason() == null ? "" : result.getReason());
    }

    private String safeChannel(ConfirmationResult result) {
        return result.getChannel() == null ? "unknown" : result.getChannel();
    }

    private AuditEventType decisionToType(ConfirmationDecision decision) {
        if (decision == null) return AuditEventType.CONFIRMATION_REQUESTED;
        return switch (decision) {
            case APPROVED -> AuditEventType.CONFIRMATION_APPROVED;
            case DENIED -> AuditEventType.CONFIRMATION_DENIED;
            case TIMEOUT -> AuditEventType.CONFIRMATION_TIMEOUT;
            case BLOCKED_DEMO_MODE -> AuditEventType.CONFIRMATION_BLOCKED_DEMO_MODE;
            case BLOCKED_NON_OWNER -> AuditEventType.CONFIRMATION_BLOCKED_NON_OWNER;
        };
    }
}
