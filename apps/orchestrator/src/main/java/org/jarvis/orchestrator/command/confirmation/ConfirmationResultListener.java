package org.jarvis.orchestrator.command.confirmation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandTopology;
import org.jarvis.commands.ConfirmationResult;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Phase 5 — consumes {@code jarvis.commands.confirmation.result} and hands
 * the decision to {@link ConfirmationCoordinator}. The coordinator owns
 * owner-validation, audit, and the APPROVED → execute fan-out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmationResultListener {

    private final ConfirmationCoordinator coordinator;

    @RabbitListener(queues = CommandTopology.QUEUE_CONFIRMATION_RESULT)
    public void onResult(ConfirmationResult result) {
        if (result == null) {
            log.warn("ignoring null confirmation result envelope");
            return;
        }
        log.debug("[{}] confirmation decision received: {} by {}",
                result.getCommandId(), result.getDecision(), result.getDecidedBy());
        coordinator.handleDecision(result);
    }
}
