package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.impl.BlockGradientFaceEmbeddingEncoder;
import org.jarvis.vision.service.impl.EmbeddingCosineFaceVerificationProvider;
import org.jarvis.vision.service.impl.HeuristicFaceAlignmentService;
import org.jarvis.vision.service.impl.OwnerReferenceFaceLoader;
import org.jarvis.vision.service.impl.OwnerReferenceEmbeddingCache;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingCosineFaceVerificationProviderTest {

    @Mock
    private FaceDetectionProvider faceDetectionProvider;

    @TempDir
    Path tempDir;

    @Test
    void mapsEmbeddingSimilarityToOwnerAndUnknownOutcomes() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setOwnerReferenceDirectory(tempDir);
        properties.setSimilarityThreshold(0.92d);
        properties.getEmbedding().setFaceImageSize(32);
        properties.getEmbedding().setPoolingGridSize(4);

        BufferedImage referenceImage = stripedFaceImage(Color.ORANGE, Color.BLACK);
        BufferedImage unknownImage = stripedFaceImage(Color.BLUE, Color.WHITE);
        ImageIO.write(referenceImage, "png", tempDir.resolve("owner.png").toFile());

        var detection = new FaceDetectionProvider.DetectionResult(
                true,
                "detector",
                "detected",
                List.of(new VisionFaceRegion(0, 0, referenceImage.getWidth(), referenceImage.getHeight())));
        when(faceDetectionProvider.detectFaces(any())).thenReturn(detection);

        EmbeddingCosineFaceVerificationProvider provider = new EmbeddingCosineFaceVerificationProvider(
                properties,
                new BlockGradientFaceEmbeddingEncoder(properties),
                new HeuristicFaceAlignmentService(properties),
                new OwnerReferenceEmbeddingCache(new OwnerReferenceFaceLoader(properties)));

        var ownerResponse = provider.verifyOwner(referenceImage, faceDetectionProvider, detection);
        var unknownResponse = provider.verifyOwner(unknownImage, faceDetectionProvider, detection);

        assertThat(ownerResponse.outcome()).isEqualTo(VisionVerificationOutcome.OWNER);
        assertThat(ownerResponse.similarity()).isGreaterThanOrEqualTo(0.92d);
        assertThat(ownerResponse.diagnostics()).containsEntry("embeddingVerifier", "cosine");
        assertThat(ownerResponse.diagnostics()).containsEntry("embeddingBackendConfigured", "block-gradient-mvp");
        assertThat(ownerResponse.diagnostics()).containsEntry("referenceEmbeddingCacheLoaded", "true");
        assertThat(ownerResponse.diagnostics()).containsKey("alignmentMode");

        assertThat(unknownResponse.outcome()).isEqualTo(VisionVerificationOutcome.UNKNOWN);
        assertThat(unknownResponse.similarity()).isLessThan(0.92d);
        assertThat(unknownResponse.diagnostics()).containsEntry("referenceEmbeddingCacheHit", "true");
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
