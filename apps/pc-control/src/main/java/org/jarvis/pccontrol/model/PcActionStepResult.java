package org.jarvis.pccontrol.model;

import java.util.Map;

public record PcActionStepResult(
        String stepId,
        String actionType,
        PcActionExecutionStatus status,
        String message,
        Map<String, Object> details) {

    public PcActionStepResult {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
