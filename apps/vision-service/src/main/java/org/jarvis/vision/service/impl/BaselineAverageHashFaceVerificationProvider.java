package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.FaceVerificationProvider;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BaselineAverageHashFaceVerificationProvider implements FaceVerificationProvider {

    private static final String PROVIDER = "average-hash-baseline";
    private static final int HASH_SIZE = 8;

    private final VisionServiceProperties properties;
    private final OwnerReferenceFaceLoader ownerReferenceFaceLoader;

    @Override
    public String providerId() {
        return PROVIDER;
    }

    @Override
    public Map<String, String> statusDetails(FaceDetectionProvider faceDetectionProvider) {
        return Map.of(
                "verificationMode", "average-hash-baseline",
                "embeddingBackendConfigured", "none",
                "referenceEmbeddingCacheLoaded", "false",
                "referenceEmbeddingCacheState", "not-applicable");
    }

    @Override
    public VisionVerifyOwnerResponse verifyOwner(BufferedImage image,
                                                 FaceDetectionProvider faceDetectionProvider,
                                                 FaceDetectionProvider.DetectionResult detectionResult) {
        if (!detectionResult.operational()) {
            return new VisionVerifyOwnerResponse(
                    VisionVerificationOutcome.UNAVAILABLE,
                    false,
                    PROVIDER,
                    detectionResult.message(),
                    null,
                    0,
                    detectionResult.faces(),
                    Map.of("baselineVerifier", "average-hash"));
        }

        if (detectionResult.faces().isEmpty()) {
            return new VisionVerifyOwnerResponse(
                    VisionVerificationOutcome.NO_FACE,
                    true,
                    PROVIDER,
                    "No face available for verification",
                    null,
                    0,
                    List.of(),
                    Map.of("baselineVerifier", "average-hash"));
        }

        List<Long> referenceHashes = ownerReferenceFaceLoader.loadReferenceFaces(faceDetectionProvider).stream()
                .map(OwnerReferenceFaceLoader.ReferenceFace::faceImage)
                .map(BaselineAverageHashFaceVerificationProvider::averageHash)
                .toList();
        if (referenceHashes.isEmpty()) {
            return new VisionVerifyOwnerResponse(
                    VisionVerificationOutcome.UNAVAILABLE,
                    false,
                    PROVIDER,
                    "No owner reference images configured",
                    null,
                    0,
                    detectionResult.faces(),
                    Map.of("baselineVerifier", "average-hash"));
        }

        VisionFaceRegion primaryFace = detectionResult.faces().getFirst();
        BufferedImage liveFace = OpenCvImageUtils.crop(
                image,
                primaryFace.x(),
                primaryFace.y(),
                primaryFace.width(),
                primaryFace.height());
        long liveHash = averageHash(liveFace);

        double bestSimilarity = referenceHashes.stream()
                .mapToDouble(referenceHash -> similarity(referenceHash, liveHash))
                .max()
                .orElse(0.0d);

        VisionVerificationOutcome outcome = bestSimilarity >= properties.getSimilarityThreshold()
                ? VisionVerificationOutcome.OWNER
                : VisionVerificationOutcome.UNKNOWN;

        return new VisionVerifyOwnerResponse(
                outcome,
                true,
                PROVIDER,
                outcome == VisionVerificationOutcome.OWNER
                        ? "Owner matched baseline reference profile"
                        : "Face matched below configured similarity threshold",
                bestSimilarity,
                referenceHashes.size(),
                detectionResult.faces(),
                Map.of("baselineVerifier", "average-hash"));
    }

    private static long averageHash(BufferedImage image) {
        BufferedImage grayscale = new BufferedImage(HASH_SIZE, HASH_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = grayscale.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, HASH_SIZE, HASH_SIZE, null);
        } finally {
            graphics.dispose();
        }

        int[] pixels = grayscale.getRaster().getPixels(0, 0, HASH_SIZE, HASH_SIZE, (int[]) null);
        double average = java.util.Arrays.stream(pixels).average().orElse(0.0d);
        long hash = 0L;
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] >= average) {
                hash |= (1L << i);
            }
        }
        return hash;
    }

    private static double similarity(long first, long second) {
        return 1.0d - (Long.bitCount(first ^ second) / 64.0d);
    }
}
