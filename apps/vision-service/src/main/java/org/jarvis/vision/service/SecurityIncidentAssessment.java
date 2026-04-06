package org.jarvis.vision.service;

import java.util.Map;

public record SecurityIncidentAssessment(
        IncidentDisposition disposition,
        int score,
        String message,
        Map<String, String> diagnostics) {

    public SecurityIncidentAssessment {
        disposition = disposition == null ? IncidentDisposition.WAIT_FOR_MORE_EVIDENCE : disposition;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
