package org.jarvis.visionsecurity.service.cv;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.CvBlock;
import org.jarvis.visionsecurity.model.DetectedElement;
import org.jarvis.visionsecurity.model.RectBox;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.jarvis.visionsecurity.service.LocalCvService;
import org.jarvis.visionsecurity.service.ScreenContextService;
import org.jarvis.visionsecurity.service.SemanticTagger;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wider screen-understanding entry point. Built on top of the existing
 * {@link LocalCvService} OCR slice — it does <strong>not</strong>
 * re-implement OCR. Adds the bits a planner/voice assistant typically
 * wants: which window is in focus, semantic tags, OCR-derived layout
 * regions, and a slot for a (currently not-configured) local VLM.
 */
@Slf4j
@Service
public class ScreenContextCvService {

    private final LocalCvService localCvService;
    private final ScreenContextService screenContextService;
    private final SemanticTagger semanticTagger;
    private final LocalVlmAdapter localVlm;
    private final UiElementDetector uiElementDetector;
    private final ObjectDetector objectDetector;
    private final ScreenContextEventPublisher eventPublisher;
    private final VisionSecurityProperties properties;
    private final CvVlmMetrics vlmMetrics;
    private final MeterRegistry meterRegistry;

    public ScreenContextCvService(LocalCvService localCvService,
                                  ScreenContextService screenContextService,
                                  SemanticTagger semanticTagger,
                                  LocalVlmAdapter localVlm,
                                  UiElementDetector uiElementDetector,
                                  ObjectDetector objectDetector,
                                  ScreenContextEventPublisher eventPublisher,
                                  VisionSecurityProperties properties,
                                  CvVlmMetrics vlmMetrics,
                                  MeterRegistry meterRegistry) {
        this.localCvService = localCvService;
        this.screenContextService = screenContextService;
        this.semanticTagger = semanticTagger;
        this.localVlm = localVlm;
        this.uiElementDetector = uiElementDetector;
        this.objectDetector = objectDetector;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.vlmMetrics = vlmMetrics;
        this.meterRegistry = meterRegistry;
    }

    public ScreenContextResult capture(String userId, Path explicitTarget) {
        long startNs = System.nanoTime();
        log.info("screen-context capture started user={} display={}",
                userId, screenContextService.detectDisplayServer());

        CvAnalysisResult analysis = localCvService.analyzeScreenshot(explicitTarget);
        String activeWindowTitle = safeActiveWindowTitle();
        String activeProcessName = safeActiveProcessName();
        List<String> semanticTags = semanticTagger.deriveTags(
                activeWindowTitle,
                activeProcessName,
                analysis.ocrText() == null ? "" : analysis.ocrText());
        List<RectBox> regions = deriveRegions(analysis.blocks());
        LocalVlmAdapter.VlmResult vlmResult = localVlm.summarise(analysis);
        vlmMetrics.record(providerLabel(), vlmResult);
        ScreenContextResult.VlmSection vlmSection = new ScreenContextResult.VlmSection(
                vlmResult.availability().name(),
                vlmResult.engine(),
                vlmResult.summary(),
                vlmResult.error());

        UiElementDetector.DetectionResult uiResult = safeDetectUi(analysis);
        ObjectDetector.DetectionResult objectResult = safeDetectObjects(analysis);
        recordDetection("ui", uiResult.availability().name(), uiResult.elements().size());
        recordDetection("object", objectResult.availability().name(), objectResult.objects().size());
        List<DetectedElement> uiElements = uiResult.elements();
        List<DetectedElement> objects = objectResult.objects();
        ScreenContextResult.DetectionSection detection = new ScreenContextResult.DetectionSection(
                uiResult.availability().name(),
                objectResult.availability().name());

        long durationMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        ScreenContextResult result = new ScreenContextResult(
                userId,
                Instant.now(),
                durationMs,
                analysis.imagePath(),
                screenContextService.detectDisplayServer(),
                activeWindowTitle,
                activeProcessName,
                semanticTags,
                regions,
                uiElements,
                objects,
                detection,
                analysis,
                vlmSection,
                analysis.success(),
                analysis.success() ? null : analysis.error()
        );

        log.info("screen-context capture finished user={} success={} blocks={} regions={} tags={} vlm={} ui={}/{} obj={}/{} durationMs={}",
                userId, result.success(),
                analysis.blocks() == null ? 0 : analysis.blocks().size(),
                regions.size(), semanticTags.size(),
                vlmResult.availability(),
                uiResult.availability(), uiElements.size(),
                objectResult.availability(), objects.size(),
                durationMs);

        if (properties.getCv().isPublishScreenContextEvent()) {
            eventPublisher.publish(result);
        }
        return result;
    }

