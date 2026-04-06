package org.jarvis.vision.service;

import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.impl.HeuristicFaceAlignmentService;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicFaceAlignmentServiceTest {

    @Test
    void alignsFaceUsingHeuristicEyeCentersWhenEyesAreDetectable() {
        HeuristicFaceAlignmentService service = new HeuristicFaceAlignmentService(new VisionServiceProperties());

        FaceAlignmentResult result = service.align(slantedFace());

        assertThat(result.available()).isTrue();
        assertThat(result.applied()).isTrue();
        assertThat(result.faceImage().getWidth()).isEqualTo(result.faceImage().getHeight());
        assertThat(result.diagnostics()).containsEntry("alignmentApplied", "true");
        assertThat(result.diagnostics()).containsKey("alignmentRotationDegrees");
    }

    @Test
    void fallsBackToSquareCropWhenNoEyeStructureIsPresent() {
        HeuristicFaceAlignmentService service = new HeuristicFaceAlignmentService(new VisionServiceProperties());

        FaceAlignmentResult result = service.align(flatFace());

        assertThat(result.available()).isTrue();
        assertThat(result.applied()).isFalse();
        assertThat(result.mode()).isEqualTo("square-crop-fallback");
        assertThat(result.faceImage().getWidth()).isEqualTo(result.faceImage().getHeight());
    }

    private static BufferedImage slantedFace() {
        BufferedImage image = new BufferedImage(80, 64, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(235, 210, 180));
            graphics.fillRect(0, 0, 80, 64);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(14, 18, 12, 8);
            graphics.fillRect(48, 24, 12, 8);
            graphics.fillRect(28, 40, 20, 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage flatFace() {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(190, 190, 190));
            graphics.fillRect(0, 0, 64, 48);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
