package org.jarvis.visionsecurity.model;

import java.time.Instant;

public record VisionSecurityStatus(
        String serviceStatus,
        boolean monitoringEnabled,
        String activeUserId,
        boolean ownerEnrolled,
        DecisionType lastDecision,
        Instant lastDecisionAt,
        String lastReason,
        int lastFaceCount,
        int unknownStreak,
        String lastIncidentId,
        int incidentCount,
        CapabilityStatus camera,
        CapabilityStatus screenshot,
        CapabilityStatus ocr,
        CapabilityStatus email,
        GpuStatus gpu,
        VisionSecurityConfigView config
) {
}
