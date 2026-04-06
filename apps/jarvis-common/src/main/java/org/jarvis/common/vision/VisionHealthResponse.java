package org.jarvis.common.vision;

import java.util.Map;

public record VisionHealthResponse(
        boolean available,
        String detectorProvider,
        String verifierProvider,
        String message,
        Map<String, String> diagnostics) {

    public VisionHealthResponse {
        detectorProvider = detectorProvider == null ? "" : detectorProvider;
        verifierProvider = verifierProvider == null ? "" : verifierProvider;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
