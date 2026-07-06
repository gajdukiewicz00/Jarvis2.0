package org.jarvis.orchestrator.dto;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code POST /api/v1/orchestrator/assist}.
 *
 * @param degraded {@code true} when a non-critical dependency (memory-service)
 *                 was unavailable and this response is a partial result rather
 *                 than a hard failure. {@code success} can still be {@code true}
 *                 while {@code degraded} is {@code true}.
 */
public record AssistResponse(
        String command,
        String intent,
        Map<String, Object> screenContext,
        Memory memory,
        String answer,
        List<ProposedAction> proposedActions,
        List<ProposedAction> executedActions,
        boolean requiresConfirmation,
        boolean success,
        String error,
        boolean degraded) {

    public record Memory(List<String> read, List<String> written) {}
}
