package org.jarvis.vision.service.impl;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceAlignmentResult;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.FaceEmbeddingEncoder;
import org.jarvis.vision.service.FaceVerificationProvider;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class AbstractEmbeddingCosineFaceVerificationProvider implements FaceVerificationProvider {

    private final String providerId;
    private final String implementationType;
    private final VisionServiceProperties properties;
    private final FaceEmbeddingEncoder faceEmbeddingEncoder;
    private final FaceAlignmentService faceAlignmentService;
    private final OwnerReferenceEmbeddingCache ownerReferenceEmbeddingCache;

    protected AbstractEmbeddingCosineFaceVerificationProvider(String providerId,
                                                              String implementationType,
                                                              VisionServiceProperties properties,
                                                              FaceEmbeddingEncoder faceEmbeddingEncoder,
                                                              FaceAlignmentService faceAlignmentService,
                                                              OwnerReferenceEmbeddingCache ownerReferenceEmbeddingCache) {
        this.providerId = providerId;
        this.implementationType = implementationType;
        this.properties = properties;
        this.faceEmbeddingEncoder = faceEmbeddingEncoder;
        this.faceAlignmentService = faceAlignmentService;
        this.ownerReferenceEmbeddingCache = ownerReferenceEmbeddingCache;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public boolean isAvailable() {
        return faceEmbeddingEncoder.isAvailable();
    }

    @Override
    public String availabilityMessage() {
        return faceEmbeddingEncoder.availabilityMessage();
    }

    @Override
    public Map<String, String> statusDetails(FaceDetectionProvider faceDetectionProvider) {
        Map<String, String> details = new LinkedHashMap<>(faceEmbeddingEncoder.statusDetails());
        details.put("embeddingVerifier", "cosine");
        details.put("implementationType", implementationType);
        details.put("effectiveSimilarityThreshold", format(effectiveThreshold()));
        details.putAll(faceAlignmentService.statusDetails());
        details.putAll(ownerReferenceEmbeddingCache.status(
                faceDetectionProvider,
                faceAlignmentService,
                faceEmbeddingEncoder).diagnostics());
        return details;
    }

    @Override
    public VisionVerifyOwnerResponse verifyOwner(BufferedImage image,
                                                 FaceDetectionProvider faceDetectionProvider,
                                                 FaceDetectionProvider.DetectionResult detectionResult) {
        if (!detectionResult.operational()) {
            return response(
                    VisionVerificationOutcome.UNAVAILABLE,
                    false,
                    detectionResult.message(),
                    null,
                    0,
                    detectionResult.faces(),
                    Map.of("detectionUnavailable", "true"));
        }

        if (detectionResult.faces().isEmpty()) {
            return response(
                    VisionVerificationOutcome.NO_FACE,
                    true,
                    "No face available for embedding verification",
                    null,
                    0,
                    List.of(),
                    Map.of());
        }

        if (!faceEmbeddingEncoder.isAvailable()) {
            return response(
                    VisionVerificationOutcome.UNAVAILABLE,
                    false,
                    faceEmbeddingEncoder.availabilityMessage(),
                    null,
                    0,
                    detectionResult.faces(),
                    Map.of());
        }

        OwnerReferenceEmbeddingCache.ReferenceEmbeddingSnapshot referenceSnapshot =
                ownerReferenceEmbeddingCache.getReferenceEmbeddings(
                        faceDetectionProvider,
                        faceAlignmentService,
                        faceEmbeddingEncoder);
        if (referenceSnapshot.embeddings().isEmpty()) {
            return response(
                    VisionVerificationOutcome.UNAVAILABLE,
                    false,
                    referenceSnapshot.message().isBlank()
                            ? "No owner reference embeddings configured"
                            : referenceSnapshot.message(),
                    null,
                    0,
                    detectionResult.faces(),
                    referenceSnapshot.diagnostics());
        }

        VisionFaceRegion primaryFace = detectionResult.faces().getFirst();
        BufferedImage liveFace = OpenCvImageUtils.crop(
                image,
                primaryFace.x(),
                primaryFace.y(),
                primaryFace.width(),
                primaryFace.height());
        FaceAlignmentResult alignmentResult = faceAlignmentService.align(liveFace);
        double[] liveEmbedding = faceEmbeddingEncoder.encode(alignmentResult.faceImage());
        double bestSimilarity = referenceSnapshot.embeddings().stream()
                .map(OwnerReferenceEmbeddingCache.ReferenceEmbedding::embedding)
                .mapToDouble(referenceEmbedding -> cosineSimilarity(liveEmbedding, referenceEmbedding))
                .max()
                .orElse(0.0d);

        double threshold = effectiveThreshold();
        VisionVerificationOutcome outcome = bestSimilarity >= threshold
                ? VisionVerificationOutcome.OWNER
                : VisionVerificationOutcome.UNKNOWN;

        Map<String, String> diagnostics = new LinkedHashMap<>(referenceSnapshot.diagnostics());
        diagnostics.putAll(alignmentResult.diagnostics());
        diagnostics.put("effectiveSimilarityThreshold", format(threshold));
        diagnostics.put("similarityThresholdSource", thresholdSource());

        return response(
                outcome,
                true,
                outcome == VisionVerificationOutcome.OWNER
                        ? "Owner matched embedding reference profile"
                        : "Embedding similarity below configured threshold",
                bestSimilarity,
                referenceSnapshot.embeddings().size(),
                detectionResult.faces(),
                diagnostics);
    }

    protected FaceEmbeddingEncoder faceEmbeddingEncoder() {
        return faceEmbeddingEncoder;
    }

    private double effectiveThreshold() {
        if ("embedding-cosine-model".equals(providerId)
                && properties.getEmbedding().getModel().getSimilarityThreshold() != null) {
            return properties.getEmbedding().getModel().getSimilarityThreshold();
        }
        return properties.getSimilarityThreshold();
    }

    private String thresholdSource() {
        return "embedding-cosine-model".equals(providerId)
                && properties.getEmbedding().getModel().getSimilarityThreshold() != null
                ? "vision.embedding.model.similarity-threshold"
                : "vision.similarity-threshold";
    }

    private VisionVerifyOwnerResponse response(VisionVerificationOutcome outcome,
                                               boolean operational,
                                               String message,
                                               Double similarity,
                                               int referenceCount,
                                               List<VisionFaceRegion> faces,
                                               Map<String, String> diagnostics) {
        Map<String, String> mergedDiagnostics = new LinkedHashMap<>(faceEmbeddingEncoder.statusDetails());
        mergedDiagnostics.put("embeddingVerifier", "cosine");
        mergedDiagnostics.put("similarityMetric", "cosine");
        mergedDiagnostics.put("implementationType", implementationType);
        mergedDiagnostics.putAll(faceAlignmentService.statusDetails());
        mergedDiagnostics.putAll(diagnostics);

        return new VisionVerifyOwnerResponse(
                outcome,
                operational,
                providerId,
                message,
                similarity,
                referenceCount,
                faces,
                mergedDiagnostics);
    }

    private static double cosineSimilarity(double[] first, double[] second) {
        if (first.length != second.length || first.length == 0) {
            return 0.0d;
        }

        double dot = 0.0d;
        double firstNorm = 0.0d;
        double secondNorm = 0.0d;
        for (int i = 0; i < first.length; i++) {
            dot += first[i] * second[i];
            firstNorm += first[i] * first[i];
            secondNorm += second[i] * second[i];
        }
        if (firstNorm == 0.0d || secondNorm == 0.0d) {
            return 0.0d;
        }

        double cosine = dot / (Math.sqrt(firstNorm) * Math.sqrt(secondNorm));
        return Math.max(0.0d, Math.min(1.0d, (cosine + 1.0d) / 2.0d));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
