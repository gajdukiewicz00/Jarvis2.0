package org.jarvis.visionsecurity.model;

import java.time.Instant;
import java.util.List;

public record CvAnalysisResult(
        String source,
        String imagePath,
        Integer width,
        Integer height,
        String ocrText,
        List<CvBlock> blocks,
        String engine,
        String language,
        long durationMs,
        Instant capturedAt,
        boolean success,
        String error
) {
}
