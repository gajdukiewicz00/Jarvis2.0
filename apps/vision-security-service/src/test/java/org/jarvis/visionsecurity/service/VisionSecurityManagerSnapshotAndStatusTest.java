package org.jarvis.visionsecurity.service;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.PipelineSnapshotResult;
import org.jarvis.visionsecurity.model.ScreenContextEvidence;
import org.jarvis.visionsecurity.model.StagePaths;
import org.jarvis.visionsecurity.model.VisionSecurityConfigView;
import org.jarvis.visionsecurity.model.VisionSecurityStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Mat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the small delegating methods on {@link VisionSecurityManager}
 * (pipeline snapshot, alerts, incidents, config/status views) that don't fit
 * naturally into the monitoring or enrollment focused test classes.
 */
class VisionSecurityManagerSnapshotAndStatusTest {

    private final CameraCaptureService cameraCaptureService = mock(CameraCaptureService.class);
    private final VisionPipelineService visionPipelineService = mock(VisionPipelineService.class);
    private final FaceVerificationService faceVerificationService = mock(FaceVerificationService.class);
    private final IncidentStore incidentStore = mock(IncidentStore.class);
    private final ScreenshotService screenshotService = mock(ScreenshotService.class);
    private final ScreenContextService screenContextService = mock(ScreenContextService.class);
    private final EmailAlertService emailAlertService = mock(EmailAlertService.class);
    private final GpuStatusService gpuStatusService = mock(GpuStatusService.class);
    private final OcrService ocrService = mock(OcrService.class);

    private VisionSecurityManager manager;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @BeforeEach
    void setUp() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
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
    void capturePipelineSnapshotReturnsAnalyzedResultForFreshFrame(@TempDir Path tempDir) throws Exception {
        Instant createdAt = Instant.now();
        Path snapshotDir = tempDir.resolve("snapshot-1");
        CameraCaptureService.CameraSession session = mock(CameraCaptureService.CameraSession.class);
        Mat frame = new Mat();
        when(session.captureFrame()).thenReturn(frame);
        when(cameraCaptureService.withCameraSession(eq("pipeline snapshot"), any())).thenAnswer(inv -> {
            CameraCaptureService.CameraSessionCallback<?> callback = inv.getArgument(1);
            return callback.execute(session);
        });
        when(incidentStore.createSnapshotDirectory(eq("owner"), any(Instant.class))).thenReturn(snapshotDir);
        PipelineResult analyzed = new PipelineResult(DecisionType.OWNER_PRESENT, 1, "Owner recognised",
                List.of(), stagePaths(snapshotDir), snapshotDir.resolve("original.png").toString());
        when(visionPipelineService.analyze(eq("owner"), any(Mat.class), eq(snapshotDir))).thenReturn(analyzed);

        PipelineSnapshotResult result = manager.capturePipelineSnapshot("owner");

        assertThat(result.userId()).isEqualTo("owner");
        assertThat(result.outputDirectory()).isEqualTo(snapshotDir.toString());
        assertThat(result.pipeline()).isEqualTo(analyzed);
    }

    @Test
    void resetEnrollmentDelegatesToFaceVerificationServiceAndReturnsStatus() throws Exception {
        stubCapabilitiesAvailable();
        when(faceVerificationService.isEnrolled("owner")).thenReturn(false);

        VisionSecurityStatus status = manager.resetEnrollment("owner");

        verify(faceVerificationService).resetEnrollment("owner");
        assertThat(status.ownerEnrolled()).isFalse();
    }

    @Test
    void sendTestAlertDelegatesToEmailAlertService() {
        EmailDelivery delivery = new EmailDelivery(true, true, "Test alert sent");
        when(emailAlertService.sendTestAlert("owner")).thenReturn(delivery);

        EmailDelivery result = manager.sendTestAlert("owner");

        assertThat(result).isEqualTo(delivery);
    }

    @Test
    void listIncidentsDelegatesToIncidentStore() throws Exception {
        IncidentRecord record = incidentRecord();
        when(incidentStore.listIncidents("owner", 10)).thenReturn(List.of(record));

        List<IncidentRecord> incidents = manager.listIncidents("owner", 10);

        assertThat(incidents).containsExactly(record);
    }

    @Test
    void incidentReturnsRecordWhenFound() throws Exception {
        IncidentRecord record = incidentRecord();
        when(incidentStore.loadIncident("owner", record.incidentId())).thenReturn(record);

        IncidentRecord result = manager.incident("owner", record.incidentId());

        assertThat(result).isEqualTo(record);
    }

