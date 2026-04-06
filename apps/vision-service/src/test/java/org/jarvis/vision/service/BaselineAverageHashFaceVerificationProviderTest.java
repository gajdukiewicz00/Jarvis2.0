package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.impl.BaselineAverageHashFaceVerificationProvider;
import org.jarvis.vision.service.impl.OwnerReferenceFaceLoader;
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
class BaselineAverageHashFaceVerificationProviderTest {

    @Mock
    private FaceDetectionProvider faceDetectionProvider;

    @TempDir
    Path tempDir;

    @Test
    void verifiesOwnerAgainstReferenceDirectory() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setOwnerReferenceDirectory(tempDir);
        properties.setSimilarityThreshold(0.80d);

        BufferedImage referenceImage = solidImage();
        ImageIO.write(referenceImage, "png", tempDir.resolve("owner.png").toFile());

        var detection = new FaceDetectionProvider.DetectionResult(
                true,
                "detector",
                "detected",
                List.of(new VisionFaceRegion(0, 0, referenceImage.getWidth(), referenceImage.getHeight())));
        when(faceDetectionProvider.detectFaces(any())).thenReturn(detection);

        OwnerReferenceFaceLoader referenceFaceLoader = new OwnerReferenceFaceLoader(properties);
        BaselineAverageHashFaceVerificationProvider provider =
                new BaselineAverageHashFaceVerificationProvider(properties, referenceFaceLoader);

        VisionVerifyOwnerResponse response = provider.verifyOwner(referenceImage, faceDetectionProvider, detection);

        assertThat(response.outcome()).isEqualTo(VisionVerificationOutcome.OWNER);
        assertThat(response.similarity()).isGreaterThanOrEqualTo(0.80d);
        assertThat(response.referenceImageCount()).isEqualTo(1);
    }

    private static BufferedImage solidImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.ORANGE);
            graphics.fillRect(0, 0, 32, 32);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
