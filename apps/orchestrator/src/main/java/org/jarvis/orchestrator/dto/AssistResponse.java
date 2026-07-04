package org.jarvis.orchestrator.dto;

import java.util.List;
import java.util.Map;

/** Response for {@code POST /api/v1/orchestrator/assist}. */
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
        String error) {

    public record Memory(List<String> read, List<String> written) {}
}
