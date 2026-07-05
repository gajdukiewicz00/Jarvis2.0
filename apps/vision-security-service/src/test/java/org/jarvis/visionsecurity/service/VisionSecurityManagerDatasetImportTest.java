package org.jarvis.visionsecurity.service;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.EnrollmentResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.opencv.core.Mat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers {@link VisionSecurityManager#importEnrollmentFromDataset(String, Path)} branches. */
class VisionSecurityManagerDatasetImportTest {

    private final VisionPipelineService visionPipelineService = mock(VisionPipelineService.class);
    private final FaceVerificationService faceVerificationService = mock(FaceVerificationService.class);
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
                mock(CameraCaptureService.class),
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
    void rejectsMissingDatasetDirectory(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist");

        assertThatThrownBy(() -> manager.importEnrollmentFromDataset("owner", missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dataset directory does not exist");
    }

    @Test
    void rejectsDirectoryWithNoImageFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "not an image");

        assertThatThrownBy(() -> manager.importEnrollmentFromDataset("owner", tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No image files found in");
    }

    @Test
    void skipsUnreadableImageAsRejectionThenFailsBelowMinimum(@TempDir Path tempDir) throws Exception {
        Files.write(tempDir.resolve("broken.png"), "not really a png".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> manager.importEnrollmentFromDataset("owner", tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dataset import failed")
                .hasMessageContaining("[unreadable image]=1");
    }

    @Test
    void rejectsSampleWhenExtractEnrollmentFaceReportsNoFace(@TempDir Path tempDir) throws Exception {
        Path image = writePng(tempDir.resolve("no-face.png"));
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class)))
                .thenThrow(new IllegalStateException("No face detected"));

        assertThatThrownBy(() -> manager.importEnrollmentFromDataset("owner", tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dataset import failed")
                .hasMessageContaining("[No face detected]=1");
    }

    @Test
    void rejectsExtractedSampleThatFailsQualityAssessmentThenAcceptsRemaining(@TempDir Path tempDir) throws Exception {
        for (int index = 0; index < 6; index++) {
            writePng(tempDir.resolve("owner-" + index + ".png"));
        }
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class))).thenAnswer(inv -> new Mat());
        when(visionPipelineService.measureFaceSharpness(any(Mat.class)))
                .thenReturn(5.0, 100.0, 100.0, 100.0, 100.0, 100.0);
        when(visionPipelineService.measureFaceContrast(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.computeDifferenceHash(any(Mat.class)))
                .thenReturn("h1", "h2", "h3", "h4", "h5");
        when(visionPipelineService.hammingDistance(anyString(), anyString())).thenReturn(999);
        EnrollmentResult expected = new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/tmp/owner");
        when(faceVerificationService.storeEnrollment(eq("owner"), anyList())).thenReturn(expected);

        EnrollmentResult result = manager.importEnrollmentFromDataset("owner", tempDir);

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<List<Mat>> captor = ArgumentCaptor.forClass(List.class);
        verify(faceVerificationService).storeEnrollment(eq("owner"), captor.capture());
        assertThat(captor.getValue()).hasSize(5);
    }

    @Test
    void importsAllQualifyingImagesWhenAtOrAboveMinimum(@TempDir Path tempDir) throws Exception {
        for (int index = 0; index < 5; index++) {
            writePng(tempDir.resolve("owner-" + index + ".png"));
        }
        when(visionPipelineService.extractEnrollmentFace(any(Mat.class))).thenAnswer(inv -> new Mat());
        when(visionPipelineService.measureFaceSharpness(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.measureFaceContrast(any(Mat.class))).thenReturn(100.0);
        when(visionPipelineService.computeDifferenceHash(any(Mat.class)))
                .thenReturn("h1", "h2", "h3", "h4", "h5");
        when(visionPipelineService.hammingDistance(anyString(), anyString())).thenReturn(999);
        EnrollmentResult expected = new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/tmp/owner");
        when(faceVerificationService.storeEnrollment(eq("owner"), anyList())).thenReturn(expected);

        EnrollmentResult result = manager.importEnrollmentFromDataset("owner", tempDir);

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<List<Mat>> captor = ArgumentCaptor.forClass(List.class);
        verify(faceVerificationService).storeEnrollment(eq("owner"), captor.capture());
        assertThat(captor.getValue()).hasSize(5);
    }

    private static Path writePng(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", target.toFile());
        return target;
    }
}
