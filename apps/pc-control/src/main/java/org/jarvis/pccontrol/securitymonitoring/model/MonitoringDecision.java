package org.jarvis.pccontrol.securitymonitoring.model;

import java.time.Instant;

public record MonitoringDecision(
        MonitoringDecisionState state,
        boolean skipped,
        boolean alertTriggered,
        boolean cooldownActive,
        String reason,
        String severity,
        int observationRiskScore,
        int rollingRiskScore,
        Instant evaluatedAt,
        Instant nextAlertAllowedAt,
        MonitoringRuntimeState nextState) {

    public MonitoringDecision {
        state = state == null ? MonitoringDecisionState.OBSERVING : state;
        reason = reason == null ? "" : reason;
        severity = severity == null ? "" : severity;
        observationRiskScore = Math.max(0, Math.min(100, observationRiskScore));
        rollingRiskScore = Math.max(0, Math.min(100, rollingRiskScore));
    }
}
