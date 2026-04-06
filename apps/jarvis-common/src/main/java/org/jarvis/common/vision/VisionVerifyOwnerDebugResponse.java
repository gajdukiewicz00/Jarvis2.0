package org.jarvis.common.vision;

import java.util.List;
import java.util.Map;

public record VisionVerifyOwnerDebugResponse(
        VisionSecurityDecision finalDecision,
        VisionVerifyOwnerResponse verification,
        List<VisionPipelineStageResult> stages,
        List<VisionImageArtifact> artifacts,
        Map<String, String> diagnostics) {

    public VisionVerifyOwnerDebugResponse {
        finalDecision = finalDecision == null ? VisionSecurityDecision.UNAVAILABLE : finalDecision;
        verification = verification == null
                ? new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNAVAILABLE,
                false,
                "",
                "",
                null,
                0,
                List.of(),
                Map.of())
                : verification;
        stages = stages == null ? List.of() : List.copyOf(stages);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
