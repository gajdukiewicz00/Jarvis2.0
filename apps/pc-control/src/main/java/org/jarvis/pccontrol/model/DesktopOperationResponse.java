package org.jarvis.pccontrol.model;

import java.time.Instant;
import java.util.Map;

public record DesktopOperationResponse(
        boolean success,
        String operation,
        String message,
        Map<String, Object> details,
        Instant timestamp) {

    public DesktopOperationResponse {
        details = details == null ? Map.of() : Map.copyOf(details);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
