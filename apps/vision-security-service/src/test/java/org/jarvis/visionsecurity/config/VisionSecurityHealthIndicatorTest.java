package org.jarvis.visionsecurity.config;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionSecurityHealthIndicatorTest {

    @Mock
    private ScreenshotService screenshotService;
    @Mock
    private OcrService ocrService;
    @Mock
    private EmailAlertService emailAlertService;
    @Mock
    private GpuStatusService gpuStatusService;
    @Mock
    private ScreenContextService screenContextService;
    @Mock
    private VisionSecurityManager manager;
    @Mock
    private OpenCvRuntime openCvRuntime;
    @Mock
    private EnrollmentStore enrollmentStore;

    @Test
    void healthUsesManagerCameraStatusWhenMonitoringOwnsTheCamera() {
        when(manager.cameraCapabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "Camera reserved by monitoring"));
        when(manager.isMonitoringEnabled()).thenReturn(true);
        when(screenshotService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "Screenshot ready"));
        when(ocrService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "OCR ready"));
        when(emailAlertService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "Email ready"));
        when(gpuStatusService.currentStatus()).thenReturn(new GpuStatus(false, false, "cpu", "CPU baseline"));
        when(screenContextService.detectDisplayServer()).thenReturn("x11");
        when(openCvRuntime.getFaceCascadePath()).thenReturn(Path.of("/tmp/face.xml"));
        when(openCvRuntime.getAltFaceCascadePath()).thenReturn(null);
        when(openCvRuntime.getEyeCascadePath()).thenReturn(null);
        when(enrollmentStore.isEnrolled("owner")).thenReturn(false);

        VisionSecurityHealthIndicator indicator = new VisionSecurityHealthIndicator(
                screenshotService,
                ocrService,
                emailAlertService,
                gpuStatusService,
                screenContextService,
                manager,
                openCvRuntime,
                enrollmentStore
        );

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("monitoringEnabled", true);
        assertThat(health.getDetails()).containsEntry("camera", new CapabilityStatus("AVAILABLE", "Camera reserved by monitoring"));
        assertThat(health.getDetails()).containsEntry("ownerEnrolled", false);
        verify(manager).cameraCapabilityStatus();
        verify(manager).isMonitoringEnabled();
    }
}
