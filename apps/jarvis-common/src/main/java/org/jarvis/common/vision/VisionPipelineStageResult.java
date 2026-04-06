package org.jarvis.common.vision;

import java.util.List;
import java.util.Map;

public record VisionPipelineStageResult(
        VisionPipelineStage stage,
        String description,
        Map<String, String> metrics,
        List<VisionFaceRegion> regions) {

    public VisionPipelineStageResult {
        stage = stage == null ? VisionPipelineStage.ORIGINAL : stage;
        description = description == null ? "" : description;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}
