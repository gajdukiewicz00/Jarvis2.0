package org.jarvis.visionsecurity.service;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Covers the pure OpenCV-math helper methods on {@link VisionPipelineService}
 * (sharpness/contrast/hash/brightness/hamming-distance and the
 * {@code extractEnrollmentFace} face-count guards) that aren't exercised by
 * {@link VisionPipelineAggregationTest} or {@link VisionPipelineBrightnessGateTest}.
 * Uses a real {@link OpenCvRuntime} with synthetic in-memory frames — no
 * camera or dataset files needed.
 */
class VisionPipelineMathMethodsTest {

    private VisionPipelineService pipelineService;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        pipelineService = new VisionPipelineService(
                properties,
                new OpenCvRuntime(),
                mock(FaceVerificationService.class)
        );
    }

    @Test
    void analyzeRejectsNullFrame() throws Exception {
        assertThatThrownBy(() -> pipelineService.analyze("owner", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Frame is empty");
    }

    @Test
    void analyzeRejectsEmptyFrame() throws Exception {
        Mat empty = new Mat();
        try {
            assertThatThrownBy(() -> pipelineService.analyze("owner", empty, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Frame is empty");
        } finally {
            empty.release();
        }
    }

    @Test
    void extractEnrollmentFaceThrowsWhenNoFaceDetected() {
        Mat blank = new Mat(200, 200, CvType.CV_8UC3, new Scalar(120, 120, 120));
        try {
            assertThatThrownBy(() -> pipelineService.extractEnrollmentFace(blank))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No face detected");
        } finally {
            blank.release();
        }
    }

    @Test
    void measureFaceSharpnessReturnsHigherVarianceForNoisyImageThanFlatImage() {
        Mat flat = new Mat(160, 160, CvType.CV_8UC1, new Scalar(128));
        Mat noisy = new Mat(160, 160, CvType.CV_8UC1);
        try {
            org.opencv.core.Core.randu(noisy, 0, 255);

            double flatSharpness = pipelineService.measureFaceSharpness(flat);
            double noisySharpness = pipelineService.measureFaceSharpness(noisy);

            assertThat(flatSharpness).isZero();
            assertThat(noisySharpness).isGreaterThan(flatSharpness);
        } finally {
            flat.release();
            noisy.release();
        }
    }

    @Test
    void measureFaceContrastReturnsZeroForFlatImage() {
        Mat flat = new Mat(160, 160, CvType.CV_8UC1, new Scalar(100));
        try {
            assertThat(pipelineService.measureFaceContrast(flat)).isZero();
        } finally {
            flat.release();
        }
    }

    @Test
    void measureFaceContrastReturnsPositiveForImageWithVariation() {
        Mat half = new Mat(160, 160, CvType.CV_8UC1, new Scalar(0));
        try {
            half.rowRange(0, 80).setTo(new Scalar(255));
            assertThat(pipelineService.measureFaceContrast(half)).isGreaterThan(0.0);
        } finally {
            half.release();
        }
    }

    @Test
    void measureMeanBrightnessHandlesSingleChannelInputDirectly() {
        Mat gray = new Mat(100, 100, CvType.CV_8UC1, new Scalar(77));
        try {
            assertThat(pipelineService.measureMeanBrightness(gray)).isCloseTo(77.0, org.assertj.core.data.Offset.offset(0.5));
        } finally {
            gray.release();
        }
    }

    @Test
    void computeDifferenceHashProducesSixtyFourBitFingerprint() {
        Mat face = new Mat(160, 160, CvType.CV_8UC1, new Scalar(0));
        try {
            face.colRange(80, 160).setTo(new Scalar(255));

            String hash = pipelineService.computeDifferenceHash(face);

            assertThat(hash).hasSize(64);
            assertThat(hash).matches("[01]+");
        } finally {
            face.release();
        }
    }

    @Test
    void computeDifferenceHashIsIdenticalForIdenticalImages() {
        Mat first = new Mat(160, 160, CvType.CV_8UC1, new Scalar(0));
        Mat second = new Mat(160, 160, CvType.CV_8UC1, new Scalar(0));
        try {
            first.colRange(80, 160).setTo(new Scalar(255));
            second.colRange(80, 160).setTo(new Scalar(255));

            String hashA = pipelineService.computeDifferenceHash(first);
            String hashB = pipelineService.computeDifferenceHash(second);

            assertThat(pipelineService.hammingDistance(hashA, hashB)).isZero();
        } finally {
            first.release();
            second.release();
        }
    }

    @Test
    void hammingDistanceCountsMismatchedBits() {
        assertThat(pipelineService.hammingDistance("1100", "1010")).isEqualTo(2);
        assertThat(pipelineService.hammingDistance("0000", "0000")).isZero();
    }

    @Test
    void hammingDistanceReturnsMaxValueWhenHashesDifferInLength() {
        assertThat(pipelineService.hammingDistance("101", "10")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void hammingDistanceReturnsMaxValueWhenEitherHashIsNull() {
        assertThat(pipelineService.hammingDistance(null, "101")).isEqualTo(Integer.MAX_VALUE);
        assertThat(pipelineService.hammingDistance("101", null)).isEqualTo(Integer.MAX_VALUE);
    }
}