    /**
     * Cluster line-level blocks into vertical regions: every cluster of
     * lines whose bbox tops are within {@code regionMergeGap} of each
     * other becomes a single region. This is a pure, deterministic
     * post-processing of OCR blocks — no extra models required.
     */
    static List<RectBox> deriveRegions(List<CvBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        List<CvBlock> sorted = new ArrayList<>(blocks);
        sorted.sort((a, b) -> Integer.compare(a.bbox().y(), b.bbox().y()));
        int regionMergeGap = 40;

        List<RectBox> regions = new ArrayList<>();
        int minX = sorted.get(0).bbox().x();
        int minY = sorted.get(0).bbox().y();
        int maxX = minX + sorted.get(0).bbox().width();
        int maxY = minY + sorted.get(0).bbox().height();

        for (int i = 1; i < sorted.size(); i++) {
            CvBlock current = sorted.get(i);
            int cTop = current.bbox().y();
            int cBottom = cTop + current.bbox().height();
            int cLeft = current.bbox().x();
            int cRight = cLeft + current.bbox().width();
            if (cTop - maxY <= regionMergeGap) {
                minX = Math.min(minX, cLeft);
                minY = Math.min(minY, cTop);
                maxX = Math.max(maxX, cRight);
                maxY = Math.max(maxY, cBottom);
            } else {
                regions.add(new RectBox(minX, minY, maxX - minX, maxY - minY));
                minX = cLeft; minY = cTop; maxX = cRight; maxY = cBottom;
            }
        }
        regions.add(new RectBox(minX, minY, maxX - minX, maxY - minY));
        return regions;
    }

    /**
     * Run the UI-element detector defensively: a detector failure must never
     * sink the whole screen-context capture, so any exception degrades to
     * {@code UNAVAILABLE} with the reason recorded.
     */
    private String providerLabel() {
        String provider = properties.getCv().getVlm().getProvider();
        return provider == null || provider.isBlank() ? localVlm.id() : provider;
    }

    /**
     * {@code jarvis_cv_detection_total{type,availability}} — counts detector
     * runs by type (ui/object) and outcome. Low cardinality by construction.
     */
    private void recordDetection(String type, String availability, int count) {
        Counter.builder("jarvis_cv_detection_total")
                .tags(Tags.of("type", type,
                        "availability", availability.toLowerCase(Locale.ROOT)))
                .register(meterRegistry)
                .increment();
        log.debug("detection type={} availability={} count={}", type, availability, count);
    }

    private UiElementDetector.DetectionResult safeDetectUi(CvAnalysisResult analysis) {
        try {
            UiElementDetector.DetectionResult r = uiElementDetector.detect(analysis);
            return r == null ? UiElementDetector.DetectionResult.unavailable("detector returned null") : r;
        } catch (RuntimeException ex) {
            log.warn("ui-element detector {} failed: {}", uiElementDetector.id(), ex.getMessage());
            return UiElementDetector.DetectionResult.unavailable(
                    uiElementDetector.id() + ": " + ex.getMessage());
        }
    }

    private ObjectDetector.DetectionResult safeDetectObjects(CvAnalysisResult analysis) {
        try {
            ObjectDetector.DetectionResult r = objectDetector.detect(analysis);
            return r == null ? ObjectDetector.DetectionResult.unavailable("detector returned null") : r;
        } catch (RuntimeException ex) {
            log.warn("object detector {} failed: {}", objectDetector.id(), ex.getMessage());
            return ObjectDetector.DetectionResult.unavailable(
                    objectDetector.id() + ": " + ex.getMessage());
        }
    }

    private String safeActiveWindowTitle() {
        try {
            String v = screenContextService.activeWindowTitle();
            return v == null ? "" : v;
        } catch (Exception ex) {
            return "";
        }
    }

    private String safeActiveProcessName() {
        try {
            String v = screenContextService.activeProcessName();
            return v == null ? "" : v;
        } catch (Exception ex) {
            return "";
        }
    }
}
