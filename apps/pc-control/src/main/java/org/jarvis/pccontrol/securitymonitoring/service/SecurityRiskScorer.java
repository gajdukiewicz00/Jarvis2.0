package org.jarvis.pccontrol.securitymonitoring.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringRuntimeState;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SecurityRiskScorer {

    private final SecurityMonitoringProperties properties;

    public ObservationRiskSummary summarize(VisionVerifyOwnerResponse verificationResult,
                                            VisionScreenAnalysisResponse screenAnalysisResult,
                                            MonitoringRuntimeState currentState) {
        String identitySignalState = diagnostic(verificationResult.diagnostics(), "identitySignalState", identityFallback(
                verificationResult));
        double identityConfidence = diagnosticDouble(verificationResult.diagnostics(), "identitySignalConfidence", 0.0d);
        boolean livenessAvailable = diagnosticBoolean(verificationResult.diagnostics(), "livenessAvailable", false);
        boolean livenessPassed = diagnosticBoolean(verificationResult.diagnostics(), "livenessPassed", true);
        double livenessConfidence = diagnosticDouble(verificationResult.diagnostics(), "livenessConfidence", 0.0d);
        boolean verificationOperational = verificationResult.operational();

        boolean screenOperational = screenAnalysisResult != null && screenAnalysisResult.operational();
        boolean screenSensitive = screenAnalysisResult != null && screenAnalysisResult.sensitive();
        String screenCategory = screenAnalysisResult == null ? "" : screenAnalysisResult.category().name();
        double screenCategoryConfidence = screenAnalysisResult == null || screenAnalysisResult.categoryConfidence() == null
                ? 0.0d : screenAnalysisResult.categoryConfidence();
        double screenConfidence = screenAnalysisResult == null || screenAnalysisResult.sensitiveConfidence() == null
                ? 0.0d : screenAnalysisResult.sensitiveConfidence();
        boolean ocrReady = screenAnalysisResult != null && screenAnalysisResult.ocrReady();
        boolean degraded = !verificationOperational || (screenAnalysisResult != null && !screenOperational);
        boolean verificationUnavailable = "UNAVAILABLE".equals(identitySignalState);

        int score = 0;
        score += switch (identitySignalState) {
            case "OWNER_CONFIRMED" -> 0;
            case "LOW_CONFIDENCE" -> 35;
            case "UNKNOWN_CONFIRMED" -> 60;
            case "NO_FACE" -> 8;
            case "UNAVAILABLE" -> 18;
            default -> verificationResult.outcome().name().equals("UNKNOWN") ? 45 : 10;
        };

        if (!verificationOperational) {
            score += 10;
        }
        if (livenessAvailable && !livenessPassed) {
            score += 20;
        }
        if (screenOperational && screenSensitive) {
            score += 10 + (int) Math.round(screenConfidence * 12.0d);
        } else if (screenOperational) {
            score += switch (screenCategory) {
                case "TERMINAL" -> 18;
                case "DOCUMENT" -> 16;
                case "CHAT" -> 14;
                case "BROWSER" -> 4;
                case "MEDIA", "UNKNOWN", "UNAVAILABLE" -> 0;
                default -> 2;
            };
            score += (int) Math.round(screenCategoryConfidence * 4.0d);
        } else if (!screenOperational && screenAnalysisResult != null) {
            score += 6;
        }
        if (screenOperational && ocrReady
                && ("DOCUMENT".equals(screenCategory) || "TERMINAL".equals(screenCategory) || "CHAT".equals(screenCategory))) {
            score += 4;
        }

        if (verificationResult.similarity() != null && verificationResult.similarity() < 0.45d) {
            score += 10;
        }
        score = Math.max(0, Math.min(100, score));

        boolean suspicious = score >= properties.getDecision().getSuspiciousScoreThreshold();
        boolean highRisk = score >= properties.getDecision().getHighRiskScoreThreshold();
        int rollingRiskScore = rollingScore(
                currentState == null ? 0 : currentState.rollingRiskScore(),
                score,
                "OWNER_CONFIRMED".equals(identitySignalState),
                "NO_FACE".equals(identitySignalState),
                verificationUnavailable,
                degraded,
                suspicious,
                highRisk);
        String severity = severity(score, rollingRiskScore);
        String explanation = buildExplanation(identitySignalState, livenessAvailable, livenessPassed,
                screenOperational, screenSensitive, screenCategory);

        return new ObservationRiskSummary(
                score,
                rollingRiskScore,
                severity,
                suspicious,
                highRisk,
                identitySignalState,
                identityConfidence,
                livenessAvailable,
                livenessPassed,
                livenessConfidence,
                degraded,
                screenOperational,
                screenSensitive,
                screenCategory,
                screenCategoryConfidence,
                screenConfidence,
                explanation);
    }

    private static int rollingScore(int currentScore,
                                    int observationScore,
                                    boolean ownerConfirmed,
                                    boolean noFace,
                                    boolean unavailable,
                                    boolean degraded,
                                    boolean suspicious,
                                    boolean highRisk) {
        if (ownerConfirmed) {
            return 0;
        }
        if (noFace) {
            return Math.max(0, currentScore - 20);
        }
        if (unavailable) {
            return Math.max(0, currentScore - 18);
        }
        if (degraded && !suspicious && !highRisk) {
            return Math.max(0, currentScore - 14);
        }
        int decayedScore = Math.max(0, currentScore - (highRisk || suspicious ? 12 : 16));
        int contribution = (int) Math.round(observationScore * (highRisk
                ? 0.75d
                : suspicious
                ? 0.60d
                : degraded ? 0.15d : 0.25d));
        int accumulated = Math.max(observationScore, decayedScore + contribution);
        return Math.max(0, Math.min(100, accumulated));
    }

    private String severity(int observationScore, int rollingRiskScore) {
        int effectiveScore = Math.max(observationScore, rollingRiskScore);
        if (effectiveScore >= properties.getDecision().getHighRiskScoreThreshold()) {
            return "CRITICAL";
        }
        if (effectiveScore >= properties.getDecision().getAlertScoreThreshold()) {
            return "HIGH";
        }
        if (effectiveScore >= properties.getDecision().getSuspiciousScoreThreshold()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String buildExplanation(String identitySignalState,
                                           boolean livenessAvailable,
                                           boolean livenessPassed,
                                           boolean screenOperational,
                                           boolean screenSensitive,
                                           String screenCategory) {
        StringBuilder builder = new StringBuilder(identitySignalState.toLowerCase(Locale.ROOT));
        if (livenessAvailable) {
            builder.append(livenessPassed ? "_liveness_passed" : "_liveness_failed");
        }
        if (screenOperational) {
            builder.append(screenSensitive ? "_screen_sensitive" : "_screen_").append(screenCategory.toLowerCase(Locale.ROOT));
        } else {
            builder.append("_screen_unavailable");
        }
        return builder.toString();
    }

    private static String identityFallback(VisionVerifyOwnerResponse verificationResult) {
        return switch (verificationResult.outcome()) {
            case OWNER -> "OWNER_CONFIRMED";
            case UNKNOWN -> "UNKNOWN_CONFIRMED";
            case NO_FACE -> "NO_FACE";
            case UNAVAILABLE -> "UNAVAILABLE";
        };
    }

    private static String diagnostic(Map<String, String> diagnostics, String key, String fallback) {
        if (diagnostics == null) {
            return fallback;
        }
        String value = diagnostics.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean diagnosticBoolean(Map<String, String> diagnostics, String key, boolean fallback) {
        String value = diagnostic(diagnostics, key, Boolean.toString(fallback));
        return Boolean.parseBoolean(value);
    }

    private static double diagnosticDouble(Map<String, String> diagnostics, String key, double fallback) {
        String value = diagnostic(diagnostics, key, Double.toString(fallback));
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public record ObservationRiskSummary(
            int observationScore,
            int rollingRiskScore,
            String severity,
            boolean suspicious,
            boolean highRisk,
            String identitySignalState,
            double identityConfidence,
            boolean livenessAvailable,
            boolean livenessPassed,
            double livenessConfidence,
            boolean degraded,
            boolean screenOperational,
            boolean screenSensitive,
            String screenCategory,
            double screenCategoryConfidence,
            double screenSensitiveConfidence,
            String explanation) {
    }
}
