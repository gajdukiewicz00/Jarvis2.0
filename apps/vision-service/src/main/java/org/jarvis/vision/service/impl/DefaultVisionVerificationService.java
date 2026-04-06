package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.vision.VisionImageArtifact;
import org.jarvis.common.vision.VisionPipelineStage;
import org.jarvis.common.vision.VisionPipelineStageResult;
import org.jarvis.common.vision.VisionSecurityDecision;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerDebugResponse;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.pipeline.VisionImagePipelineService;
import org.jarvis.vision.pipeline.VisionPipelineExecution;
import org.jarvis.vision.pipeline.VisionPipelineStageSnapshot;
import org.jarvis.vision.service.FaceAlignmentResult;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.FaceLivenessAssessment;
import org.jarvis.vision.service.FaceLivenessAssessor;
import org.jarvis.vision.service.FaceVerificationProvider;
import org.jarvis.vision.service.IdentitySignal;
import org.jarvis.vision.service.SecurityIncidentAssessment;
import org.jarvis.vision.service.SecurityIncidentScoringService;
import org.jarvis.vision.service.VisionVerificationService;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultVisionVerificationService implements VisionVerificationService {

    private final List<FaceDetectionProvider> faceDetectionProviders;
    private final List<FaceVerificationProvider> faceVerificationProviders;
    private final VisionServiceProperties properties;
    private final VisionImagePipelineService visionImagePipelineService;
    private final FaceAlignmentService faceAlignmentService;
    private final FaceLivenessAssessor faceLivenessAssessor;
    private final IdentitySignalEvaluator identitySignalEvaluator;
    private final SecurityIncidentScoringService securityIncidentScoringService;

    @Override
    public VisionVerifyOwnerResponse verifyOwner(VisionVerifyOwnerRequest request) {
        return execute(request).verificationResponse();
    }

    @Override
    public VisionVerifyOwnerDebugResponse verifyOwnerDebug(VisionVerifyOwnerRequest request) {
        return execute(request).debugResponse();
    }

    private VerificationExecution execute(VisionVerifyOwnerRequest request) {
        Map<String, String> baseDiagnostics = baseDiagnostics(request);
        if (!properties.isEnabled()) {
            return unavailableExecution(
                    "Vision service is disabled",
                    "",
                    baseDiagnostics,
                    null,
                    null,
                    null);
        }
        if (request.imageBytes().length == 0) {
            return unavailableExecution(
                    "Image payload is required",
                    "",
                    baseDiagnostics,
                    null,
                    null,
                    null);
        }

        FaceDetectionProvider faceDetectionProvider = selectDetectionProvider();
        if (faceDetectionProvider == null) {
            return unavailableExecution(
                    "Configured detector provider is unavailable: " + properties.getDetectorProvider(),
                    properties.getDetectorProvider(),
                    withAvailableProviders(baseDiagnostics),
                    null,
                    null,
                    null);
        }

        try {
            BufferedImage image = OpenCvImageUtils.decode(request.imageBytes());
            VisionPipelineExecution pipelineExecution = visionImagePipelineService.process(image, faceDetectionProvider);

            FaceVerificationProvider faceVerificationProvider = selectVerificationProvider();
            if (faceVerificationProvider == null) {
                Map<String, String> diagnostics = withAvailableProviders(baseDiagnostics);
                diagnostics.putAll(pipelineExecution.diagnostics());
                return unavailableExecution(
                        "Configured verifier provider is unavailable: " + properties.getVerifierProvider(),
                        properties.getVerifierProvider(),
                        diagnostics,
                        pipelineExecution,
                        new FaceDetectionProvider.DetectionResult(
                                pipelineExecution.detectionResult().operational(),
                                pipelineExecution.detectionResult().provider(),
                                pipelineExecution.detectionResult().message(),
                                pipelineExecution.detectionResult().faces()),
                        image);
            }
            if (!faceVerificationProvider.isAvailable()) {
                Map<String, String> diagnostics = withAvailableProviders(baseDiagnostics);
                diagnostics.putAll(faceVerificationProvider.statusDetails(faceDetectionProvider));
                diagnostics.putAll(pipelineExecution.diagnostics());
                diagnostics.put("configuredDetectorProvider", properties.getDetectorProvider());
                diagnostics.put("configuredVerifierProvider", properties.getVerifierProvider());
                return unavailableExecution(
                        faceVerificationProvider.availabilityMessage().isBlank()
                                ? "Configured verifier provider is not operational: " + properties.getVerifierProvider()
                                : faceVerificationProvider.availabilityMessage(),
                        faceVerificationProvider.providerId(),
                        diagnostics,
                        pipelineExecution,
                        pipelineExecution.detectionResult(),
                        image);
            }

            VisionVerifyOwnerResponse providerResponse = faceVerificationProvider.verifyOwner(
                    image,
                    faceDetectionProvider,
                    pipelineExecution.detectionResult());
            FaceAnalysisContext faceAnalysisContext = analyzePrimaryFace(image, pipelineExecution.detectionResult());
            VisionVerifyOwnerResponse mergedResponse = mergeResponse(
                    providerResponse,
                    baseDiagnostics,
                    pipelineExecution.diagnostics(),
                    faceDetectionProvider.providerId(),
                    faceVerificationProvider.providerId(),
                    faceAnalysisContext);

            VisionSecurityDecision finalDecision = mapFinalDecision(
                    pipelineExecution.detectionResult(),
                    mergedResponse.outcome());
            return new VerificationExecution(
                    mergedResponse,
                    buildDebugResponse(mergedResponse, finalDecision, pipelineExecution, mergedResponse.diagnostics()));
        } catch (Exception exception) {
            log.warn("Vision verification failed: requestId={}, source={}, message={}",
                    request.requestId(), request.source(), exception.getMessage());
            return unavailableExecution(exception.getMessage(), "", baseDiagnostics, null, null, null);
        }
    }

    private VisionVerifyOwnerResponse mergeResponse(VisionVerifyOwnerResponse response,
                                                    Map<String, String> baseDiagnostics,
                                                    Map<String, String> pipelineDiagnostics,
                                                    String detectorProviderUsed,
                                                    String verifierProviderUsed,
                                                    FaceAnalysisContext faceAnalysisContext) {
        Map<String, String> diagnostics = new LinkedHashMap<>(response.diagnostics());
        diagnostics.putAll(baseDiagnostics);
        diagnostics.putAll(pipelineDiagnostics);
        diagnostics.put("configuredDetectorProvider", properties.getDetectorProvider());
        diagnostics.put("configuredVerifierProvider", properties.getVerifierProvider());
        diagnostics.put("detectorProviderUsed", detectorProviderUsed);
        diagnostics.put("verifierProviderUsed", verifierProviderUsed);
        diagnostics.putAll(faceAnalysisContext.alignmentResult().diagnostics());
        diagnostics.putAll(faceAnalysisContext.livenessAssessment().diagnostics());

        IdentitySignal identitySignal = identitySignalEvaluator.evaluate(
                response,
                faceAnalysisContext.livenessAssessment(),
                properties);
        diagnostics.putAll(identitySignal.diagnostics());
        diagnostics.put("identitySignalMessage", identitySignal.message());
        diagnostics.put("identitySignalConfidence", format(identitySignal.confidence()));

        SecurityIncidentAssessment incidentAssessment = securityIncidentScoringService.assess(
                identitySignal,
                faceAnalysisContext.livenessAssessment(),
                null,
                response.operational());
        diagnostics.putAll(incidentAssessment.diagnostics());
        diagnostics.put("incidentAssessmentMessage", incidentAssessment.message());

        return new VisionVerifyOwnerResponse(
                response.outcome(),
                response.operational(),
                response.provider(),
                response.message(),
                response.similarity(),
                response.referenceImageCount(),
                response.detectedFaces(),
                diagnostics);
    }

    private VerificationExecution unavailableExecution(String message,
                                                       String provider,
                                                       Map<String, String> diagnostics,
                                                       VisionPipelineExecution pipelineExecution,
                                                       FaceDetectionProvider.DetectionResult detectionResult,
                                                       BufferedImage image) {
        FaceDetectionProvider.DetectionResult effectiveDetection = detectionResult == null
                ? new FaceDetectionProvider.DetectionResult(false, "", message, List.of())
                : detectionResult;
        Map<String, String> mergedDiagnostics = new LinkedHashMap<>(diagnostics);
        if (pipelineExecution != null) {
            mergedDiagnostics.putAll(pipelineExecution.diagnostics());
        }
        FaceAnalysisContext faceAnalysisContext = analyzePrimaryFace(image, effectiveDetection);
        mergedDiagnostics.putAll(faceAnalysisContext.alignmentResult().diagnostics());
        mergedDiagnostics.putAll(faceAnalysisContext.livenessAssessment().diagnostics());

        VisionVerifyOwnerResponse response = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNAVAILABLE,
                false,
                provider,
                message,
                null,
                0,
                effectiveDetection.faces(),
                mergedDiagnostics);

        IdentitySignal identitySignal = identitySignalEvaluator.evaluate(
                response,
                faceAnalysisContext.livenessAssessment(),
                properties);
        mergedDiagnostics.putAll(identitySignal.diagnostics());
        mergedDiagnostics.put("identitySignalMessage", identitySignal.message());
        mergedDiagnostics.put("identitySignalConfidence", format(identitySignal.confidence()));

        SecurityIncidentAssessment incidentAssessment = securityIncidentScoringService.assess(
                identitySignal,
                faceAnalysisContext.livenessAssessment(),
                null,
                false);
        mergedDiagnostics.putAll(incidentAssessment.diagnostics());
        mergedDiagnostics.put("incidentAssessmentMessage", incidentAssessment.message());

        VisionVerifyOwnerResponse enrichedResponse = new VisionVerifyOwnerResponse(
                response.outcome(),
                response.operational(),
                response.provider(),
                response.message(),
                response.similarity(),
                response.referenceImageCount(),
                response.detectedFaces(),
                mergedDiagnostics);

        VisionSecurityDecision finalDecision = mapFinalDecision(effectiveDetection, enrichedResponse.outcome());
        return new VerificationExecution(
                enrichedResponse,
                buildDebugResponse(enrichedResponse, finalDecision, pipelineExecution, mergedDiagnostics));
    }

    private FaceAnalysisContext analyzePrimaryFace(BufferedImage image,
                                                   FaceDetectionProvider.DetectionResult detectionResult) {
        if (image == null || detectionResult == null || !detectionResult.operational() || detectionResult.faces().isEmpty()) {
            return new FaceAnalysisContext(
                    new FaceAlignmentResult(
                            false,
                            false,
                            faceAlignmentService.providerId(),
                            "not-run",
                            "Alignment not run",
                            null,
                            Map.of(
                                    "alignmentProvider", faceAlignmentService.providerId(),
                                    "alignmentApplied", "false",
                                    "alignmentMode", "not-run")),
                    new FaceLivenessAssessment(
                            false,
                            false,
                            null,
                            faceLivenessAssessor.providerId(),
                            "Liveness not run",
                            Map.of(
                                    "livenessProvider", faceLivenessAssessor.providerId(),
                                    "livenessAvailable", "false",
                                    "livenessPassed", "false")));
        }

        var face = detectionResult.faces().getFirst();
        BufferedImage cropped = OpenCvImageUtils.crop(image, face.x(), face.y(), face.width(), face.height());
        FaceAlignmentResult alignmentResult = faceAlignmentService.align(cropped);
        FaceLivenessAssessment livenessAssessment = faceLivenessAssessor.assess(
                alignmentResult.faceImage() == null ? cropped : alignmentResult.faceImage());
        return new FaceAnalysisContext(alignmentResult, livenessAssessment);
    }

    private VisionVerifyOwnerDebugResponse buildDebugResponse(VisionVerifyOwnerResponse response,
                                                              VisionSecurityDecision finalDecision,
                                                              VisionPipelineExecution pipelineExecution,
                                                              Map<String, String> diagnostics) {
        List<VisionPipelineStageResult> stageResults = new ArrayList<>();
        List<VisionImageArtifact> artifacts = new ArrayList<>();
        if (pipelineExecution != null) {
            stageResults.addAll(pipelineExecution.stages().stream()
                    .map(this::toDto)
                    .toList());
            artifacts.addAll(pipelineExecution.artifacts().stream()
                    .map(this::toDto)
                    .toList());
        }
        stageResults.add(new VisionPipelineStageResult(
                VisionPipelineStage.DECISION,
                "Final security decision derived from detection + verification",
                decisionMetrics(finalDecision, response, diagnostics),
                response.detectedFaces()));

        Map<String, String> mergedDiagnostics = new LinkedHashMap<>(diagnostics);
        mergedDiagnostics.put("finalDecision", finalDecision.name());
        return new VisionVerifyOwnerDebugResponse(
                finalDecision,
                response,
                stageResults,
                artifacts,
                mergedDiagnostics);
    }

    private VisionImageArtifact toDto(org.jarvis.vision.pipeline.VisionPipelineArtifactImage artifact) {
        try {
            byte[] imageBytes = OpenCvImageUtils.encodePng(artifact.image());
            return new VisionImageArtifact(
                    artifact.stage(),
                    "image/png",
                    Base64.getEncoder().encodeToString(imageBytes),
                    artifact.image().getWidth(),
                    artifact.image().getHeight(),
                    artifact.description());
        } catch (Exception exception) {
            return new VisionImageArtifact(
                    artifact.stage(),
                    "image/png",
                    "",
                    0,
                    0,
                    "Failed to encode artifact: " + exception.getMessage());
        }
    }

    private VisionPipelineStageResult toDto(VisionPipelineStageSnapshot snapshot) {
        return new VisionPipelineStageResult(
                snapshot.stage(),
                snapshot.description(),
                snapshot.metrics(),
                snapshot.regions());
    }

    private FaceDetectionProvider selectDetectionProvider() {
        return faceDetectionProviders.stream()
                .filter(provider -> provider.providerId().equals(properties.getDetectorProvider()))
                .findFirst()
                .orElse(null);
    }

    private FaceVerificationProvider selectVerificationProvider() {
        return faceVerificationProviders.stream()
                .filter(provider -> provider.providerId().equals(properties.getVerifierProvider()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> baseDiagnostics(VisionVerifyOwnerRequest request) {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("source", request.source());
        if (!request.requestId().isBlank()) {
            diagnostics.put("requestId", request.requestId());
        }
        return diagnostics;
    }

    private Map<String, String> withAvailableProviders(Map<String, String> diagnostics) {
        Map<String, String> result = new LinkedHashMap<>(diagnostics);
        result.put("availableDetectorProviders", faceDetectionProviders.stream()
                .map(FaceDetectionProvider::providerId)
                .sorted()
                .collect(Collectors.joining(",")));
        result.put("availableVerifierProviders", faceVerificationProviders.stream()
                .map(FaceVerificationProvider::providerId)
                .sorted()
                .collect(Collectors.joining(",")));
        return result;
    }

    private static VisionSecurityDecision mapFinalDecision(FaceDetectionProvider.DetectionResult detectionResult,
                                                           VisionVerificationOutcome verificationOutcome) {
        if (detectionResult == null || !detectionResult.operational()) {
            return VisionSecurityDecision.UNAVAILABLE;
        }
        if (detectionResult.faces().isEmpty()) {
            return VisionSecurityDecision.NO_FACE;
        }
        return switch (verificationOutcome) {
            case OWNER -> VisionSecurityDecision.AUTHORIZED;
            case UNKNOWN -> VisionSecurityDecision.UNAUTHORIZED;
            case NO_FACE -> VisionSecurityDecision.NO_FACE;
            case UNAVAILABLE -> VisionSecurityDecision.UNAVAILABLE;
        };
    }

    private static Map<String, String> decisionMetrics(VisionSecurityDecision finalDecision,
                                                       VisionVerifyOwnerResponse response,
                                                       Map<String, String> diagnostics) {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("finalDecision", finalDecision.name());
        metrics.put("verificationOutcome", response.outcome().name());
        metrics.put("operational", String.valueOf(response.operational()));
        if (response.similarity() != null) {
            metrics.put("similarity", String.valueOf(response.similarity()));
        }
        metrics.put("referenceImageCount", String.valueOf(response.referenceImageCount()));
        copyIfPresent(diagnostics, metrics, "identitySignalState");
        copyIfPresent(diagnostics, metrics, "identitySignalConfidence");
        copyIfPresent(diagnostics, metrics, "alignmentMode");
        copyIfPresent(diagnostics, metrics, "alignmentRotationDegrees");
        copyIfPresent(diagnostics, metrics, "livenessPassed");
        copyIfPresent(diagnostics, metrics, "livenessConfidence");
        copyIfPresent(diagnostics, metrics, "incidentDisposition");
        copyIfPresent(diagnostics, metrics, "incidentScore");
        return metrics;
    }

    private static void copyIfPresent(Map<String, String> source, Map<String, String> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private record VerificationExecution(
            VisionVerifyOwnerResponse verificationResponse,
            VisionVerifyOwnerDebugResponse debugResponse) {
    }

    private record FaceAnalysisContext(
            FaceAlignmentResult alignmentResult,
            FaceLivenessAssessment livenessAssessment) {
    }
}
