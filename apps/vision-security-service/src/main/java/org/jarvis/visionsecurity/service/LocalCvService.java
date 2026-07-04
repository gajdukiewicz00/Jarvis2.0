package org.jarvis.visionsecurity.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.CvBlock;
import org.jarvis.visionsecurity.service.cv.OcrEngine;
import org.jarvis.visionsecurity.service.cv.OcrEngineSelector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local computer-vision entry point: takes either a screenshot or a path to an
 * existing image, runs the local OCR engine and returns a structured
 * {@link CvAnalysisResult}. No network/cloud calls.
 */
@Slf4j
@Service
public class LocalCvService {

    public static final String SOURCE_FILE = "file";
    public static final String SOURCE_SCREENSHOT = "screenshot";

    private static final String METRIC_REQUESTS = "jarvis_cv_requests_total";
    private static final String METRIC_FAILURES = "jarvis_cv_failures_total";
    private static final String METRIC_DURATION = "jarvis_cv_duration";

    private final OcrService ocrService;
    private final ScreenshotService screenshotService;
    private final VisionSecurityProperties properties;
    private final MeterRegistry meterRegistry;
    private final OcrEngineSelector engineSelector;
    private final AtomicLong ocrTextCharsGauge = new AtomicLong();

    @Autowired
    public LocalCvService(OcrService ocrService,
                          ScreenshotService screenshotService,
                          VisionSecurityProperties properties,
                          MeterRegistry meterRegistry,
                          OcrEngineSelector engineSelector) {
        this.ocrService = ocrService;
        this.screenshotService = screenshotService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.engineSelector = engineSelector;
        if (meterRegistry != null) {
            meterRegistry.gauge("jarvis_cv_ocr_text_chars", ocrTextCharsGauge);
        }
    }

    /**
     * Test-friendly constructor that uses {@link OcrService} directly (i.e.
     * the historical tesseract path). Production code goes through the
     * {@link OcrEngineSelector}-aware constructor above.
     */
    public LocalCvService(OcrService ocrService,
                          ScreenshotService screenshotService,
                          VisionSecurityProperties properties,
                          MeterRegistry meterRegistry) {
        this(ocrService, screenshotService, properties, meterRegistry, null);
    }

    public CvAnalysisResult analyzeFile(Path imagePath) {
        return analyze(SOURCE_FILE, imagePath, null);
    }

    public CvAnalysisResult analyzeScreenshot(Path destination) {
        long startNs = System.nanoTime();
        Path target = destination != null
                ? destination
                : defaultScreenshotPath();
        try {
            Path actual = screenshotService.capture(target);
            return analyze(SOURCE_SCREENSHOT, actual, startNs);
        } catch (Exception ex) {
            log.warn("CV screenshot capture failed: {}", ex.getMessage());
            long durationMs = durationMs(startNs);
            incrementFailure(SOURCE_SCREENSHOT, "screenshot_capture_failed");
            return failure(SOURCE_SCREENSHOT, target, durationMs,
                    "Screenshot capture failed: " + ex.getMessage());
        }
    }

    private Path defaultScreenshotPath() {
        String root = properties.getStorage().getRoot();
        Path dir = Path.of(root, "cv-screenshots");
        long stamp = System.currentTimeMillis();
        return dir.resolve("screen-" + stamp + ".png");
    }

