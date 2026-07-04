package org.jarvis.orchestrator.command;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.CommandTopology;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 4 — consumes {@code jarvis.commands.agent.result}, correlates the
 * incoming {@link CommandResult} to a pending future via
 * {@link PendingCommandRegistry}, and (Phase 8 wire) emits an audit
 * event matching the result's terminal {@link CommandStatus}.
 *
 * <p>Audit emit is fire-and-forget via {@link ObjectProvider} so this
 * listener still works in tests that don't wire {@code AuditPublisher}.</p>
 */
@Slf4j
@Component
public class CommandResultListener {

    private final PendingCommandRegistry registry;
    private final ObjectProvider<AuditPublisher> auditProvider;

    public CommandResultListener(PendingCommandRegistry registry,
                                 ObjectProvider<AuditPublisher> auditProvider) {
        this.registry = registry;
        this.auditProvider = auditProvider;
    }

    @RabbitListener(queues = CommandTopology.QUEUE_AGENT_RESULT)
    public void onResult(CommandResult result) {
        if (result == null || result.getCommandId() == null) {
            log.warn("ignoring null/blank command result");
            return;
        }
        boolean matched = registry.complete(result);
        log.info("[{}] result received status={} matched={} duration={}ms reason={}",
                result.getCommandId(), result.getStatus(), matched,
                result.getDurationMillis(),
                result.getErrorReason() == null ? "" : result.getErrorReason());

        AuditPublisher audit = auditProvider.getIfAvailable();
        if (audit == null) return;
        AuditEventType type = auditTypeFor(result.getStatus());
        if (type == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", result.getStatus() != null ? result.getStatus().name() : "UNKNOWN");
        payload.put("durationMillis", result.getDurationMillis());
        if (result.getErrorReason() != null) payload.put("errorReason", result.getErrorReason());
        if (result.getOutput() != null) payload.put("output", result.getOutput());
        payload.put("matchedPending", matched);
        audit.audit(type, result.getCorrelationId(), null, null, result.getCommandId(), payload);
    }

    private static AuditEventType auditTypeFor(CommandStatus status) {
        if (status == null) return null;
        return switch (status) {
            case SUCCESS -> AuditEventType.COMMAND_EXECUTED;
            case FAILED, REJECTED -> AuditEventType.COMMAND_FAILED;
            case EXPIRED -> AuditEventType.COMMAND_EXPIRED;
            // Non-terminal states should not arrive on the result topic; if one
            // does we log it but don't audit it as a terminal event.
            case CREATED, QUEUED, AWAITING_CONFIRMATION, EXECUTING -> null;
        };
    }
}
