package org.jarvis.vision.service;

import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.impl.DefaultVisionStatusService;
import org.jarvis.vision.service.impl.HeuristicFaceAlignmentService;
import org.jarvis.vision.service.impl.HeuristicFaceLivenessAssessor;
import org.jarvis.vision.service.impl.OpenCvRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultVisionStatusServiceTest {

    @Mock
    private OpenCvRuntime openCvRuntime;
    @Mock
    private OwnerReferenceService ownerReferenceService;
    @Mock
    private FaceDetectionProvider faceDetectionProvider;
    @Mock
    private FaceVerificationProvider baselineVerificationProvider;
    @Mock
    private FaceVerificationProvider embeddingVerificationProvider;
    @Mock
    private FaceVerificationProvider modelVerificationProvider;

    @Test
    void healthAndConfigExposeConfiguredVerifierBackendAndCacheStatus() {
        VisionServiceProperties properties = new VisionServiceProperties();
        properties.setVerifierProvider("embedding-cosine-model");
        properties.getReferenceCache().setPrewarmOnStartup(true);

        when(openCvRuntime.isAvailable()).thenReturn(true);
        when(ownerReferenceService.countReferences()).thenReturn(3);
        when(faceDetectionProvider.providerId()).thenReturn("opencv-haarcascade");
        when(baselineVerificationProvider.providerId()).thenReturn("average-hash-baseline");
        when(embeddingVerificationProvider.providerId()).thenReturn("embedding-cosine-mvp");
        when(modelVerificationProvider.providerId()).thenReturn("embedding-cosine-model");
        when(modelVerificationProvider.isAvailable()).thenReturn(true);
        when(modelVerificationProvider.statusDetails(any())).thenReturn(
                java.util.Map.of(
                        "embeddingBackendConfigured", "opencv-dnn-onnx",
                        "embeddingBackendAvailable", "true",
                        "referenceEmbeddingCacheLoaded", "false",
                        "referenceEmbeddingCacheState", "cold"));

        DefaultVisionStatusService service = new DefaultVisionStatusService(
                properties,
                openCvRuntime,
                ownerReferenceService,
                new HeuristicFaceAlignmentService(properties),
                new HeuristicFaceLivenessAssessor(properties),
                List.of(faceDetectionProvider),
                List.of(baselineVerificationProvider, embeddingVerificationProvider, modelVerificationProvider));

        var health = service.health();
        var config = service.configStatus();

        assertThat(health.available()).isTrue();
        assertThat(health.verifierProvider()).isEqualTo("embedding-cosine-model");
        assertThat(health.diagnostics()).containsEntry("verifierProviderConfigured", "true");
        assertThat(health.diagnostics()).containsEntry("embeddingBackendConfigured", "opencv-dnn-onnx");
        assertThat(health.diagnostics()).containsEntry("referenceEmbeddingCacheState", "cold");
        assertThat(health.diagnostics()).containsEntry("debugEndpointSupported", "true");
        assertThat(health.diagnostics()).containsEntry("screenAnalysisEndpointSupported", "true");
        assertThat(health.diagnostics()).containsEntry("referenceEmbeddingPrewarmOnStartup", "true");
        assertThat(health.diagnostics()).containsEntry("alignmentProvider", "heuristic-eye-center");
        assertThat(health.diagnostics()).containsEntry("livenessProvider", "heuristic-single-frame");
        assertThat(health.diagnostics()).containsEntry(
                "availableVerifierProviders",
                "average-hash-baseline,embedding-cosine-model,embedding-cosine-mvp");

        assertThat(config.verifierProvider()).isEqualTo("embedding-cosine-model");
        assertThat(config.diagnostics()).containsEntry("verifierProviderConfigured", "true");
        assertThat(config.diagnostics()).containsEntry("embeddingBackendConfigured", "opencv-dnn-onnx");
        assertThat(config.diagnostics()).containsEntry("referenceEmbeddingCacheLoaded", "false");
        assertThat(config.diagnostics()).containsEntry("debugEndpointSupported", "true");
        assertThat(config.diagnostics()).containsEntry("screenAnalysisEndpointSupported", "true");
        assertThat(config.diagnostics()).containsEntry("alignmentLandmarkMode", "false");
        assertThat(config.diagnostics()).containsEntry(
                "availableVerifierProviders",
                "average-hash-baseline,embedding-cosine-model,embedding-cosine-mvp");
    }
}
