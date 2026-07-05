package org.jarvis.visionsecurity.service;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.ScreenContextEvidence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.opencv.core.Mat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Drives the private {@code monitorOnce} tick directly via reflection so the
 * alert / incident-recording branches are exercised deterministically instead
 * of racing the real scheduled executor.
 */
class VisionSecurityManagerMonitorOnceTest {

    private final CameraCaptureService cameraCaptureService = mock(CameraCaptureService.class);
    private final VisionPipelineService visionPipelineService = mock(VisionPipelineService.class);
    private final FaceVerificationService faceVerificationService = mock(FaceVerificationService.class);
    private final IncidentStore incidentStore = mock(IncidentStore.class);
    private final ScreenshotService screenshotService = mock(ScreenshotService.class);
    private final ScreenContextService screenContextService = mock(ScreenContextService.class);
    private final EmailAlertService emailAlertService = mock(EmailAlertService.class);
    private final GpuStatusService gpuStatusService = mock(GpuStatusService.class);
    private final OcrService ocrService = mock(OcrService.class);

    private VisionSecurityProperties properties;
    private VisionSecurityManager manager;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @BeforeEach
    void setUp() {
        properties = new VisionSecurityProperties();
        properties.getMonitoring().setDebounceUnknownFrames(1);
        properties.getMonitoring().setOwnerGraceFrames(0);
        properties.getMonitoring().setAlertCooldownSeconds(0L);

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

    @Test
    void monitorOnceUpdatesDecisionWithoutIncidentWhenOwnerPresent() throws Exception {
        installDecisionEngine();
        Mat frame = new Mat();
        when(cameraCaptureService.captureFrame("monitoring")).thenReturn(frame);
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), isNull())).thenReturn(
                new PipelineResult(DecisionType.OWNER_PRESENT, 1, "Owner recognised", List.of(), null, null));

        invokeMonitorOnce("owner");

        assertThat(lastReasonField()).isEqualTo("Owner recognised");
        assertThat(lastCameraStatusField().state()).isEqualTo("AVAILABLE");
        verifyNoInteractions(incidentStore);
    }

    @Test
    void monitorOnceReportsTemporalConsensusReasonWhenGraceFrameOverridesFrameDecision() throws Exception {
        properties.getMonitoring().setOwnerGraceFrames(2);
        installDecisionEngine();
        when(cameraCaptureService.captureFrame("monitoring")).thenAnswer(inv -> new Mat());
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), isNull())).thenReturn(
                new PipelineResult(DecisionType.OWNER_PRESENT, 1, "Owner recognised", List.of(), null, null),
                new PipelineResult(DecisionType.UNKNOWN_PERSON, 1, "Unknown face detected", List.of(), null, null));

        invokeMonitorOnce("owner");
        invokeMonitorOnce("owner");

        assertThat(lastReasonField())
                .isEqualTo("Temporal consensus kept OWNER_PRESENT while frame-level result was UNKNOWN_PERSON"
                        + " (Unknown face detected)");
        verifyNoInteractions(incidentStore);
    }

    @Test
    void monitorOnceRecordsIncidentWhenUnknownPersonConfirmed(@TempDir Path tempDir) throws Exception {
        installDecisionEngine();
        Mat frame = new Mat();
        Path incidentDir = tempDir.resolve("incident-1");
        Instant createdAt = Instant.parse("2026-05-01T00:00:00Z");

        when(cameraCaptureService.captureFrame("monitoring")).thenReturn(frame);
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), isNull())).thenReturn(
                new PipelineResult(DecisionType.UNKNOWN_PERSON, 1, "Unknown face detected", List.of(), null, null));
        when(incidentStore.createIncidentDirectory(eq("owner"), any(Instant.class))).thenReturn(incidentDir);
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), eq(incidentDir))).thenReturn(
                new PipelineResult(DecisionType.UNKNOWN_PERSON, 1, "Unknown face detected", List.of(),
                        null, incidentDir.resolve("original.png").toString()));
        Path screenshotPath = incidentDir.resolve("screenshot.png");
        when(screenshotService.capture(eq(screenshotPath))).thenReturn(screenshotPath);
        ScreenContextEvidence evidence = new ScreenContextEvidence("Terminal", "bash", "some ocr text", List.of("DEVELOPMENT"));
        when(screenContextService.collect(eq(screenshotPath), eq(incidentDir.resolve("screen-ocr.txt"))))
                .thenReturn(new ScreenContextService.ScreenContextCapture(evidence, incidentDir.resolve("screen-ocr.txt").toString()));
        when(emailAlertService.sendIncidentAlert(any(IncidentRecord.class)))
                .thenReturn(new EmailDelivery(true, true, "Alert email sent"));

        invokeMonitorOnce("owner");

        ArgumentCaptor<IncidentRecord> captor = ArgumentCaptor.forClass(IncidentRecord.class);
        verify(incidentStore).saveIncident(captor.capture());
        IncidentRecord saved = captor.getValue();
        assertThat(saved.userId()).isEqualTo("owner");
        assertThat(saved.decision()).isEqualTo(DecisionType.UNKNOWN_PERSON);
        assertThat(saved.screenshotPath()).isEqualTo(screenshotPath.toString());
        assertThat(saved.screenContext().activeWindowTitle()).isEqualTo("Terminal");
        assertThat(saved.emailDelivery().sent()).isTrue();
        assertThat(lastIncidentIdField()).isEqualTo(incidentDir.getFileName().toString());
        assertThat(lastReasonField()).isEqualTo("Unknown person confirmed and incident stored");
    }

    @Test
    void monitorOnceFallsBackToPartialEvidenceWhenScreenshotCaptureFails(@TempDir Path tempDir) throws Exception {
        installDecisionEngine();
        Mat frame = new Mat();
        Path incidentDir = tempDir.resolve("incident-2");

        when(cameraCaptureService.captureFrame("monitoring")).thenReturn(frame);
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), isNull())).thenReturn(
                new PipelineResult(DecisionType.UNKNOWN_PERSON, 1, "Unknown face detected", List.of(), null, null));
        when(incidentStore.createIncidentDirectory(eq("owner"), any(Instant.class))).thenReturn(incidentDir);
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), eq(incidentDir))).thenReturn(
                new PipelineResult(DecisionType.UNKNOWN_PERSON, 1, "Unknown face detected", List.of(), null, null));
        when(screenshotService.capture(any(Path.class))).thenThrow(new IllegalStateException("no display"));
        when(emailAlertService.sendIncidentAlert(any(IncidentRecord.class)))
                .thenReturn(new EmailDelivery(false, false, "Alert email recipient is not configured"));

        invokeMonitorOnce("owner");

        ArgumentCaptor<IncidentRecord> captor = ArgumentCaptor.forClass(IncidentRecord.class);
        verify(incidentStore).saveIncident(captor.capture());
        IncidentRecord saved = captor.getValue();
        assertThat(saved.screenshotPath()).isNull();
        assertThat(saved.ocrTextPath()).isNull();
        assertThat(saved.screenContext().ocrText()).contains("Screen capture failed: no display");
        assertThat(saved.screenContext().semanticTags()).containsExactly("GENERAL_DESKTOP");
        verifyNoInteractions(screenContextService);
    }

    @Test
    void monitorOnceMarksCameraUnavailableWhenCaptureThrows() throws Exception {
        when(cameraCaptureService.captureFrame("monitoring")).thenThrow(new IllegalStateException("camera unplugged"));

        invokeMonitorOnce("owner");

        assertThat(lastCameraStatusField().state()).isEqualTo("UNAVAILABLE");
        assertThat(lastCameraStatusField().detail()).isEqualTo("camera unplugged");
        assertThat(lastReasonField()).isEqualTo("Monitoring error: camera unplugged");
    }

    private void installDecisionEngine() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("decisionEngine");
        field.setAccessible(true);
        field.set(manager, new MonitoringDecisionEngine(
                properties.getMonitoring().getDebounceUnknownFrames(),
                Duration.ofSeconds(properties.getMonitoring().getAlertCooldownSeconds()),
                properties.getMonitoring().getOwnerGraceFrames()));
    }

    private void invokeMonitorOnce(String userId) throws Exception {
        Method method = VisionSecurityManager.class.getDeclaredMethod("monitorOnce", String.class);
        method.setAccessible(true);
        method.invoke(manager, userId);
    }

    private String lastReasonField() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("lastReason");
        field.setAccessible(true);
        return (String) field.get(manager);
    }

    private String lastIncidentIdField() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("lastIncidentId");
        field.setAccessible(true);
        return (String) field.get(manager);
    }

    private CapabilityStatus lastCameraStatusField() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("lastCameraStatus");
        field.setAccessible(true);
        return (CapabilityStatus) field.get(manager);
    }
}
