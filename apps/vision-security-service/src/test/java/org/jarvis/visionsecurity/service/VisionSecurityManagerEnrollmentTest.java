package org.jarvis.visionsecurity.service;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.EnrollmentResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencv.core.Mat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers {@link VisionSecurityManager#captureEnrollment(String, Integer)} branches. */
class VisionSecurityManagerEnrollmentTest {

    private final CameraCaptureService cameraCaptureService = mock(CameraCaptureService.class);
    private final VisionPipelineService visionPipelineService = mock(VisionPipelineService.class);
    private final FaceVerificationService faceVerificationService = mock(FaceVerificationService.class);

    private VisionSecurityProperties properties;
    private VisionSecurityManager manager;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @BeforeEach
    void setUp() {
        properties = new VisionSecurityProperties();
        properties.getEnrollment().setSampleSpacingMs(1L);
        properties.getEnrollment().setCaptureTimeoutSeconds(5L);

        manager = new VisionSecurityManager(
                properties,
                cameraCaptureService,
                visionPipelineService,
                faceVerificationService,
                mock(IncidentStore.class),
                mock(ScreenshotService.class),
                mock(ScreenContextService.class),
                mock(EmailAlertService.class),
                mock(GpuStatusService.class),
                mock(OcrService.class)
        );
    }

    @Test
    void rejectsRequestBelowMinimumSampleCount() {
        assertThatThrownBy(() -> manager.captureEnrollment("owner", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Enrollment needs at least 5 samples");
    }

    @Test
    void rejectsWhenCameraAlreadyReservedByAnotherExclusiveOperation() throws Exception {
        exclusiveOperation().set("pipeline snapshot");

        assertThatThrownBy(() -> manager.captureEnrollment("owner", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Vision security is already using the camera for pipeline snapshot");
    }

    @Test
    void succeedsAndStoresAllAcceptedSamplesWhenEverySampleQualifies() throws Exception {
        stubCameraSessionReturningFreshFrames();
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class))).thenAnswer(inv -> new Mat());
        when(visionPipelineService.measureFaceSharpness(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.measureFaceContrast(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.computeDifferenceHash(any(Mat.class))).thenReturn("h1", "h2", "h3", "h4", "h5");
        when(visionPipelineService.hammingDistance(anyString(), anyString())).thenReturn(999);
        EnrollmentResult expected = new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/tmp/owner");
        when(faceVerificationService.storeEnrollment(eq("owner"), anyList())).thenReturn(expected);

        EnrollmentResult result = manager.captureEnrollment("owner", 5);

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<List<Mat>> captor = ArgumentCaptor.forClass(List.class);
        verify(faceVerificationService).storeEnrollment(eq("owner"), captor.capture());
        assertThat(captor.getValue()).hasSize(5);
    }

    @Test
    void rejectsBlurrySampleThenAcceptsRemainingUntilTargetReached() throws Exception {
        stubCameraSessionReturningFreshFrames();
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class))).thenAnswer(inv -> new Mat());
        when(visionPipelineService.measureFaceSharpness(any(Mat.class)))
                .thenReturn(5.0, 100.0, 100.0, 100.0, 100.0, 100.0);
        when(visionPipelineService.measureFaceContrast(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.computeDifferenceHash(any(Mat.class))).thenReturn("h1", "h2", "h3", "h4", "h5");
        when(visionPipelineService.hammingDistance(anyString(), anyString())).thenReturn(999);
        when(faceVerificationService.storeEnrollment(eq("owner"), anyList()))
                .thenReturn(new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/tmp/owner"));

        manager.captureEnrollment("owner", 5);

        verify(visionPipelineService, org.mockito.Mockito.times(6)).measureFaceSharpness(any(Mat.class));
    }

    @Test
    void rejectsLowContrastSampleThenAcceptsRemainingUntilTargetReached() throws Exception {
        stubCameraSessionReturningFreshFrames();
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class))).thenAnswer(inv -> new Mat());
        when(visionPipelineService.measureFaceSharpness(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.measureFaceContrast(any(Mat.class)))
                .thenReturn(5.0, 100.0, 100.0, 100.0, 100.0, 100.0);
        when(visionPipelineService.computeDifferenceHash(any(Mat.class))).thenReturn("h1", "h2", "h3", "h4", "h5");
        when(visionPipelineService.hammingDistance(anyString(), anyString())).thenReturn(999);
        when(faceVerificationService.storeEnrollment(eq("owner"), anyList()))
                .thenReturn(new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/tmp/owner"));

        manager.captureEnrollment("owner", 5);

        verify(visionPipelineService, org.mockito.Mockito.times(6)).measureFaceContrast(any(Mat.class));
    }

    @Test
    void rejectsDuplicateHashSampleThenAcceptsRemainingUntilTargetReached() throws Exception {
        stubCameraSessionReturningFreshFrames();
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class))).thenAnswer(inv -> new Mat());
        when(visionPipelineService.measureFaceSharpness(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.measureFaceContrast(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.computeDifferenceHash(any(Mat.class)))
                .thenReturn("h1", "h1", "h2", "h3", "h4", "h5");
        when(visionPipelineService.hammingDistance(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0).equals(inv.getArgument(1)) ? 0 : 999);
        when(faceVerificationService.storeEnrollment(eq("owner"), anyList()))
                .thenReturn(new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/tmp/owner"));

        manager.captureEnrollment("owner", 5);

        verify(visionPipelineService, org.mockito.Mockito.times(6)).computeDifferenceHash(any(Mat.class));
    }

    @Test
    void countsIllegalStateRejectionFromExtractEnrollmentFaceAndContinues() throws Exception {
        stubCameraSessionReturningFreshFrames();
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class)))
                .thenThrow(new IllegalStateException("No face detected"))
                .thenAnswer(inv -> new Mat());
        when(visionPipelineService.measureFaceSharpness(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.measureFaceContrast(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.computeDifferenceHash(any(Mat.class))).thenReturn("h1", "h2", "h3", "h4", "h5");
        when(visionPipelineService.hammingDistance(anyString(), anyString())).thenReturn(999);
        when(faceVerificationService.storeEnrollment(eq("owner"), anyList()))
                .thenReturn(new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/tmp/owner"));

        manager.captureEnrollment("owner", 5);

        ArgumentCaptor<List<Mat>> captor = ArgumentCaptor.forClass(List.class);
        verify(faceVerificationService).storeEnrollment(eq("owner"), captor.capture());
        assertThat(captor.getValue()).hasSize(5);
    }

    @Test
    void failsWithDeadlineExceededMessageWhenTimeoutIsZero() throws Exception {
        properties.getEnrollment().setCaptureTimeoutSeconds(0L);
        when(cameraCaptureService.withCameraSession(eq("owner enrollment"), any())).thenAnswer(inv -> {
            CameraCaptureService.CameraSessionCallback<?> callback = inv.getArgument(1);
            return callback.execute(mock(CameraCaptureService.CameraSession.class));
        });

        assertThatThrownBy(() -> manager.captureEnrollment("owner", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Enrollment failed: 0/5 samples from 0 frames.");
    }

    private void stubCameraSessionReturningFreshFrames() throws Exception {
        CameraCaptureService.CameraSession session = mock(CameraCaptureService.CameraSession.class);
        when(session.captureFrame()).thenAnswer(inv -> new Mat());
        when(cameraCaptureService.withCameraSession(eq("owner enrollment"), any())).thenAnswer(inv -> {
            CameraCaptureService.CameraSessionCallback<?> callback = inv.getArgument(1);
            return callback.execute(session);
        });
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<String> exclusiveOperation() throws Exception {
        Field field = VisionSecurityManager.class.getDeclaredField("activeExclusiveOperation");
        field.setAccessible(true);
        return (AtomicReference<String>) field.get(manager);
    }
}
