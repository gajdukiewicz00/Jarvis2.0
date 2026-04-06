package org.jarvis.vision.pipeline;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionPipelineStage;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.impl.OpenCvRuntime;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultVisionImagePipelineServiceTest {

    @Test
    void executesProfessorPipelineStagesAndProducesArtifacts() {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.getPipeline().setGamma(1.15d);
        properties.getPipeline().setBlurKernelSize(5);

        DefaultVisionImagePipelineService service =
                new DefaultVisionImagePipelineService(properties, new OpenCvRuntime());

        BufferedImage input = syntheticFaceLikeImage();
        FaceDetectionProvider detector = new FaceDetectionProvider() {
            @Override
            public String providerId() {
                return "test-detector";
            }

            @Override
            public DetectionResult detectFaces(BufferedImage image) {
                return new DetectionResult(
                        true,
                        "test-detector",
                        "Detected 1 face(s)",
                        List.of(new VisionFaceRegion(12, 8, 28, 28)));
            }
        };

        VisionPipelineExecution execution = service.process(input, detector);

        assertThat(execution.artifacts()).extracting(VisionPipelineArtifactImage::stage).containsExactly(
                VisionPipelineStage.ORIGINAL,
                VisionPipelineStage.ENHANCEMENT,
                VisionPipelineStage.SEGMENTATION,
                VisionPipelineStage.CLEANING,
                VisionPipelineStage.DETECTION);
        assertThat(execution.stages()).extracting(VisionPipelineStageSnapshot::stage).containsExactly(
                VisionPipelineStage.ORIGINAL,
                VisionPipelineStage.ENHANCEMENT,
                VisionPipelineStage.SEGMENTATION,
                VisionPipelineStage.CLEANING,
                VisionPipelineStage.DETECTION);
        assertThat(execution.detectionResult().faces()).hasSize(1);
        assertThat(execution.stages().stream()
                .filter(stage -> stage.stage() == VisionPipelineStage.SEGMENTATION)
                .findFirst()
                .orElseThrow()
                .metrics()).containsKey("nonZeroPixels");
        assertThat(execution.stages().stream()
                .filter(stage -> stage.stage() == VisionPipelineStage.CLEANING)
                .findFirst()
                .orElseThrow()
                .metrics()).containsKey("coveragePercent");
    }

    private static BufferedImage syntheticFaceLikeImage() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.DARK_GRAY);
            graphics.fillRect(0, 0, 64, 64);
            graphics.setColor(new Color(220, 180, 150));
            graphics.fillOval(12, 8, 36, 40);
            graphics.setColor(Color.BLACK);
            graphics.fillOval(20, 20, 6, 6);
            graphics.fillOval(34, 20, 6, 6);
            graphics.fillRect(26, 32, 8, 3);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
