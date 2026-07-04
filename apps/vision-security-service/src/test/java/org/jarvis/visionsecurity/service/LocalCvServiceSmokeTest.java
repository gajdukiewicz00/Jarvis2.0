package org.jarvis.visionsecurity.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end smoke test against the real {@code tesseract} binary.
 * Skipped automatically when the binary is not on PATH so it stays safe
 * in environments where the OCR dependency is unavailable.
 */
class LocalCvServiceSmokeTest {

    @Test
    void runsRealTesseractAgainstFixture(@TempDir Path tmp) throws Exception {
        ShellCommandRunner runner = new ShellCommandRunner();
        assumeTrue(runner.isAvailable("tesseract"),
                "tesseract binary not available; install `tesseract-ocr` to run this smoke test");

        Path fixture = renderFixture(tmp.resolve("fixture.png"));
        VisionSecurityProperties properties = new VisionSecurityProperties();
        OcrService ocr = new OcrService(runner, properties);
        ScreenshotService screenshotService = new ScreenshotService(runner);
        LocalCvService service = new LocalCvService(ocr, screenshotService, properties, new SimpleMeterRegistry());

        CvAnalysisResult result = service.analyzeFile(fixture);

        assertThat(result.success()).as("OCR should succeed: %s", result.error()).isTrue();
        assertThat(result.engine()).isEqualTo("tesseract");
        assertThat(result.language()).isEqualTo("eng");
        assertThat(result.width()).isEqualTo(800);
        assertThat(result.height()).isEqualTo(240);
        assertThat(result.ocrText()).isNotBlank();
        assertThat(result.ocrText()).contains("Jarvis").contains("OCR");
        assertThat(result.blocks()).isNotEmpty();
        assertThat(result.blocks().get(0).bbox().width()).isGreaterThan(0);
        assertThat(result.blocks().get(0).bbox().height()).isGreaterThan(0);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    private static Path renderFixture(Path target) throws Exception {
        BufferedImage img = new BufferedImage(800, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
        g.drawString("Hello Jarvis", 30, 60);
        g.drawString("Local OCR Test", 30, 120);
        g.drawString("Computer Vision", 30, 180);
        g.dispose();
        ImageIO.write(img, "png", target.toFile());
        return target;
    }
}
