package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.jarvis.visionsecurity.model.VisionSecurityStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionSecurityManagerTest {

    @Mock
    private CameraCaptureService cameraCaptureService;
    @Mock
    private VisionPipelineService visionPipelineService;
    @Mock
    private FaceVerificationService faceVerificationService;
    @Mock
    private IncidentStore incidentStore;
    @Mock
    private ScreenshotService screenshotService;
    @Mock
    private ScreenContextService screenContextService;
    @Mock
    private EmailAlertService emailAlertService;
    @Mock
    private GpuStatusService gpuStatusService;
    @Mock
    private OcrService ocrService;

    private VisionSecurityManager manager;

    @BeforeEach
    void setUp() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getMonitoring().setCheckIntervalMs(60_000L);
        properties.getEnrollment().setSampleSpacingMs(1L);
        properties.getEnrollment().setCaptureTimeoutSeconds(1L);

        lenient().when(cameraCaptureService.capabilityStatus()).thenReturn(available("Camera ready"));
        lenient().when(cameraCaptureService.captureFrame("monitoring")).thenThrow(new IllegalStateException("camera unavailable"));
        when(screenshotService.capabilityStatus()).thenReturn(available("Screenshot ready"));
        when(ocrService.capabilityStatus()).thenReturn(available("OCR ready"));
        when(emailAlertService.capabilityStatus()).thenReturn(available("Email ready"));
        when(gpuStatusService.currentStatus()).thenReturn(new GpuStatus(false, false, "cpu", "CPU baseline"));
        when(screenContextService.detectDisplayServer()).thenReturn("x11");

        manager = new VisionSecurityManager(
                properties,
                cameraCaptureService,
                visionPipelineService,
                faceVerificationService,
                incidentStore,
                screenshotService,
                screenContextService,
                emailAlertService,
                gpuStatusService,
                ocrService
        );
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void startMonitoringReturnsCurrentStatusWhenAlreadyActive() throws Exception {
        monitoringEnabled().set(true);

        VisionSecurityStatus status = manager.startMonitoring("owner");

        assertThat(status.monitoringEnabled()).isTrue();
        assertThat(status.lastReason()).isEqualTo("Monitoring already active");
    }

    @Test
    void stopMonitoringReturnsCurrentStatusWhenAlreadyStopped() {
        VisionSecurityStatus status = manager.stopMonitoring("owner");

        assertThat(status.monitoringEnabled()).isFalse();
        assertThat(status.lastReason()).isEqualTo("Monitoring already stopped");
    }

    @Test
    void captureEnrollmentFailsFastWhenMonitoringIsActive() throws Exception {
        monitoringEnabled().set(true);

        assertThatThrownBy(() -> manager.captureEnrollment("owner", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stop monitoring before starting owner enrollment");
    }

    @Test
    void pipelineSnapshotFailsFastWhenMonitoringIsActive() throws Exception {
        monitoringEnabled().set(true);

        assertThatThrownBy(() -> manager.capturePipelineSnapshot("owner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stop monitoring before starting pipeline snapshot");
    }

    @Test
    void cameraCapabilityStatusReportsReservationDuringExclusiveOperation() throws Exception {
        exclusiveOperation().set("owner enrollment");

        CapabilityStatus status = manager.cameraCapabilityStatus();

        assertThat(status).isEqualTo(new CapabilityStatus("AVAILABLE", "Camera reserved by owner enrollment"));
        verifyNoInteractions(cameraCaptureService);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<String> exclusiveOperation() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("activeExclusiveOperation");
        field.setAccessible(true);
        return (AtomicReference<String>) field.get(manager);
    }

    private AtomicBoolean monitoringEnabled() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("monitoringEnabled");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(manager);
    }

    private CapabilityStatus available(String detail) {
        return new CapabilityStatus("AVAILABLE", detail);
    }
}