    @Test
    void incidentThrowsWhenNotFound() throws Exception {
        when(incidentStore.loadIncident("owner", "missing-id")).thenReturn(null);

        assertThatThrownBy(() -> manager.incident("owner", "missing-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incident not found: missing-id");
    }

    @Test
    void configViewReflectsCurrentPropertiesAndDisplayServer() {
        when(screenContextService.detectDisplayServer()).thenReturn("wayland");

        VisionSecurityConfigView view = manager.configView();

        assertThat(view.displayServer()).isEqualTo("wayland");
        assertThat(view.ocrLanguage()).isEqualTo("eng");
    }

    @Test
    void statusReportsReadyWhenEverythingAvailableAndOwnerEnrolled() throws Exception {
        stubCapabilitiesAvailable();
        when(faceVerificationService.isEnrolled("owner")).thenReturn(true);
        when(incidentStore.incidentCount("owner")).thenReturn(2);

        VisionSecurityStatus status = manager.statusFor("owner");

        assertThat(status.serviceStatus()).isEqualTo("READY");
        assertThat(status.ownerEnrolled()).isTrue();
        assertThat(status.incidentCount()).isEqualTo(2);
    }

    @Test
    void statusReportsDegradedWhenOwnerNotEnrolled() throws Exception {
        stubCapabilitiesAvailable();
        when(faceVerificationService.isEnrolled("owner")).thenReturn(false);

        VisionSecurityStatus status = manager.statusFor("owner");

        assertThat(status.serviceStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void statusReportsUnavailableWhenCameraUnavailable() throws Exception {
        when(cameraCaptureService.capabilityStatus()).thenReturn(new CapabilityStatus("UNAVAILABLE", "no camera"));
        when(screenshotService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "ok"));
        when(ocrService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "ok"));
        when(emailAlertService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "ok"));
        when(gpuStatusService.currentStatus()).thenReturn(new GpuStatus(false, false, "cpu", "cpu"));
        when(faceVerificationService.isEnrolled("owner")).thenReturn(true);

        VisionSecurityStatus status = manager.statusFor("owner");

        assertThat(status.serviceStatus()).isEqualTo("UNAVAILABLE");
        assertThat(status.camera().state()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void statusSwallowsIncidentCountFailureAndDefaultsToZero() throws Exception {
        stubCapabilitiesAvailable();
        when(faceVerificationService.isEnrolled("owner")).thenReturn(true);
        when(incidentStore.incidentCount("owner")).thenThrow(new java.io.IOException("disk error"));

        VisionSecurityStatus status = manager.statusFor("owner");

        assertThat(status.incidentCount()).isZero();
    }

    @Test
    void cameraCapabilityDelegatesToCameraCaptureServiceWhenIdle() {
        when(cameraCaptureService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "Camera backend V4L2"));

        CapabilityStatus status = manager.cameraCapabilityStatus();

        assertThat(status.detail()).isEqualTo("Camera backend V4L2");
    }

    private void stubCapabilitiesAvailable() {
        when(cameraCaptureService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "ok"));
        when(screenshotService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "ok"));
        when(ocrService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "ok"));
        when(emailAlertService.capabilityStatus()).thenReturn(new CapabilityStatus("AVAILABLE", "ok"));
        when(gpuStatusService.currentStatus()).thenReturn(new GpuStatus(false, false, "cpu", "cpu"));
    }

    private StagePaths stagePaths(Path directory) {
        return new StagePaths(
                directory.resolve("original.png").toString(),
                directory.resolve("enhanced.png").toString(),
                directory.resolve("segmentation-mask.png").toString(),
                directory.resolve("cleaned-mask.png").toString(),
                directory.resolve("detection-result.png").toString(),
                directory.resolve("final-decision.png").toString()
        );
    }

    private IncidentRecord incidentRecord() {
        return new IncidentRecord(
                "20260501T000000Z-abc",
                "owner",
                Instant.parse("2026-05-01T00:00:00Z"),
                DecisionType.UNKNOWN_PERSON,
                1,
                "Detected faces stayed outside the owner threshold",
                List.of("GENERAL_DESKTOP"),
                new ScreenContextEvidence("", "", "", List.of("GENERAL_DESKTOP")),
                null,
                "/tmp/incident",
                null,
                null,
                null,
                new EmailDelivery(true, true, "sent")
        );
    }
}
