package org.jarvis.visionsecurity.model;

public record StagePaths(
        String originalImage,
        String enhancedImage,
        String segmentationMask,
        String cleanedMask,
        String detectionResultImage,
        String finalDecisionImage
) {
}
