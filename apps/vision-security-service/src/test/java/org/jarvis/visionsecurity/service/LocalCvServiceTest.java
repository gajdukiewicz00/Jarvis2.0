package org.jarvis.visionsecurity.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.CvBlock;
import org.jarvis.visionsecurity.model.RectBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalCvServiceTest {

    private final OcrService ocrService = mock(OcrService.class);
    private final ScreenshotService screenshotService = mock(ScreenshotService.class);
    private final VisionSecurityProperties properties = new VisionSecurityProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LocalCvService cvService = new LocalCvService(
            ocrService, screenshotService, properties, meterRegistry);

    @Test
    void analyzeFileReturnsFailureWhenImageMissing(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.png");

        CvAnalysisResult result = cvService.analyzeFile(missing);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Image file not found");
        assertThat(result.source()).isEqualTo(LocalCvService.SOURCE_FILE);
        assertThat(result.engine()).isEqualTo(OcrService.ENGINE);
        assertThat(result.blocks()).isEmpty();
        assertThat(meterRegistry.find("jarvis_cv_failures_total")
                .tag("reason", "file_not_found").counter().count()).isEqualTo(1.0);
    }

    @Test
    void analyzeFileReturnsFailureWhenOcrEngineMissing(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"), 320, 240);
        when(ocrService.extractStructured(image)).thenThrow(
                new OcrService.OcrUnavailableException("tesseract missing"));

        CvAnalysisResult result = cvService.analyzeFile(image);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("OCR engine unavailable");
        assertThat(result.width()).isEqualTo(320);
        assertThat(result.height()).isEqualTo(240);
        assertThat(meterRegistry.find("jarvis_cv_failures_total")
                .tag("reason", "engine_unavailable").counter().count()).isEqualTo(1.0);
    }

    @Test
    void analyzeFileReturnsFailureWhenOcrExecutionFails(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"), 100, 100);
        when(ocrService.extractStructured(image)).thenThrow(
                new OcrService.OcrExecutionException("non-zero exit"));

        CvAnalysisResult result = cvService.analyzeFile(image);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("non-zero exit");
        assertThat(meterRegistry.find("jarvis_cv_failures_total")
                .tag("reason", "engine_error").counter().count()).isEqualTo(1.0);
    }

    @Test
    void analyzeFileReturnsStructuredResultOnSuccess(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"), 200, 80);
        List<CvBlock> blocks = List.of(new CvBlock("Hello", 95.0, new RectBox(10, 20, 80, 30)));
        when(ocrService.extractStructured(image))
                .thenReturn(new OcrService.StructuredOcrResult(blocks, "Hello", "eng"));

        CvAnalysisResult result = cvService.analyzeFile(image);

        assertThat(result.success()).isTrue();
        assertThat(result.ocrText()).isEqualTo("Hello");
        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0).text()).isEqualTo("Hello");
        assertThat(result.width()).isEqualTo(200);
        assertThat(result.height()).isEqualTo(80);
        assertThat(result.engine()).isEqualTo(OcrService.ENGINE);
        assertThat(result.language()).isEqualTo("eng");
        assertThat(result.imagePath()).isEqualTo(image.toString());
        assertThat(result.capturedAt()).isNotNull();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(meterRegistry.find("jarvis_cv_requests_total")
                .tag("outcome", "success").counter().count()).isEqualTo(1.0);
    }

    @Test
    void analyzeScreenshotPropagatesCaptureFailure(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("shot.png");
        when(screenshotService.capture(target)).thenThrow(new RuntimeException("headless"));

        CvAnalysisResult result = cvService.analyzeScreenshot(target);

        assertThat(result.success()).isFalse();
        assertThat(result.source()).isEqualTo(LocalCvService.SOURCE_SCREENSHOT);
        assertThat(result.error()).contains("Screenshot capture failed");
        assertThat(meterRegistry.find("jarvis_cv_failures_total")
                .tag("reason", "screenshot_capture_failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void normalizeSourceMapsAliases() {
        assertThat(LocalCvService.normalizeSource(null)).isEqualTo("file");
        assertThat(LocalCvService.normalizeSource("FILE")).isEqualTo("file");
        assertThat(LocalCvService.normalizeSource("Screenshot")).isEqualTo("screenshot");
        assertThat(LocalCvService.normalizeSource("screen")).isEqualTo("screenshot");
        assertThat(LocalCvService.normalizeSource("anything-else")).isEqualTo("file");
    }

    private static Path writePng(Path target, int width, int height) throws Exception {
        Files.createDirectories(target.getParent());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", target.toFile());
        return target;
    }
}
