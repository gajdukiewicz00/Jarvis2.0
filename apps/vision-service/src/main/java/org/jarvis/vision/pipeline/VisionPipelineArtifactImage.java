package org.jarvis.vision.pipeline;

import org.jarvis.common.vision.VisionPipelineStage;

import java.awt.image.BufferedImage;

public record VisionPipelineArtifactImage(
        VisionPipelineStage stage,
        BufferedImage image,
        String description) {

    public VisionPipelineArtifactImage {
        description = description == null ? "" : description;
    }
}
