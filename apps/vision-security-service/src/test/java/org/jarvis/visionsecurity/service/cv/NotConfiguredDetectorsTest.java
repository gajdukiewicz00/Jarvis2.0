package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default UI/object detectors must report NOT_CONFIGURED and never
 * fabricate detections.
 */
class NotConfiguredDetectorsTest {

    private final CvAnalysisResult analysis = new CvAnalysisResult(
            "screenshot", "/tmp/x.png", 800, 600, "hello",
            List.of(), "tesseract", "eng", 5L, Instant.now(), true, null);

    @Test
    void uiDetectorIsNotConfiguredAndEmpty() {
        UiElementDetector detector = new NotConfiguredUiElementDetector();
        UiElementDetector.DetectionResult r = detector.detect(analysis);

        assertThat(detector.id()).isEqualTo("not-configured");
        assertThat(r.availability()).isEqualTo(UiElementDetector.Availability.NOT_CONFIGURED);
        assertThat(r.elements()).isEmpty();
        assertThat(r.error()).contains("not configured");
    }

    @Test
    void objectDetectorIsNotConfiguredAndEmpty() {
        ObjectDetector detector = new NotConfiguredObjectDetector();
        ObjectDetector.DetectionResult r = detector.detect(analysis);

        assertThat(detector.id()).isEqualTo("not-configured");
        assertThat(r.availability()).isEqualTo(ObjectDetector.Availability.NOT_CONFIGURED);
        assertThat(r.objects()).isEmpty();
        assertThat(r.error()).contains("not configured");
    }

    @Test
    void readyAndUnavailableFactoriesAreConsistent() {
        UiElementDetector.DetectionResult ready =
                UiElementDetector.DetectionResult.ready(null);
        assertThat(ready.availability()).isEqualTo(UiElementDetector.Availability.READY);
        assertThat(ready.elements()).isEmpty();

        ObjectDetector.DetectionResult down =
                ObjectDetector.DetectionResult.unavailable("model offline");
        assertThat(down.availability()).isEqualTo(ObjectDetector.Availability.UNAVAILABLE);
        assertThat(down.error()).isEqualTo("model offline");
    }
}
