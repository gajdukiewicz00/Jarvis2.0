package org.jarvis.visionsecurity.service;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.VisionSecurityStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opencv.core.Mat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the real {@code startMonitoring}/{@code stopMonitoring} scheduling path
 * (as opposed to flipping the internal atomic flag via reflection), so the virtual
 * thread factory and the recurring {@code monitorOnce} tick get real coverage.
 */
@ExtendWith(MockitoExtension.class)
class VisionSecurityManagerMonitoringLifecycleTest {

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

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @BeforeEach
    void setUp() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getMonitoring().setCheckIntervalMs(60_000L);
        properties.getMonitoring().setDebounceUnknownFrames(1);
        properties.getMonitoring().setOwnerGraceFrames(0);
        properties.getMonitoring().setAlertCooldownSeconds(0L);

        lenient().when(cameraCaptureService.capabilityStatus()).thenReturn(available("Camera ready"));
        lenient().when(screenshotService.capabilityStatus()).thenReturn(available("Screenshot ready"));
        lenient().when(ocrService.capabilityStatus()).thenReturn(available("OCR ready"));
        lenient().when(emailAlertService.capabilityStatus()).thenReturn(available("Email ready"));
        lenient().when(gpuStatusService.currentStatus()).thenReturn(new GpuStatus(false, false, "cpu", "CPU baseline"));
        lenient().when(screenContextService.detectDisplayServer()).thenReturn("x11");

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
    void startMonitoringSchedulesRecurringCheckAndStopStopsIt() throws Exception {
        Mat frame = new Mat();
        when(cameraCaptureService.captureFrame("monitoring")).thenReturn(frame);
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), isNull())).thenReturn(
                new PipelineResult(DecisionType.OWNER_PRESENT, 1, "Owner recognised", List.of(), null, null));

        VisionSecurityStatus started = manager.startMonitoring("owner");
        assertThat(started.monitoringEnabled()).isTrue();
        assertThat(started.lastReason()).isEqualTo("Monitoring started");
        assertThat(manager.isMonitoringEnabled()).isTrue();

        // The recurring task runs on a background virtual thread; wait for at least
        // one tick instead of racing a fixed sleep.
        verify(cameraCaptureService, timeout(2_000)).captureFrame("monitoring");
        verify(visionPipelineService, timeout(2_000)).analyze(eq("owner"), any(Mat.class), isNull());

        VisionSecurityStatus stopped = manager.stopMonitoring("owner");
        assertThat(stopped.monitoringEnabled()).isFalse();
        assertThat(stopped.lastReason()).isEqualTo("Monitoring stopped");
        assertThat(manager.isMonitoringEnabled()).isFalse();
    }

    @Test
    void startMonitoringThrowsWhenCameraReservedByExclusiveOperation() throws Exception {
        exclusiveOperation().set("owner enrollment");

        assertThatThrownBy(() -> manager.startMonitoring("owner"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot start monitoring while owner enrollment is using the camera");
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<String> exclusiveOperation() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("activeExclusiveOperation");
        field.setAccessible(true);
        return (AtomicReference<String>) field.get(manager);
    }

    private CapabilityStatus available(String detail) {
        return new CapabilityStatus("AVAILABLE", detail);
    }
}
