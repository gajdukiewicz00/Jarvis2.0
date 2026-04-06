package org.jarvis.pccontrol.securitymonitoring.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecision;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecisionState;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringRuntimeState;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class SecurityMonitoringPolicy {

    private final SecurityMonitoringProperties properties;
    private final SecurityRiskScorer securityRiskScorer;

    public MonitoringDecision evaluate(VisionVerifyOwnerResponse verificationResult,
                                       VisionScreenAnalysisResponse screenAnalysisResult,
                                       MonitoringRuntimeState currentState,
                                       Instant now) {
        SecurityRiskScorer.ObservationRiskSummary riskSummary =
                securityRiskScorer.summarize(verificationResult, screenAnalysisResult, currentState);

        if (!verificationResult.operational() && properties.getVision().isSkipOnUnavailable()) {
            return decision(
                    MonitoringDecisionState.UNAVAILABLE,
                    true,
                    false,
                    false,
                    "verification_unavailable",
                    riskSummary,
                    now,
                    nextAlertAllowedAt(currentState),
                    updatedState(currentState, now, riskSummary, false));
        }

        if ("OWNER_CONFIRMED".equals(riskSummary.identitySignalState())) {
            return decision(
                    MonitoringDecisionState.OWNER_CONFIRMED,
                    false,
                    false,
                    false,
                    "owner_verified",
                    riskSummary,
                    now,
                    nextAlertAllowedAt(currentState),
                    new MonitoringRuntimeState(
                            0,
                            0,
                            0,
                            0,
                            riskSummary.identitySignalState(),
                            riskSummary.screenCategory(),
                            now,
                            currentState.lastAlertAt()));
        }

        if ("NO_FACE".equals(riskSummary.identitySignalState())) {
            return decision(
                    MonitoringDecisionState.NO_FACE,
                    false,
                    false,
                    false,
                    "no_face_detected",
                    riskSummary,
                    now,
                    nextAlertAllowedAt(currentState),
                    new MonitoringRuntimeState(
                            0,
                            0,
                            0,
                            riskSummary.rollingRiskScore(),
                            riskSummary.identitySignalState(),
                            riskSummary.screenCategory(),
                            now,
                            currentState.lastAlertAt()));
        }

        MonitoringRuntimeState nextState = updatedState(currentState, now, riskSummary, false);
        Instant nextAllowed = nextAlertAllowedAt(currentState);
        boolean cooldownActive = nextAllowed != null && now.isBefore(nextAllowed);
        boolean alertTriggered = !cooldownActive && shouldAlert(nextState, riskSummary);

        if (alertTriggered) {
            nextState = updatedState(currentState, now, riskSummary, true);
        }

        return decision(
                decisionState(alertTriggered, cooldownActive, riskSummary),
                false,
                alertTriggered,
                cooldownActive,
                reason(alertTriggered, cooldownActive, nextState, riskSummary),
                riskSummary,
                now,
                alertTriggered ? now.plus(properties.getCooldownBetweenAlerts()) : nextAllowed,
                nextState);
    }

    private MonitoringRuntimeState updatedState(MonitoringRuntimeState currentState,
                                                Instant now,
                                                SecurityRiskScorer.ObservationRiskSummary riskSummary,
                                                boolean alertTriggered) {
        int consecutiveUnknowns = "UNKNOWN_CONFIRMED".equals(riskSummary.identitySignalState())
                ? currentState.consecutiveUnknownDetections() + 1
                : 0;
        int suspiciousObservations = riskSummary.suspicious()
                ? currentState.consecutiveSuspiciousObservations() + 1
                : 0;
        int highRiskObservations = riskSummary.highRisk()
                ? currentState.consecutiveHighRiskObservations() + 1
                : 0;
        return new MonitoringRuntimeState(
                consecutiveUnknowns,
                suspiciousObservations,
                highRiskObservations,
                riskSummary.rollingRiskScore(),
                riskSummary.identitySignalState(),
                riskSummary.screenCategory(),
                now,
                alertTriggered ? now : currentState.lastAlertAt());
    }

    private boolean shouldAlert(MonitoringRuntimeState nextState,
                                SecurityRiskScorer.ObservationRiskSummary riskSummary) {
        if (nextState.consecutiveHighRiskObservations() >= properties.getDecision().getHighRiskObservationsRequired()) {
            return true;
        }
        return nextState.consecutiveSuspiciousObservations() >= properties.getConsecutiveUnknownDetectionsRequired()
                && nextState.rollingRiskScore() >= properties.getDecision().getAlertScoreThreshold();
    }

    private String reason(boolean alertTriggered,
                          boolean cooldownActive,
                          MonitoringRuntimeState nextState,
                          SecurityRiskScorer.ObservationRiskSummary riskSummary) {
        if (alertTriggered && nextState.consecutiveHighRiskObservations()
                >= properties.getDecision().getHighRiskObservationsRequired()) {
            return "high_risk_temporal_threshold_reached";
        }
        if (alertTriggered) {
            return "suspicious_observation_threshold_reached";
        }
        if (cooldownActive) {
            return "cooldown_active";
        }
        if (riskSummary.suspicious()) {
            return "awaiting_additional_suspicious_observations";
        }
        return riskSummary.explanation();
    }

    private MonitoringDecisionState decisionState(boolean alertTriggered,
                                                  boolean cooldownActive,
                                                  SecurityRiskScorer.ObservationRiskSummary riskSummary) {
        if (alertTriggered) {
            return MonitoringDecisionState.ALERT_TRIGGERED;
        }
        if (cooldownActive && riskSummary.highRisk()) {
            return MonitoringDecisionState.HIGH_RISK;
        }
        if (riskSummary.highRisk()) {
            return MonitoringDecisionState.HIGH_RISK;
        }
        if (riskSummary.suspicious()) {
            return MonitoringDecisionState.SUSPICIOUS;
        }
        if (riskSummary.degraded()) {
            return MonitoringDecisionState.DEGRADED;
        }
        return MonitoringDecisionState.OBSERVING;
    }

    private MonitoringDecision decision(MonitoringDecisionState state,
                                        boolean skipped,
                                        boolean alertTriggered,
                                        boolean cooldownActive,
                                        String reason,
                                        SecurityRiskScorer.ObservationRiskSummary riskSummary,
                                        Instant now,
                                        Instant nextAlertAllowedAt,
                                        MonitoringRuntimeState nextState) {
        return new MonitoringDecision(
                state,
                skipped,
                alertTriggered,
                cooldownActive,
                reason,
                riskSummary.severity(),
                riskSummary.observationScore(),
                riskSummary.rollingRiskScore(),
                now,
                nextAlertAllowedAt,
                nextState);
    }

    private Instant nextAlertAllowedAt(MonitoringRuntimeState currentState) {
        return currentState.lastAlertAt() == null
                ? null
                : currentState.lastAlertAt().plus(properties.getCooldownBetweenAlerts());
    }
}
