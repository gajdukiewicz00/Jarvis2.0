package org.jarvis.visionsecurity.service.cv;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.CvBlock;
import org.jarvis.visionsecurity.model.RectBox;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.jarvis.visionsecurity.service.LocalCvService;
import org.jarvis.visionsecurity.service.OcrService;
import org.jarvis.visionsecurity.service.ScreenContextService;
import org.jarvis.visionsecurity.service.ScreenshotService;
import org.jarvis.visionsecurity.service.SemanticTagger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScreenContextCvServiceTest {

    private final VisionSecurityProperties properties = new VisionSecurityProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void deriveRegionsClustersAdjacentLines() {
        List<CvBlock> blocks = List.of(
                new CvBlock("a", 90, new RectBox(0, 0, 100, 20)),
                new CvBlock("b", 90, new RectBox(0, 25, 200, 20)),
                new CvBlock("c", 90, new RectBox(0, 500, 80, 20)));

        List<RectBox> regions = ScreenContextCvService.deriveRegions(blocks);

        assertThat(regions).hasSize(2);
        assertThat(regions.get(0)).isEqualTo(new RectBox(0, 0, 200, 45));
        assertThat(regions.get(1)).isEqualTo(new RectBox(0, 500, 80, 20));
    }

    @Test
    void deriveRegionsReturnsEmptyForEmptyBlocks() {
        assertThat(ScreenContextCvService.deriveRegions(List.of())).isEmpty();
        assertThat(ScreenContextCvService.deriveRegions(null)).isEmpty();
    }

    @Test
    void captureCarriesAnalysisAndIncludesVlmNotConfigured(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"), 300, 200);
        OcrService ocrService = mock(OcrService.class);
        ScreenshotService screenshotService = mock(ScreenshotService.class);
        ScreenContextService screenContextService = mock(ScreenContextService.class);
        SemanticTagger tagger = mock(SemanticTagger.class);
        when(screenshotService.capture(image)).thenReturn(image);
        when(ocrService.extractStructured(image))
                .thenReturn(new OcrService.StructuredOcrResult(
                        List.of(new CvBlock("hello world", 95.0, new RectBox(10, 20, 100, 30))),
                        "hello world", "eng"));
        when(screenContextService.detectDisplayServer()).thenReturn("x11");
        when(screenContextService.activeWindowTitle()).thenReturn("Some Window");
        when(screenContextService.activeProcessName()).thenReturn("some-app");
        when(tagger.deriveTags("Some Window", "some-app", "hello world"))
                .thenReturn(List.of("BROWSER", "WORK"));

        LocalCvService localCvService = new LocalCvService(
                ocrService, screenshotService, properties, meterRegistry,
                new OcrEngineSelector(
                        List.of(new TesseractOcrEngine(ocrService)),
                        properties));
        ScreenContextEventPublisher noopPublisher = new ScreenContextEventPublisher(
                noopProvider(), properties);
        ScreenContextCvService service = new ScreenContextCvService(
                localCvService, screenContextService, tagger,
                new NotConfiguredLocalVlmAdapter(),
                new NotConfiguredUiElementDetector(),
                new NotConfiguredObjectDetector(),
                noopPublisher, properties,
                new CvVlmMetrics(meterRegistry), meterRegistry);

        ScreenContextResult result = service.capture("owner", image);

        assertThat(result.success()).isTrue();
        assertThat(result.userId()).isEqualTo("owner");
        assertThat(result.analysis().ocrText()).isEqualTo("hello world");
        assertThat(result.semanticTags()).containsExactly("BROWSER", "WORK");
        assertThat(result.activeWindowTitle()).isEqualTo("Some Window");
        assertThat(result.activeProcessName()).isEqualTo("some-app");
        assertThat(result.displayServer()).isEqualTo("x11");
        assertThat(result.regions()).hasSize(1);
        assertThat(result.vlm().availability()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.vlm().summary()).isNull();
        assertThat(result.vlm().error()).contains("Local VLM not configured");
        // Detectors default to NOT_CONFIGURED and never fabricate elements.
        assertThat(result.uiElements()).isEmpty();
        assertThat(result.objects()).isEmpty();
        assertThat(result.detection().uiAvailability()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.detection().objectAvailability()).isEqualTo("NOT_CONFIGURED");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaTemplate<String, String>> noopProvider() {
        ObjectProvider<KafkaTemplate<String, String>> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private static Path writePng(Path target, int width, int height) throws Exception {
        Files.createDirectories(target.getParent());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", target.toFile());
        return target;
    }
}
