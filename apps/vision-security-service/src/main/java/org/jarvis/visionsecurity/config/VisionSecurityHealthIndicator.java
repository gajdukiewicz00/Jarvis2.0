package org.jarvis.visionsecurity.config;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.jarvis.visionsecurity.service.CameraCaptureService;
import org.jarvis.visionsecurity.service.EmailAlertService;
import org.jarvis.visionsecurity.service.GpuStatusService;
import org.jarvis.visionsecurity.service.OcrService;
import org.jarvis.visionsecurity.service.ScreenContextService;
import org.jarvis.visionsecurity.service.ScreenshotService;
import org.jarvis.visionsecurity.service.VisionSecurityManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VisionSecurityHealthIndicator implements HealthIndicator {

    private final CameraCaptureService cameraCaptureService;
    private final ScreenshotService screenshotService;
    private final OcrService ocrService;
    private final EmailAlertService emailAlertService;
    private final GpuStatusService gpuStatusService;
    private final ScreenContextService screenContextService;
    private final VisionSecurityManager manager;

    @Override
    public Health health() {
        try {
            CapabilityStatus camera = cameraCaptureService.capabilityStatus();
            CapabilityStatus screenshot = screenshotService.capabilityStatus();
            CapabilityStatus ocr = ocrService.capabilityStatus();
            CapabilityStatus email = emailAlertService.capabilityStatus();
            GpuStatus gpu = gpuStatusService.currentStatus();
            String readiness = deriveReadiness(camera, screenshot, ocr);

            return Health.up()
                    .withDetail("readiness", readiness)
                    .withDetail("displayServer", screenContextService.detectDisplayServer())
                    .withDetail("monitoringEnabled", manager.statusFor(null).monitoringEnabled())
                    .withDetail("camera", camera)
                    .withDetail("screenshot", screenshot)
                    .withDetail("ocr", ocr)
                    .withDetail("email", email)
                    .withDetail("gpu", gpu)
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
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
