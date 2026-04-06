package org.jarvis.common.vision;

import java.util.List;
import java.util.Map;

public record VisionVerifyOwnerResponse(
        VisionVerificationOutcome outcome,
        boolean operational,
        String provider,
        String message,
        Double similarity,
        int referenceImageCount,
        List<VisionFaceRegion> detectedFaces,
        Map<String, String> diagnostics) {

    public VisionVerifyOwnerResponse {
        outcome = outcome == null ? VisionVerificationOutcome.UNAVAILABLE : outcome;
        provider = provider == null ? "" : provider;
        message = message == null ? "" : message;
        detectedFaces = detectedFaces == null ? List.of() : List.copyOf(detectedFaces);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
