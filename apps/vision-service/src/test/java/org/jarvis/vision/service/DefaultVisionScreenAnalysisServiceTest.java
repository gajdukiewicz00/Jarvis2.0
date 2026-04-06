package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.impl.DefaultVisionScreenAnalysisService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultVisionScreenAnalysisServiceTest {

    @Test
    void categorizesTerminalAndMediaLikeScreens() throws Exception {
        DefaultVisionScreenAnalysisService service =
                new DefaultVisionScreenAnalysisService(new VisionServiceProperties());

        var terminal = service.analyze(new VisionScreenAnalysisRequest(
                pngBytes(terminalImage()),
                "png",
                "pc-control/screenshot",
                "req-terminal",
                Map.of()));
        var media = service.analyze(new VisionScreenAnalysisRequest(
                pngBytes(mediaImage()),
                "png",
                "pc-control/screenshot",
                "req-media",
                Map.of()));

        assertThat(terminal.operational()).isTrue();
        assertThat(terminal.category()).isEqualTo(VisionScreenCategory.TERMINAL);
        assertThat(terminal.sensitive()).isTrue();
        assertThat(terminal.ocrReady()).isTrue();

        assertThat(media.operational()).isTrue();
        assertThat(media.category()).isEqualTo(VisionScreenCategory.MEDIA);
        assertThat(media.sensitive()).isFalse();
    }

    private static BufferedImage terminalImage() {
        BufferedImage image = new BufferedImage(240, 140, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(18, 18, 18));
            graphics.fillRect(0, 0, 240, 140);
            graphics.setColor(new Color(80, 255, 120));
            for (int y = 18; y < 120; y += 16) {
                graphics.fillRect(18, y, 170, 4);
                graphics.fillRect(18, y + 6, 130, 3);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage mediaImage() {
        BufferedImage image = new BufferedImage(240, 140, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(255, 120, 60));
            graphics.fillRect(0, 0, 80, 140);
            graphics.setColor(new Color(50, 140, 255));
            graphics.fillRect(80, 0, 80, 140);
            graphics.setColor(new Color(255, 240, 40));
            graphics.fillRect(160, 0, 80, 140);
            graphics.setColor(Color.WHITE);
            graphics.fillOval(92, 36, 56, 56);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] pngBytes(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
