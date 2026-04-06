package org.jarvis.pccontrol.securitymonitoring.model;

import org.jarvis.common.vision.VisionVerifyOwnerResponse;

public record AlertPayload(
        String subject,
        String message,
        EvidenceBundle evidenceBundle,
        VisionVerifyOwnerResponse verificationResult,
        WorkstationIncidentContext incidentContext) {
}
