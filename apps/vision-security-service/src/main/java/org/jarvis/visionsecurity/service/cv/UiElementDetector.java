package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.DetectedElement;

import java.util.List;

/**
 * Detects UI elements (button / input / tab / window / menu …) on a captured
 * screen. Implementations MUST run 100% locally — Jarvis forbids cloud vision
 * APIs.
 *
 * <p>The default bean is {@link NotConfiguredUiElementDetector}, which returns
 * {@link Availability#NOT_CONFIGURED} and an empty list. It never fabricates
 * detections. A future neural implementation (e.g. a local UI-detection model)
 * or an OCR-rule heuristic can replace it by registering a higher-priority
 * {@code @Component} implementing this interface.</p>
 */
public interface UiElementDetector {

    String id();

    /**
     * Best-effort UI-element detection over the OCR/screenshot context.
     * Implementations MUST NOT call any remote service.
     */
    DetectionResult detect(CvAnalysisResult context);

    /** Tri-state availability so callers can render an honest status. */
    enum Availability {
        /** Detector is wired and ran. */
        READY,
        /** No detector is configured. MUST return an empty element list. */
        NOT_CONFIGURED,
        /** Detector is configured but its backend/model is unavailable. */
        UNAVAILABLE
    }

    record DetectionResult(
            Availability availability,
            List<DetectedElement> elements,
            String error
    ) {
        public static DetectionResult notConfigured(String detectorId) {
            return new DetectionResult(
                    Availability.NOT_CONFIGURED,
                    List.of(),
                    "UI element detection not configured (" + detectorId + "). "
                            + "No cloud APIs will ever be used; wire a local "
                            + "detector model and provide a UiElementDetector bean.");
        }

        public static DetectionResult ready(List<DetectedElement> elements) {
            return new DetectionResult(Availability.READY,
                    elements == null ? List.of() : List.copyOf(elements), null);
        }

        public static DetectionResult unavailable(String reason) {
            return new DetectionResult(Availability.UNAVAILABLE, List.of(), reason);
        }
    }
}
