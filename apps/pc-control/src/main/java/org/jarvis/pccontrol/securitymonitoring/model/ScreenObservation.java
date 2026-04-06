package org.jarvis.pccontrol.securitymonitoring.model;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

public record ScreenObservation(
        BufferedImage screenshotImage,
        VisionScreenAnalysisResponse analysisResult,
        List<String> warnings) {

    public ScreenObservation {
        analysisResult = analysisResult == null
                ? new VisionScreenAnalysisResponse(
                false,
                VisionScreenCategory.UNAVAILABLE,
                "Screen analysis unavailable",
                null,
                false,
                null,
                false,
                Map.of())
                : analysisResult;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean hasScreenshot() {
        return screenshotImage != null;
    }
}
