package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.vision.config.VisionServiceProperties;
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
class OwnerReferenceEmbeddingCacheTest {

    @Mock
    private FaceDetectionProvider faceDetectionProvider;

    @TempDir
    Path tempDir;

    @Test
    void cachesReferenceEmbeddingsUntilReferenceSetChanges() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setOwnerReferenceDirectory(tempDir);

        BufferedImage firstReference = solidImage(Color.ORANGE);
        ImageIO.write(firstReference, "png", tempDir.resolve("owner-1.png").toFile());

        var detection = new FaceDetectionProvider.DetectionResult(
                true,
                "detector",
                "detected",
                List.of(new VisionFaceRegion(0, 0, firstReference.getWidth(), firstReference.getHeight())));
        when(faceDetectionProvider.detectFaces(any())).thenReturn(detection);
        when(faceDetectionProvider.cacheKey()).thenReturn("opencv-haarcascade");

        AtomicInteger encodeCalls = new AtomicInteger();
        FaceEmbeddingEncoder countingEncoder = new FaceEmbeddingEncoder() {
            @Override
            public String encoderId() {
                return "counting-test";
            }

            @Override
            public double[] encode(BufferedImage faceImage) {
                encodeCalls.incrementAndGet();
                int rgb = faceImage.getRGB(0, 0);
                return new double[]{(rgb >> 16) & 0xFF, rgb & 0xFF};
            }
        };

        OwnerReferenceEmbeddingCache cache = new OwnerReferenceEmbeddingCache(new OwnerReferenceFaceLoader(properties));
        FaceAlignmentService faceAlignmentService = new FaceAlignmentService() {
            @Override
            public String providerId() {
                return "test-aligner";
            }

            @Override
            public FaceAlignmentResult align(BufferedImage faceImage) {
                return new FaceAlignmentResult(
                        true,
                        true,
                        providerId(),
                        "square",
                        "aligned",
                        OpenCvImageUtils.centerCropSquare(faceImage),
                        Map.of("alignmentApplied", "true"));
            }
        };

        var firstSnapshot = cache.getReferenceEmbeddings(faceDetectionProvider, faceAlignmentService, countingEncoder);
        var secondSnapshot = cache.getReferenceEmbeddings(faceDetectionProvider, faceAlignmentService, countingEncoder);

        assertThat(firstSnapshot.loaded()).isTrue();
        assertThat(firstSnapshot.cacheHit()).isFalse();
        assertThat(firstSnapshot.embeddings()).hasSize(1);
        assertThat(secondSnapshot.cacheHit()).isTrue();
        assertThat(encodeCalls).hasValue(1);
        assertThat(firstSnapshot.diagnostics()).containsEntry("referenceEmbeddingAlignmentProvider", "test-aligner");

        BufferedImage secondReference = solidImage(Color.BLUE);
        ImageIO.write(secondReference, "png", tempDir.resolve("owner-2.png").toFile());

        var staleStatus = cache.status(faceDetectionProvider, faceAlignmentService, countingEncoder);
        var rebuiltSnapshot = cache.prewarm(faceDetectionProvider, faceAlignmentService, countingEncoder);

        assertThat(staleStatus.stale()).isTrue();
        assertThat(rebuiltSnapshot.cacheHit()).isFalse();
        assertThat(rebuiltSnapshot.embeddings()).hasSize(2);
        assertThat(encodeCalls).hasValue(3);
        assertThat(rebuiltSnapshot.lastPrewarmedAt()).isNotNull();
        assertThat(rebuiltSnapshot.diagnostics()).containsEntry(
                "referenceEmbeddingInvalidationPolicy",
                "invalidate-on-enroll-or-reference-fingerprint-change");
    }

    private static BufferedImage solidImage(Color color) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, 32, 32);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
