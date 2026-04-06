package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.impl.ModelBackedEmbeddingCosineFaceVerificationProvider;
import org.jarvis.vision.service.impl.OwnerReferenceEmbeddingCache;
import org.jarvis.vision.service.impl.OwnerReferenceFaceLoader;
import org.jarvis.vision.service.impl.OpenCvImageUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelBackedEmbeddingCosineFaceVerificationProviderTest {

    @Mock
    private FaceDetectionProvider faceDetectionProvider;

    @TempDir
    Path tempDir;

    @Test
    void mapsModelBackedEmbeddingSimilarityToOwnerAndUnknownOutcomes() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setOwnerReferenceDirectory(tempDir);
        properties.setSimilarityThreshold(0.80d);
        properties.getEmbedding().getModel().setSimilarityThreshold(0.90d);

        BufferedImage referenceImage = stripedFaceImage(Color.ORANGE, Color.BLACK);
        BufferedImage unknownImage = stripedFaceImage(Color.BLUE, Color.WHITE);
        ImageIO.write(referenceImage, "png", tempDir.resolve("owner-model.png").toFile());

        var detection = new FaceDetectionProvider.DetectionResult(
                true,
                "detector",
                "detected",
                List.of(new VisionFaceRegion(0, 0, referenceImage.getWidth(), referenceImage.getHeight())));
        when(faceDetectionProvider.detectFaces(any())).thenReturn(detection);
        when(faceDetectionProvider.cacheKey()).thenReturn("opencv-haarcascade");

        FaceEmbeddingEncoder modelBackedEncoder = new FaceEmbeddingEncoder() {
            @Override
            public String encoderId() {
                return "opencv-dnn-onnx";
            }

            @Override
            public double[] encode(BufferedImage faceImage) {
                int rgb = faceImage.getRGB(0, 0);
                int red = (rgb >> 16) & 0xFF;
                int blue = rgb & 0xFF;
                return red >= blue ? new double[]{1.0d, 0.0d} : new double[]{0.0d, 1.0d};
            }
        };

        AtomicInteger alignmentCalls = new AtomicInteger();
        FaceAlignmentService faceAlignmentService = new FaceAlignmentService() {
            @Override
            public String providerId() {
                return "test-aligner";
            }

            @Override
            public FaceAlignmentResult align(BufferedImage faceImage) {
                alignmentCalls.incrementAndGet();
                return new FaceAlignmentResult(
                        true,
                        true,
                        providerId(),
                        "test-square",
                        "aligned",
                        OpenCvImageUtils.centerCropSquare(faceImage),
                        Map.of(
                                "alignmentApplied", "true",
                                "alignmentMode", "test-square"));
            }
        };

        ModelBackedEmbeddingCosineFaceVerificationProvider provider =
                new ModelBackedEmbeddingCosineFaceVerificationProvider(
                        properties,
                        modelBackedEncoder,
                        faceAlignmentService,
                        new OwnerReferenceEmbeddingCache(new OwnerReferenceFaceLoader(properties)));

        var ownerResponse = provider.verifyOwner(referenceImage, faceDetectionProvider, detection);
        var unknownResponse = provider.verifyOwner(unknownImage, faceDetectionProvider, detection);

        assertThat(ownerResponse.outcome()).isEqualTo(VisionVerificationOutcome.OWNER);
        assertThat(ownerResponse.provider()).isEqualTo("embedding-cosine-model");
        assertThat(ownerResponse.similarity()).isGreaterThanOrEqualTo(0.99d);
        assertThat(ownerResponse.diagnostics()).containsEntry("implementationType", "model-backed-embedding");
        assertThat(ownerResponse.diagnostics()).containsEntry("embeddingBackendConfigured", "opencv-dnn-onnx");
        assertThat(ownerResponse.diagnostics()).containsEntry("alignmentApplied", "true");
        assertThat(ownerResponse.diagnostics()).containsEntry("effectiveSimilarityThreshold", "0.9000");

        assertThat(unknownResponse.outcome()).isEqualTo(VisionVerificationOutcome.UNKNOWN);
        assertThat(unknownResponse.similarity()).isEqualTo(0.5d);
        assertThat(alignmentCalls).hasValue(3);
    }

    private static BufferedImage stripedFaceImage(Color primary, Color secondary) {
        BufferedImage image = new BufferedImage(48, 48, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(primary);
            graphics.fillRect(0, 0, 48, 48);
            graphics.setColor(secondary);
            graphics.fillRect(8, 8, 10, 32);
            graphics.fillRect(30, 8, 10, 32);
            graphics.setColor(primary.darker());
            graphics.fillRect(12, 22, 24, 6);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
