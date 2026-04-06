package org.jarvis.vision.service;

import java.util.Map;

public record FaceLivenessAssessment(
        boolean available,
        boolean passed,
        Double confidence,
        String provider,
        String message,
        Map<String, String> diagnostics) {

    public FaceLivenessAssessment {
        provider = provider == null ? "" : provider;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
