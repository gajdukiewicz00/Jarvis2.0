package org.jarvis.visionsecurity.service;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VisionPipelineBrightnessGateTest {

    private FaceVerificationService faceVerificationService;
    private VisionPipelineService pipelineService;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        faceVerificationService = mock(FaceVerificationService.class);
        pipelineService = new VisionPipelineService(
                properties,
                new OpenCvRuntime(),
                faceVerificationService
        );
    }

    @Test
    void monitoringPathReturnsEarlyForFullyBlackFrame() throws Exception {
        Mat black = new Mat(480, 640, CvType.CV_8UC3, new Scalar(0, 0, 0));
        try {
            PipelineResult result = pipelineService.analyze("owner", black, null);

            assertThat(result.decision()).isEqualTo(DecisionType.NO_FACE);
            assertThat(result.faceCount()).isZero();
            assertThat(result.reason()).startsWith("Frame too dark for analysis");
            assertThat(result.stagePaths()).isNull();
            assertThat(result.rawFramePath()).isNull();
            verifyNoInteractions(faceVerificationService);
        } finally {
            black.release();
        }
    }

    @Test
    void monitoringPathProcessesBrightFrameNormally() throws Exception {
        Mat bright = new Mat(480, 640, CvType.CV_8UC3, new Scalar(200, 200, 200));
        try {
            PipelineResult result = pipelineService.analyze("owner", bright, null);

            assertThat(result.reason()).doesNotStartWith("Frame too dark");
            verify(faceVerificationService).isEnrolled("owner");
        } finally {
            bright.release();
        }
    }

    @Test
    void snapshotPathSkipsBrightnessGateAndAlwaysRunsPipeline(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        Mat black = new Mat(480, 640, CvType.CV_8UC3, new Scalar(0, 0, 0));
        try {
            PipelineResult result = pipelineService.analyze("owner", black, tempDir);

            assertThat(result.reason()).doesNotStartWith("Frame too dark");
            verify(faceVerificationService).isEnrolled("owner");
        } finally {
            black.release();
        }
    }
}
