package org.jarvis.vision.phase10;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit test for {@link VisionStatusController}, mirroring the plain
 * instantiation style already used by {@code DesktopFrameIngestControllerTest}
 * in this package (its dependencies are simple {@code @ConfigurationProperties}
 * holders, so a full {@code @WebMvcTest} slice isn't needed to exercise the
 * controller's branches).
 */
class VisionStatusControllerTest {

    private DemoModeProperties demoMode;
    private VisionRetentionProperties retention;
    private VisionStatusController controller;

    @BeforeEach
    void setUp() {
        demoMode = new DemoModeProperties();
        retention = new VisionRetentionProperties();
        controller = new VisionStatusController(demoMode, retention);
    }

    @Test
    void statusReportsDemoModeAndRetentionConfiguration() {
        demoMode.setEnabled(true);
        retention.setEnabled(false);
        retention.setDays(14);
        retention.setRoot("/tmp/vision-retention");

        Map<String, Object> body = controller.status();

        assertThat(body.get("service")).isEqualTo("vision-security-service");
        assertThat(body.get("demoMode")).isEqualTo(true);
        assertThat(body).containsKeys("opencvAvailable", "tesseractAvailable", "checkedAt");

        @SuppressWarnings("unchecked")
        Map<String, Object> retentionBody = (Map<String, Object>) body.get("retention");
        assertThat(retentionBody.get("enabled")).isEqualTo(false);
        assertThat(retentionBody.get("days")).isEqualTo(14);
        assertThat(retentionBody.get("root")).isEqualTo("/tmp/vision-retention");
    }

    @Test
    void statusThrottlesExpensiveProbesOnRapidRepeatedCalls() {
        Map<String, Object> first = controller.status();
        Map<String, Object> second = controller.status();

        // The second call within the 30s throttle window reuses the same
        // checkedAt timestamp instead of re-running the class/binary probes.
        assertThat(second.get("checkedAt")).isEqualTo(first.get("checkedAt"));
    }

    @Test
    void statusDetectsOpenCvOnClasspath() {
        Map<String, Object> body = controller.status();

        // org.opencv.videoio.VideoCapture is a genuine compile-time dependency
        // of this module, so the classpath probe must find it.
        assertThat(body.get("opencvAvailable")).isEqualTo(true);
    }
}
