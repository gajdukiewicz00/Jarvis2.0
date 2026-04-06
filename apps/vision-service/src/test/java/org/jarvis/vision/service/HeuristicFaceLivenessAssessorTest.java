package org.jarvis.vision.service;

import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.impl.HeuristicFaceLivenessAssessor;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicFaceLivenessAssessorTest {

    @Test
    void marksDetailedFaceAsPassingAndFlatFaceAsFailing() {
        HeuristicFaceLivenessAssessor assessor = new HeuristicFaceLivenessAssessor(new VisionServiceProperties());

        FaceLivenessAssessment detailed = assessor.assess(detailedFace());
        FaceLivenessAssessment flat = assessor.assess(flatFace());

        assertThat(detailed.available()).isTrue();
        assertThat(detailed.passed()).isTrue();
        assertThat(detailed.confidence()).isGreaterThan(0.55d);

        assertThat(flat.available()).isTrue();
        assertThat(flat.passed()).isFalse();
        assertThat(flat.confidence()).isLessThan(0.55d);
    }

    private static BufferedImage detailedFace() {
        BufferedImage image = new BufferedImage(72, 72, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(230, 205, 175));
            graphics.fillRect(0, 0, 72, 72);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(14, 18, 12, 10);
            graphics.fillRect(46, 18, 12, 10);
            graphics.setColor(new Color(120, 60, 40));
            for (int y = 34; y < 58; y += 4) {
                graphics.drawLine(22, y, 50, y);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage flatFace() {
        BufferedImage image = new BufferedImage(72, 72, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(185, 185, 185));
            graphics.fillRect(0, 0, 72, 72);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
