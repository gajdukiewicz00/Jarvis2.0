package org.jarvis.pccontrol.securitymonitoring.model;

import java.time.Instant;

public record MonitoringRuntimeState(
        int consecutiveUnknownDetections,
        int consecutiveSuspiciousObservations,
        int consecutiveHighRiskObservations,
        int rollingRiskScore,
        String lastIdentitySignalState,
        String lastScreenCategory,
        Instant lastCheckAt,
        Instant lastAlertAt) {

    public MonitoringRuntimeState {
        consecutiveUnknownDetections = Math.max(0, consecutiveUnknownDetections);
        consecutiveSuspiciousObservations = Math.max(0, consecutiveSuspiciousObservations);
        consecutiveHighRiskObservations = Math.max(0, consecutiveHighRiskObservations);
        rollingRiskScore = Math.max(0, Math.min(100, rollingRiskScore));
        lastIdentitySignalState = lastIdentitySignalState == null ? "" : lastIdentitySignalState;
        lastScreenCategory = lastScreenCategory == null ? "" : lastScreenCategory;
    }

    public static MonitoringRuntimeState initial() {
        return new MonitoringRuntimeState(0, 0, 0, 0, "", "", null, null);
    }
}
