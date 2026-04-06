package org.jarvis.vision.service.impl;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.vision.service.FaceLivenessAssessment;
import org.jarvis.vision.service.IdentitySignal;
import org.jarvis.vision.service.IdentitySignalState;
import org.jarvis.vision.service.IncidentDisposition;
import org.jarvis.vision.service.SecurityIncidentAssessment;
import org.jarvis.vision.service.SecurityIncidentScoringService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DefaultSecurityIncidentScoringService implements SecurityIncidentScoringService {

    @Override
    public SecurityIncidentAssessment assess(IdentitySignal identitySignal,
                                             FaceLivenessAssessment livenessAssessment,
                                             VisionScreenAnalysisResponse screenAnalysis,
                                             boolean operational) {
        int score = 0;
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("incidentOperational", String.valueOf(operational));
        diagnostics.put("incidentIdentityState", identitySignal.state().name());

        if (!operational || identitySignal.state() == IdentitySignalState.UNAVAILABLE) {
            score += 25;
        } else {
            score += switch (identitySignal.state()) {
                case OWNER_CONFIRMED -> 5;
                case LOW_CONFIDENCE -> 35;
                case UNKNOWN_CONFIRMED -> 70;
                case NO_FACE -> 10;
                case UNAVAILABLE -> 25;
            };
        }

        if (livenessAssessment != null) {
            diagnostics.put("incidentLivenessAvailable", String.valueOf(livenessAssessment.available()));
            diagnostics.put("incidentLivenessPassed", String.valueOf(livenessAssessment.passed()));
            if (livenessAssessment.available() && !livenessAssessment.passed()) {
                score += 20;
            } else if (livenessAssessment.available()) {
                score -= 5;
            }
        }

        if (screenAnalysis != null && screenAnalysis.operational()) {
            diagnostics.put("incidentScreenCategory", screenAnalysis.category().name());
            diagnostics.put("incidentSensitiveScreen", String.valueOf(screenAnalysis.sensitive()));
            if (screenAnalysis.sensitive()) {
                score += 25;
            } else if (screenAnalysis.category() == VisionScreenCategory.DOCUMENT
                    || screenAnalysis.category() == VisionScreenCategory.CHAT
                    || screenAnalysis.category() == VisionScreenCategory.TERMINAL) {
                score += 10;
            }
        } else if (screenAnalysis != null) {
            diagnostics.put("incidentScreenCategory", VisionScreenCategory.UNAVAILABLE.name());
            score += 5;
        }

        score = Math.max(0, Math.min(100, score));
        IncidentDisposition disposition = disposition(score, identitySignal.state(), screenAnalysis);
        diagnostics.put("incidentScore", String.valueOf(score));
        diagnostics.put("incidentDisposition", disposition.name());

        return new SecurityIncidentAssessment(
                disposition,
                score,
                message(disposition),
                diagnostics);
    }

    private static IncidentDisposition disposition(int score,
                                                   IdentitySignalState identityState,
                                                   VisionScreenAnalysisResponse screenAnalysis) {
        boolean sensitive = screenAnalysis != null && screenAnalysis.operational() && screenAnalysis.sensitive();
        if (identityState == IdentitySignalState.UNKNOWN_CONFIRMED && sensitive) {
            return IncidentDisposition.ALERT_IMMEDIATELY;
        }
        if (score >= 75) {
            return IncidentDisposition.HIGH_SEVERITY;
        }
        if (score >= 45) {
            return IncidentDisposition.MEDIUM_SEVERITY;
        }
        if (identityState == IdentitySignalState.NO_FACE || identityState == IdentitySignalState.UNAVAILABLE) {
            return IncidentDisposition.WAIT_FOR_MORE_EVIDENCE;
        }
        return IncidentDisposition.LOW_SEVERITY;
    }

    private static String message(IncidentDisposition disposition) {
        return switch (disposition) {
            case LOW_SEVERITY -> "Low-risk observation";
            case MEDIUM_SEVERITY -> "Medium-risk signal; gather more evidence";
            case HIGH_SEVERITY -> "High-risk signal; incident likely";
            case ALERT_IMMEDIATELY -> "Identity + screen sensitivity justify immediate alerting";
            case WAIT_FOR_MORE_EVIDENCE -> "Signal incomplete; wait for more evidence";
        };
    }
}
