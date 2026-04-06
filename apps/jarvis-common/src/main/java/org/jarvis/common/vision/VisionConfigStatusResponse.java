package org.jarvis.common.vision;

import java.util.Map;

public record VisionConfigStatusResponse(
        boolean enabled,
        String detectorProvider,
        String verifierProvider,
        double similarityThreshold,
        int minimumFaceSizePixels,
        boolean enrollmentEnabled,
        Map<String, String> diagnostics) {

    public VisionConfigStatusResponse {
        detectorProvider = detectorProvider == null ? "" : detectorProvider;
        verifierProvider = verifierProvider == null ? "" : verifierProvider;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
