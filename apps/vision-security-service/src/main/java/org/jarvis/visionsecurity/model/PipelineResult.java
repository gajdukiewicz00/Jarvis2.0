package org.jarvis.visionsecurity.model;

import java.util.List;

public record PipelineResult(
        DecisionType decision,
        int faceCount,
        String reason,
        List<FaceMatch> faces,
        StagePaths stagePaths,
        String rawFramePath
) {
}
