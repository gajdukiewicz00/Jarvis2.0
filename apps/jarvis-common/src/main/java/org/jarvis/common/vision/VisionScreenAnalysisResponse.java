package org.jarvis.common.vision;

import java.util.Map;

public record VisionScreenAnalysisResponse(
        boolean operational,
        VisionScreenCategory category,
        String message,
        Double categoryConfidence,
        boolean sensitive,
        Double sensitiveConfidence,
        boolean ocrReady,
        Map<String, String> diagnostics) {

    public VisionScreenAnalysisResponse {
        category = category == null ? VisionScreenCategory.UNAVAILABLE : category;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
