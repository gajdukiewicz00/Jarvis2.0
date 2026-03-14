package org.jarvis.pccontrol.model;

import java.time.Instant;
import java.util.Map;

public record DesktopErrorResponse(String code, String message, Map<String, Object> details, Instant timestamp) {

    public DesktopErrorResponse {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        details = details == null ? Map.of() : details;
    }
}
