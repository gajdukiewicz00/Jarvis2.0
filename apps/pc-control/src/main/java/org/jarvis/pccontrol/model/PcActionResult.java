package org.jarvis.pccontrol.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PcActionResult(
        boolean success,
        String actionType,
        PcActionExecutionStatus status,
        String message,
        String errorCode,
        Map<String, Object> details,
        List<PcActionStepResult> steps,
        Instant timestamp) {

    public PcActionResult {
        details = details == null ? Map.of() : Map.copyOf(details);
        steps = steps == null ? List.of() : List.copyOf(steps);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