    private CvAnalysisResult analyze(String source, Path imagePath, Long preStartNs) {
        long startNs = preStartNs != null ? preStartNs : System.nanoTime();
        log.info("CV analysis started source={} path={} engine={}",
                source, imagePath, OcrService.ENGINE);

        if (imagePath == null) {
            long durationMs = durationMs(startNs);
            incrementFailure(source, "missing_path");
            return failure(source, null, durationMs, "Image path was null");
        }

        if (!Files.isRegularFile(imagePath)) {
            long durationMs = durationMs(startNs);
            incrementFailure(source, "file_not_found");
            log.info("CV analysis aborted source={} path={} reason=file_not_found durationMs={}",
                    source, imagePath, durationMs);
            return failure(source, imagePath, durationMs,
                    "Image file not found or not readable: " + imagePath);
        }

        ImageDimensions dims = readDimensions(imagePath);
        String engineId = OcrService.ENGINE;

        try {
            OcrEngine engine = activeEngine();
            engineId = engine.id();
            OcrService.StructuredOcrResult ocr = engine.extractStructured(imagePath);
            long durationMs = durationMs(startNs);
            recordTimer(source, "success", durationMs);
            incrementRequest(source, "success");
            ocrTextCharsGauge.set(ocr.text() == null ? 0 : ocr.text().length());
            log.info("CV analysis completed source={} engine={} path={} blocks={} chars={} durationMs={}",
                    source, engineId, imagePath, ocr.blocks().size(),
                    ocr.text() == null ? 0 : ocr.text().length(), durationMs);
            return new CvAnalysisResult(
                    source,
                    imagePath.toString(),
                    dims.width,
                    dims.height,
                    ocr.text(),
                    ocr.blocks(),
                    engineId,
                    ocr.language(),
                    durationMs,
                    Instant.now(),
                    true,
                    null
            );
        } catch (OcrService.OcrUnavailableException ex) {
            long durationMs = durationMs(startNs);
            recordTimer(source, "unavailable", durationMs);
            incrementFailure(source, "engine_unavailable");
            log.warn("CV analysis aborted source={} engine={} path={} reason=engine_unavailable: {}",
                    source, engineId, imagePath, ex.getMessage());
            return new CvAnalysisResult(
                    source, imagePath.toString(),
                    dims.width, dims.height,
                    "", List.of(),
                    engineId, properties.getScreen().getOcrLanguage(),
                    durationMs, Instant.now(),
                    false, "OCR engine unavailable: " + ex.getMessage());
        } catch (OcrService.OcrExecutionException ex) {
            long durationMs = durationMs(startNs);
            recordTimer(source, "engine_error", durationMs);
            incrementFailure(source, "engine_error");
            log.warn("CV analysis failed source={} engine={} path={} reason=engine_error: {}",
                    source, engineId, imagePath, ex.getMessage());
            return new CvAnalysisResult(
                    source, imagePath.toString(),
                    dims.width, dims.height,
                    "", List.of(),
                    engineId, properties.getScreen().getOcrLanguage(),
                    durationMs, Instant.now(),
                    false, ex.getMessage());
        } catch (Exception ex) {
            long durationMs = durationMs(startNs);
            recordTimer(source, "error", durationMs);
            incrementFailure(source, "unexpected_error");
            log.warn("CV analysis failed source={} engine={} path={} reason=unexpected_error",
                    source, engineId, imagePath, ex);
            return failure(source, imagePath, durationMs,
                    "Unexpected error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private OcrEngine activeEngine() {
        if (engineSelector != null) {
            return engineSelector.select();
        }
        return new OcrEngine() {
            @Override public String id() { return OcrService.ENGINE; }
            @Override public org.jarvis.visionsecurity.model.CapabilityStatus capabilityStatus() {
                return ocrService.capabilityStatus();
            }
            @Override public OcrService.StructuredOcrResult extractStructured(Path imagePath) throws Exception {
                return ocrService.extractStructured(imagePath);
            }
        };
    }

    private CvAnalysisResult failure(String source, Path imagePath, long durationMs, String error) {
        return new CvAnalysisResult(
                source,
                imagePath == null ? null : imagePath.toString(),
                null,
                null,
                "",
                List.<CvBlock>of(),
                OcrService.ENGINE,
                properties.getScreen().getOcrLanguage(),
                durationMs,
                Instant.now(),
                false,
                error
        );
    }

    private long durationMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private void incrementRequest(String source, String outcome) {
        if (meterRegistry == null) return;
        Counter.builder(METRIC_REQUESTS)
                .description("Total CV analysis requests")
                .tags(Tags.of("source", source, "outcome", outcome, "engine", OcrService.ENGINE))
                .register(meterRegistry)
                .increment();
    }

    private void incrementFailure(String source, String reason) {
        if (meterRegistry == null) return;
        Counter.builder(METRIC_FAILURES)
                .description("Total CV analysis failures")
                .tags(Tags.of("source", source, "reason", reason, "engine", OcrService.ENGINE))
                .register(meterRegistry)
                .increment();
        incrementRequest(source, "failure");
    }

    private void recordTimer(String source, String outcome, long durationMs) {
        if (meterRegistry == null) return;
        Timer.builder(METRIC_DURATION)
                .description("CV analysis duration")
                .tags(Tags.of("source", source, "outcome", outcome, "engine", OcrService.ENGINE))
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private ImageDimensions readDimensions(Path imagePath) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(imagePath.toFile())) {
            if (stream != null) {
                java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(stream);
                        int w = reader.getWidth(0);
                        int h = reader.getHeight(0);
                        return new ImageDimensions(w, h);
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException ex) {
            log.debug("Failed to read image dimensions for {}: {}", imagePath, ex.getMessage());
        }
        try {
            BufferedImage img = ImageIO.read(imagePath.toFile());
            if (img != null) {
                return new ImageDimensions(img.getWidth(), img.getHeight());
            }
        } catch (IOException ignore) {
            // fall through
        }
        return new ImageDimensions(null, null);
    }

    public static String normalizeSource(String source) {
        if (source == null) return SOURCE_FILE;
        return switch (source.toLowerCase(Locale.ROOT)) {
            case SOURCE_SCREENSHOT, "screen" -> SOURCE_SCREENSHOT;
            default -> SOURCE_FILE;
        };
    }

    private record ImageDimensions(Integer width, Integer height) {
    }
}
