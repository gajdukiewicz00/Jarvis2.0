package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionConfigStatusResponse;
import org.jarvis.common.vision.VisionHealthResponse;
import org.jarvis.common.vision.VisionPipelineStage;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.FaceLivenessAssessor;
import org.jarvis.vision.service.FaceVerificationProvider;
import org.jarvis.vision.service.OwnerReferenceService;
import org.jarvis.vision.service.VisionStatusService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultVisionStatusService implements VisionStatusService {

    private final VisionServiceProperties properties;
    private final OpenCvRuntime openCvRuntime;
    private final OwnerReferenceService ownerReferenceService;
    private final FaceAlignmentService faceAlignmentService;
    private final FaceLivenessAssessor faceLivenessAssessor;
    private final List<FaceDetectionProvider> faceDetectionProviders;
    private final List<FaceVerificationProvider> faceVerificationProviders;

    @Override
    public VisionHealthResponse health() {
        FaceDetectionProvider selectedDetectionProvider = selectedDetectionProvider();
        FaceVerificationProvider selectedVerificationProvider = selectedVerificationProvider();
        Map<String, String> diagnostics = baseDiagnostics(selectedDetectionProvider, selectedVerificationProvider);
        boolean detectorConfigured = selectedDetectionProvider != null;
        boolean verifierConfigured = selectedVerificationProvider != null;
        boolean verifierAvailable = verifierConfigured && selectedVerificationProvider.isAvailable();
        diagnostics.put("detectorProviderConfigured", String.valueOf(detectorConfigured));
        diagnostics.put("verifierProviderConfigured", String.valueOf(verifierConfigured));
        diagnostics.put("verifierProviderAvailable", String.valueOf(verifierAvailable));

        boolean available = properties.isEnabled()
                && detectorConfigured
                && verifierConfigured
                && verifierAvailable
                && openCvRuntime.isAvailable();
        String message = !properties.isEnabled()
                ? "Vision service disabled"
                : !detectorConfigured
                ? "Configured detector provider is unavailable"
                : !verifierConfigured
                ? "Configured verifier provider is unavailable"
                : !verifierAvailable
                ? selectedVerificationProvider.availabilityMessage().isBlank()
                    ? "Configured verifier provider is not operational"
                    : selectedVerificationProvider.availabilityMessage()
                : openCvRuntime.isAvailable()
                ? "Vision service ready"
                : openCvRuntime.failureMessage();

        return new VisionHealthResponse(
                available,
                properties.getDetectorProvider(),
                properties.getVerifierProvider(),
                message,
                diagnostics);
    }

    @Override
    public VisionConfigStatusResponse configStatus() {
        FaceDetectionProvider selectedDetectionProvider = selectedDetectionProvider();
        FaceVerificationProvider selectedVerificationProvider = selectedVerificationProvider();
        boolean detectorConfigured = selectedDetectionProvider != null;
        boolean verifierConfigured = selectedVerificationProvider != null;
        Map<String, String> diagnostics = baseDiagnostics(selectedDetectionProvider, selectedVerificationProvider);
        diagnostics.put("detectorProviderConfigured", String.valueOf(detectorConfigured));
        diagnostics.put("verifierProviderConfigured", String.valueOf(verifierConfigured));
        diagnostics.put("verifierProviderAvailable", String.valueOf(
                verifierConfigured && selectedVerificationProvider.isAvailable()));
        return new VisionConfigStatusResponse(
                properties.isEnabled(),
                properties.getDetectorProvider(),
                properties.getVerifierProvider(),
                properties.getSimilarityThreshold(),
                properties.getMinimumFaceSizePixels(),
                properties.getEnrollment().isEnabled(),
                diagnostics);
    }

    private Map<String, String> baseDiagnostics(FaceDetectionProvider selectedDetectionProvider,
                                                FaceVerificationProvider selectedVerificationProvider) {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("referenceImageCount", String.valueOf(ownerReferenceService.countReferences()));
        diagnostics.put("enabled", String.valueOf(properties.isEnabled()));
        diagnostics.put("openCvRuntimeAvailable", String.valueOf(openCvRuntime.isAvailable()));
        diagnostics.put("debugEndpointSupported", "true");
        diagnostics.put("screenAnalysisEndpointSupported", "true");
        diagnostics.put("pipelineStages", pipelineStages());
        diagnostics.put("availableDetectorProviders", detectionProviderNames(faceDetectionProviders));
        diagnostics.put("availableVerifierProviders", verificationProviderNames(faceVerificationProviders));
        diagnostics.put("alignmentConfigured", String.valueOf(properties.getAlignment().isEnabled()));
        diagnostics.putAll(faceAlignmentService.statusDetails());
        diagnostics.put("livenessConfigured", String.valueOf(properties.getLiveness().isEnabled()));
        diagnostics.putAll(faceLivenessAssessor.statusDetails());
        diagnostics.put("referenceEmbeddingPrewarmOnStartup",
                String.valueOf(properties.getReferenceCache().isPrewarmOnStartup()));
        diagnostics.put("screenAnalysisConfigured", String.valueOf(properties.getScreen().isEnabled()));
        diagnostics.put("screenAnalysisMethod", "heuristic-foundation");
        diagnostics.put("screenAnalysisSensitiveThreshold",
                format(properties.getScreen().getSensitiveThreshold()));
        if (selectedVerificationProvider != null) {
            diagnostics.putAll(selectedVerificationProvider.statusDetails(selectedDetectionProvider));
        }
        return diagnostics;
    }

    private FaceDetectionProvider selectedDetectionProvider() {
        return faceDetectionProviders.stream()
                .filter(provider -> provider.providerId().equals(properties.getDetectorProvider()))
                .findFirst()
                .orElse(null);
    }

    private FaceVerificationProvider selectedVerificationProvider() {
        return faceVerificationProviders.stream()
                .filter(provider -> provider.providerId().equals(properties.getVerifierProvider()))
                .findFirst()
                .orElse(null);
    }

    private static String detectionProviderNames(List<FaceDetectionProvider> providers) {
        return providers.stream()
                .map(FaceDetectionProvider::providerId)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static String verificationProviderNames(List<FaceVerificationProvider> providers) {
        return providers.stream()
                .map(FaceVerificationProvider::providerId)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static String pipelineStages() {
        return java.util.Arrays.stream(VisionPipelineStage.values())
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
