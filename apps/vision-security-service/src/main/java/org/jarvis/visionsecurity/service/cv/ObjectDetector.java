package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.DetectedElement;

import java.util.List;

/**
 * Detects general scene objects (person / laptop / cup …) in a captured image.
 * Useful for the camera/world view rather than screens. Implementations MUST
 * run 100% locally — Jarvis forbids cloud vision APIs.
 *
 * <p>The default bean is {@link NotConfiguredObjectDetector}, which returns
 * {@link Availability#NOT_CONFIGURED} and an empty list. It never fabricates
 * detections. A future local model (YOLO/DETR via a local runtime) can replace
 * it by registering a higher-priority {@code @Component}.</p>
 */
public interface ObjectDetector {

    String id();

    /**
     * Best-effort object detection over the captured image referenced by
     * {@code context}. Implementations MUST NOT call any remote service.
     */
    DetectionResult detect(CvAnalysisResult context);

    /** Tri-state availability so callers can render an honest status. */
    enum Availability {
        READY,
        NOT_CONFIGURED,
        UNAVAILABLE
    }

    record DetectionResult(
            Availability availability,
            List<DetectedElement> objects,
            String error
    ) {
        public static DetectionResult notConfigured(String detectorId) {
            return new DetectionResult(
                    Availability.NOT_CONFIGURED,
                    List.of(),
                    "Object detection not configured (" + detectorId + "). "
                            + "No cloud APIs will ever be used; wire a local "
                            + "detector model and provide an ObjectDetector bean.");
        }

        public static DetectionResult ready(List<DetectedElement> objects) {
            return new DetectionResult(Availability.READY,
                    objects == null ? List.of() : List.copyOf(objects), null);
        }

        public static DetectionResult unavailable(String reason) {
            return new DetectionResult(Availability.UNAVAILABLE, List.of(), reason);
        }
    }
}
