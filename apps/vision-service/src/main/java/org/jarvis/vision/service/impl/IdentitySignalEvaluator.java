package org.jarvis.vision.service.impl;

import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceLivenessAssessment;
import org.jarvis.vision.service.IdentitySignal;
import org.jarvis.vision.service.IdentitySignalState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class IdentitySignalEvaluator {

    private static final double LOW_CONFIDENCE_MARGIN = 0.03d;

    public IdentitySignal evaluate(VisionVerifyOwnerResponse response,
                                   FaceLivenessAssessment livenessAssessment,
                                   VisionServiceProperties properties) {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        double threshold = effectiveThreshold(response.provider(), properties);
        diagnostics.put("identityThreshold", format(threshold));

        if (!response.operational() || response.outcome() == VisionVerificationOutcome.UNAVAILABLE) {
            diagnostics.put("identitySignalState", IdentitySignalState.UNAVAILABLE.name());
            return new IdentitySignal(
                    IdentitySignalState.UNAVAILABLE,
                    0.0d,
                    "Verification unavailable",
                    diagnostics);
        }

        if (response.outcome() == VisionVerificationOutcome.NO_FACE) {
            diagnostics.put("identitySignalState", IdentitySignalState.NO_FACE.name());
            return new IdentitySignal(
                    IdentitySignalState.NO_FACE,
                    0.0d,
                    "No face available for identity scoring",
                    diagnostics);
        }

        double similarity = response.similarity() == null ? 0.0d : response.similarity();
        double thresholdGap = similarity - threshold;
        diagnostics.put("identitySimilarity", format(similarity));
        diagnostics.put("identityThresholdGap", format(thresholdGap));

        boolean livenessGatePassed = livenessAssessment == null
                || !livenessAssessment.available()
                || livenessAssessment.passed();
        diagnostics.put("identityLivenessGatePassed", String.valueOf(livenessGatePassed));

        if (response.outcome() == VisionVerificationOutcome.OWNER
                && thresholdGap >= LOW_CONFIDENCE_MARGIN
                && livenessGatePassed) {
            diagnostics.put("identitySignalState", IdentitySignalState.OWNER_CONFIRMED.name());
            return new IdentitySignal(
                    IdentitySignalState.OWNER_CONFIRMED,
                    bounded(0.75d + Math.min(0.25d, thresholdGap)),
                    "Owner confirmed with margin above threshold",
                    diagnostics);
        }

        if (response.outcome() == VisionVerificationOutcome.UNKNOWN
                && thresholdGap <= -LOW_CONFIDENCE_MARGIN) {
            diagnostics.put("identitySignalState", IdentitySignalState.UNKNOWN_CONFIRMED.name());
            return new IdentitySignal(
                    IdentitySignalState.UNKNOWN_CONFIRMED,
                    bounded(0.75d + Math.min(0.25d, Math.abs(thresholdGap))),
                    "Unknown face confirmed below threshold",
                    diagnostics);
        }

        diagnostics.put("identitySignalState", IdentitySignalState.LOW_CONFIDENCE.name());
        return new IdentitySignal(
                IdentitySignalState.LOW_CONFIDENCE,
                bounded(0.40d + Math.min(0.30d, Math.abs(thresholdGap))),
                livenessGatePassed
                        ? "Similarity is close to threshold"
                        : "Identity downgraded by liveness gate",
                diagnostics);
    }

    private static double effectiveThreshold(String provider, VisionServiceProperties properties) {
        if ("embedding-cosine-model".equals(provider)
                && properties.getEmbedding().getModel().getSimilarityThreshold() != null) {
            return properties.getEmbedding().getModel().getSimilarityThreshold();
        }
        return properties.getSimilarityThreshold();
    }

    private static double bounded(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
