package org.jarvis.common.vision;

public record VisionImageArtifact(
        VisionPipelineStage stage,
        String mediaType,
        String base64Data,
        int width,
        int height,
        String description) {

    public VisionImageArtifact {
        stage = stage == null ? VisionPipelineStage.ORIGINAL : stage;
        mediaType = mediaType == null ? "image/png" : mediaType;
        base64Data = base64Data == null ? "" : base64Data;
        description = description == null ? "" : description;
    }
}
