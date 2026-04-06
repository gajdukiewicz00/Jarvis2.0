package org.jarvis.pccontrol.securitymonitoring.model;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;

import java.util.List;

public record MonitoringCheckReport(
        String trigger,
        VisionVerifyOwnerResponse verificationResult,
        VisionScreenAnalysisResponse screenAnalysisResult,
        MonitoringDecision decision,
        EvidenceBundle evidenceBundle,
        WorkstationIncidentContext incidentContext,
        List<String> warnings) {

    public MonitoringCheckReport {
        trigger = trigger == null || trigger.isBlank() ? "unknown" : trigger;
        incidentContext = incidentContext == null
                ? WorkstationIncidentContext.of(trigger, null, verificationResult, screenAnalysisResult, decision,
                null, null, null, null, null, warnings)
                : incidentContext;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
