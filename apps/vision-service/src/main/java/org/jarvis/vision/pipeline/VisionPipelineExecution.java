package org.jarvis.vision.pipeline;

import org.jarvis.vision.service.FaceDetectionProvider;

import java.util.List;
import java.util.Map;

public record VisionPipelineExecution(
        List<VisionPipelineArtifactImage> artifacts,
        List<VisionPipelineStageSnapshot> stages,
        FaceDetectionProvider.DetectionResult detectionResult,
        Map<String, String> diagnostics) {

    public VisionPipelineExecution {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        stages = stages == null ? List.of() : List.copyOf(stages);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
