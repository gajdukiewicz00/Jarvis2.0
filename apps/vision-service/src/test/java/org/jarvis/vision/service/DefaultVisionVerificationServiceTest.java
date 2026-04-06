package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionImageArtifact;
import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionPipelineStage;
import org.jarvis.common.vision.VisionPipelineStageResult;
import org.jarvis.common.vision.VisionSecurityDecision;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerDebugResponse;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.pipeline.VisionImagePipelineService;
import org.jarvis.vision.pipeline.VisionPipelineArtifactImage;
import org.jarvis.vision.pipeline.VisionPipelineExecution;
import org.jarvis.vision.pipeline.VisionPipelineStageSnapshot;
import org.jarvis.vision.service.impl.DefaultSecurityIncidentScoringService;
import org.jarvis.vision.service.impl.DefaultVisionVerificationService;
import org.jarvis.vision.service.impl.HeuristicFaceAlignmentService;
import org.jarvis.vision.service.impl.HeuristicFaceLivenessAssessor;
import org.jarvis.vision.service.impl.IdentitySignalEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultVisionVerificationServiceTest {

    @Mock
    private FaceDetectionProvider faceDetectionProvider;
    @Mock
    private FaceVerificationProvider faceVerificationProvider;
    @Mock
    private FaceVerificationProvider fallbackVerificationProvider;
    @Mock
    private FaceVerificationProvider modelVerificationProvider;
    @Mock
    private VisionImagePipelineService visionImagePipelineService;

    @Test
    void returnsUnavailableWhenImagePayloadIsMissing() {
        VisionServiceProperties properties = new VisionServiceProperties();
        DefaultVisionVerificationService service =
                new DefaultVisionVerificationService(
                        List.of(faceDetectionProvider),
                        List.of(faceVerificationProvider),
                        properties,
                        visionImagePipelineService,
                        new HeuristicFaceAlignmentService(properties),
                        new HeuristicFaceLivenessAssessor(properties),
                        new IdentitySignalEvaluator(),
                        new DefaultSecurityIncidentScoringService());

        VisionVerifyOwnerResponse response = service.verifyOwner(
                new VisionVerifyOwnerRequest(new byte[0], "jpg", "test", "req-1", Map.of()));

        assertThat(response.outcome()).isEqualTo(VisionVerificationOutcome.UNAVAILABLE);
        assertThat(response.operational()).isFalse();
    }

    @Test
    void enrichesDiagnosticsWithRequestMetadata() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setVerifierProvider("average-hash-baseline");
        DefaultVisionVerificationService service =
                new DefaultVisionVerificationService(
                        List.of(faceDetectionProvider),
                        List.of(faceVerificationProvider),
                        properties,
                        visionImagePipelineService,
                        new HeuristicFaceAlignmentService(properties),
                        new HeuristicFaceLivenessAssessor(properties),
                        new IdentitySignalEvaluator(),
                        new DefaultSecurityIncidentScoringService());
        byte[] imageBytes = pngBytes();

        when(faceDetectionProvider.providerId()).thenReturn("opencv-haarcascade");
        when(faceVerificationProvider.providerId()).thenReturn("average-hash-baseline");
        when(faceVerificationProvider.isAvailable()).thenReturn(true);
        when(visionImagePipelineService.process(any(), any())).thenReturn(pipelineExecution());
        when(faceVerificationProvider.verifyOwner(any(), any(), any())).thenReturn(
                new VisionVerifyOwnerResponse(
                        VisionVerificationOutcome.NO_FACE,
                        true,
                        "baseline",
                        "No face",
                        null,
                        1,
                        List.of(),
                        Map.of("baselineVerifier", "average-hash")));

        VisionVerifyOwnerResponse response = service.verifyOwner(
                new VisionVerifyOwnerRequest(imageBytes, "png", "pc-control", "req-42", Map.of()));

        assertThat(response.diagnostics()).containsEntry("source", "pc-control");
        assertThat(response.diagnostics()).containsEntry("requestId", "req-42");
        assertThat(response.diagnostics()).containsEntry("detectorProviderUsed", "opencv-haarcascade");
        assertThat(response.diagnostics()).containsEntry("verifierProviderUsed", "average-hash-baseline");
        assertThat(response.diagnostics()).containsEntry("identitySignalState", "NO_FACE");
    }

    @Test
    void returnsUnavailableWhenConfiguredVerifierProviderIsMissing() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setVerifierProvider("missing-provider");
        when(faceDetectionProvider.providerId()).thenReturn("opencv-haarcascade");

        DefaultVisionVerificationService service =
                new DefaultVisionVerificationService(
                        List.of(faceDetectionProvider),
                        List.of(),
                        properties,
                        visionImagePipelineService,
                        new HeuristicFaceAlignmentService(properties),
                        new HeuristicFaceLivenessAssessor(properties),
                        new IdentitySignalEvaluator(),
                        new DefaultSecurityIncidentScoringService());
        when(visionImagePipelineService.process(any(), any())).thenReturn(pipelineExecution());

        VisionVerifyOwnerResponse response = service.verifyOwner(
                new VisionVerifyOwnerRequest(pngBytes(), "png", "pc-control", "req-99", Map.of()));

        assertThat(response.outcome()).isEqualTo(VisionVerificationOutcome.UNAVAILABLE);
        assertThat(response.operational()).isFalse();
        assertThat(response.message()).contains("Configured verifier provider is unavailable");
        assertThat(response.diagnostics()).containsEntry("availableVerifierProviders", "");
    }

    @Test
    void selectsConfiguredEmbeddingVerifierWhenMultipleProvidersExist() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setVerifierProvider("embedding-cosine-mvp");
        DefaultVisionVerificationService service =
                new DefaultVisionVerificationService(
                        List.of(faceDetectionProvider),
                        List.of(faceVerificationProvider, fallbackVerificationProvider),
                        properties,
                        visionImagePipelineService,
                        new HeuristicFaceAlignmentService(properties),
                        new HeuristicFaceLivenessAssessor(properties),
                        new IdentitySignalEvaluator(),
                        new DefaultSecurityIncidentScoringService());
        byte[] imageBytes = pngBytes();

        when(faceDetectionProvider.providerId()).thenReturn("opencv-haarcascade");
        when(faceVerificationProvider.providerId()).thenReturn("average-hash-baseline");
        when(fallbackVerificationProvider.providerId()).thenReturn("embedding-cosine-mvp");
        when(fallbackVerificationProvider.isAvailable()).thenReturn(true);
        when(visionImagePipelineService.process(any(), any())).thenReturn(pipelineExecution());
        when(fallbackVerificationProvider.verifyOwner(any(), any(), any())).thenReturn(
                new VisionVerifyOwnerResponse(
                        VisionVerificationOutcome.UNKNOWN,
                        true,
                        "embedding-cosine-mvp",
                        "below threshold",
                        0.70d,
                        2,
                        List.of(),
                        Map.of("embeddingVerifier", "cosine")));

        VisionVerifyOwnerResponse response = service.verifyOwner(
                new VisionVerifyOwnerRequest(imageBytes, "png", "pc-control", "req-88", Map.of()));

        assertThat(response.provider()).isEqualTo("embedding-cosine-mvp");
        assertThat(response.diagnostics()).containsEntry("verifierProviderUsed", "embedding-cosine-mvp");
    }

    @Test
    void selectsConfiguredModelBackedVerifierWhenMultipleProvidersExist() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setVerifierProvider("embedding-cosine-model");
        DefaultVisionVerificationService service =
                new DefaultVisionVerificationService(
                        List.of(faceDetectionProvider),
                        List.of(faceVerificationProvider, fallbackVerificationProvider, modelVerificationProvider),
                        properties,
                        visionImagePipelineService,
                        new HeuristicFaceAlignmentService(properties),
                        new HeuristicFaceLivenessAssessor(properties),
                        new IdentitySignalEvaluator(),
                        new DefaultSecurityIncidentScoringService());
        byte[] imageBytes = pngBytes();

        when(faceDetectionProvider.providerId()).thenReturn("opencv-haarcascade");
        when(faceVerificationProvider.providerId()).thenReturn("average-hash-baseline");
        when(fallbackVerificationProvider.providerId()).thenReturn("embedding-cosine-mvp");
        when(modelVerificationProvider.providerId()).thenReturn("embedding-cosine-model");
        when(modelVerificationProvider.isAvailable()).thenReturn(true);
        when(visionImagePipelineService.process(any(), any())).thenReturn(pipelineExecution());
        when(modelVerificationProvider.verifyOwner(any(), any(), any())).thenReturn(
                new VisionVerifyOwnerResponse(
                        VisionVerificationOutcome.OWNER,
                        true,
                        "embedding-cosine-model",
                        "matched",
                        0.95d,
                        3,
                        List.of(),
                        Map.of("implementationType", "model-backed-embedding")));

        VisionVerifyOwnerResponse response = service.verifyOwner(
                new VisionVerifyOwnerRequest(imageBytes, "png", "pc-control", "req-101", Map.of()));

        assertThat(response.provider()).isEqualTo("embedding-cosine-model");
        assertThat(response.diagnostics()).containsEntry("verifierProviderUsed", "embedding-cosine-model");
    }

    @Test
    void returnsUnavailableWhenConfiguredVerifierBackendIsNotOperational() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setVerifierProvider("embedding-cosine-model");

        when(faceDetectionProvider.providerId()).thenReturn("opencv-haarcascade");
        when(modelVerificationProvider.providerId()).thenReturn("embedding-cosine-model");
        when(modelVerificationProvider.isAvailable()).thenReturn(false);
        when(modelVerificationProvider.availabilityMessage()).thenReturn("Embedding model path is not configured");
        when(modelVerificationProvider.statusDetails(faceDetectionProvider)).thenReturn(
                Map.of(
                        "embeddingBackendConfigured", "opencv-dnn-onnx",
                        "embeddingBackendAvailable", "false"));

        DefaultVisionVerificationService service =
                new DefaultVisionVerificationService(
                        List.of(faceDetectionProvider),
                        List.of(modelVerificationProvider),
                        properties,
                        visionImagePipelineService,
                        new HeuristicFaceAlignmentService(properties),
                        new HeuristicFaceLivenessAssessor(properties),
                        new IdentitySignalEvaluator(),
                        new DefaultSecurityIncidentScoringService());
        when(visionImagePipelineService.process(any(), any())).thenReturn(pipelineExecution());

        VisionVerifyOwnerResponse response = service.verifyOwner(
                new VisionVerifyOwnerRequest(pngBytes(), "png", "pc-control", "req-111", Map.of()));

        assertThat(response.outcome()).isEqualTo(VisionVerificationOutcome.UNAVAILABLE);
        assertThat(response.message()).contains("Embedding model path is not configured");
        assertThat(response.diagnostics()).containsEntry("embeddingBackendAvailable", "false");
    }

    @Test
    void debugResponseExposesProfessorVisibleStageArtifactsAndDecision() throws Exception {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setVerifierProvider("embedding-cosine-model");
        DefaultVisionVerificationService service =
                new DefaultVisionVerificationService(
                        List.of(faceDetectionProvider),
                        List.of(modelVerificationProvider),
                        properties,
                        visionImagePipelineService,
                        new HeuristicFaceAlignmentService(properties),
                        new HeuristicFaceLivenessAssessor(properties),
                        new IdentitySignalEvaluator(),
                        new DefaultSecurityIncidentScoringService());

        when(faceDetectionProvider.providerId()).thenReturn("opencv-haarcascade");
        when(modelVerificationProvider.providerId()).thenReturn("embedding-cosine-model");
        when(modelVerificationProvider.isAvailable()).thenReturn(true);
        when(visionImagePipelineService.process(any(), any())).thenReturn(pipelineExecution());
        when(modelVerificationProvider.verifyOwner(any(), any(), any())).thenReturn(
                new VisionVerifyOwnerResponse(
                        VisionVerificationOutcome.OWNER,
                        true,
                        "embedding-cosine-model",
                        "matched",
                        0.97d,
                        2,
                        List.of(),
                        Map.of()));

        VisionVerifyOwnerDebugResponse response = service.verifyOwnerDebug(
                new VisionVerifyOwnerRequest(pngBytes(), "png", "pc-control", "req-debug", Map.of()));

        assertThat(response.finalDecision()).isEqualTo(VisionSecurityDecision.AUTHORIZED);
        assertThat(response.stages()).extracting(VisionPipelineStageResult::stage).contains(
                VisionPipelineStage.ORIGINAL,
                VisionPipelineStage.ENHANCEMENT,
                VisionPipelineStage.SEGMENTATION,
                VisionPipelineStage.CLEANING,
                VisionPipelineStage.DETECTION,
                VisionPipelineStage.DECISION);
        assertThat(response.artifacts()).extracting(VisionImageArtifact::stage).contains(
                VisionPipelineStage.ORIGINAL,
                VisionPipelineStage.ENHANCEMENT,
                VisionPipelineStage.SEGMENTATION,
                VisionPipelineStage.CLEANING,
                VisionPipelineStage.DETECTION);
        assertThat(response.diagnostics()).containsEntry("finalDecision", "AUTHORIZED");
        assertThat(response.diagnostics()).containsKey("identitySignalState");
        assertThat(response.diagnostics()).containsKey("incidentDisposition");
    }

    private static VisionPipelineExecution pipelineExecution() {
        BufferedImage artifact = new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR);
        FaceDetectionProvider.DetectionResult detectionResult =
                new FaceDetectionProvider.DetectionResult(
                        true,
                        "opencv-haarcascade",
                        "Detected 1 face(s)",
                        List.of(new VisionFaceRegion(1, 1, 2, 2)));
        return new VisionPipelineExecution(
                List.of(
                        new VisionPipelineArtifactImage(VisionPipelineStage.ORIGINAL, artifact, "original"),
                        new VisionPipelineArtifactImage(VisionPipelineStage.ENHANCEMENT, artifact, "enhanced"),
                        new VisionPipelineArtifactImage(VisionPipelineStage.SEGMENTATION, artifact, "segmentation"),
                        new VisionPipelineArtifactImage(VisionPipelineStage.CLEANING, artifact, "cleaning"),
                        new VisionPipelineArtifactImage(VisionPipelineStage.DETECTION, artifact, "detection")),
                List.of(
                        new VisionPipelineStageSnapshot(VisionPipelineStage.ORIGINAL, "original", Map.of(), List.of()),
                        new VisionPipelineStageSnapshot(VisionPipelineStage.ENHANCEMENT, "enhancement", Map.of(), List.of()),
                        new VisionPipelineStageSnapshot(VisionPipelineStage.SEGMENTATION, "segmentation", Map.of(), List.of()),
                        new VisionPipelineStageSnapshot(VisionPipelineStage.CLEANING, "cleaning", Map.of(), List.of()),
                        new VisionPipelineStageSnapshot(VisionPipelineStage.DETECTION, "detection", Map.of(), List.of())),
                detectionResult,
                Map.of("pipelineOperational", "true"));
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
