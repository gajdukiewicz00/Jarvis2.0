package org.jarvis.vision.pipeline;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionPipelineStage;

import java.util.List;
import java.util.Map;

public record VisionPipelineStageSnapshot(
        VisionPipelineStage stage,
        String description,
        Map<String, String> metrics,
        List<VisionFaceRegion> regions) {

    public VisionPipelineStageSnapshot {
        description = description == null ? "" : description;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}
