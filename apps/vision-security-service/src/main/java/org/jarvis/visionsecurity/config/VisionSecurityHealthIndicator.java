package org.jarvis.visionsecurity.config;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.jarvis.visionsecurity.service.EmailAlertService;
import org.jarvis.visionsecurity.service.EnrollmentStore;
import org.jarvis.visionsecurity.service.GpuStatusService;
import org.jarvis.visionsecurity.service.OcrService;
import org.jarvis.visionsecurity.service.OpenCvRuntime;
import org.jarvis.visionsecurity.service.ScreenContextService;
import org.jarvis.visionsecurity.service.ScreenshotService;
import org.jarvis.visionsecurity.service.VisionSecurityManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class VisionSecurityHealthIndicator implements HealthIndicator {

    private final ScreenshotService screenshotService;
    private final OcrService ocrService;
    private final EmailAlertService emailAlertService;
    private final GpuStatusService gpuStatusService;
    private final ScreenContextService screenContextService;
    private final VisionSecurityManager manager;
    private final OpenCvRuntime openCvRuntime;
    private final EnrollmentStore enrollmentStore;

    @Override
    public Health health() {
        try {
            CapabilityStatus camera = manager.cameraCapabilityStatus();
            CapabilityStatus screenshot = screenshotService.capabilityStatus();
            CapabilityStatus ocr = ocrService.capabilityStatus();
            CapabilityStatus email = emailAlertService.capabilityStatus();
            GpuStatus gpu = gpuStatusService.currentStatus();
            String readiness = deriveReadiness(camera, screenshot, ocr);

            return Health.up()
                    .withDetail("readiness", readiness)
                    .withDetail("displayServer", screenContextService.detectDisplayServer())
                    .withDetail("monitoringEnabled", manager.isMonitoringEnabled())
                    .withDetail("camera", camera)
                    .withDetail("screenshot", screenshot)
                    .withDetail("ocr", ocr)
                    .withDetail("email", email)
                    .withDetail("gpu", gpu)
                    .withDetail("opencv", buildOpenCvDetails())
                    .withDetail("ownerEnrolled", enrollmentStore.isEnrolled("owner"))
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }

    private Map<String, Object> buildOpenCvDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("faceCascade", openCvRuntime.getFaceCascadePath() != null);
        details.put("altFaceCascade", openCvRuntime.getAltFaceCascadePath() != null);
        details.put("eyeCascade", openCvRuntime.getEyeCascadePath() != null);
        return details;
    }

    private String deriveReadiness(CapabilityStatus camera, CapabilityStatus screenshot, CapabilityStatus ocr) {
        if (!"AVAILABLE".equals(camera.state())) {
            return "UNAVAILABLE";
        }
        if (!"AVAILABLE".equals(screenshot.state()) || !"AVAILABLE".equals(ocr.state())) {
            return "DEGRADED";
        }
        return "READY";
    }
}
