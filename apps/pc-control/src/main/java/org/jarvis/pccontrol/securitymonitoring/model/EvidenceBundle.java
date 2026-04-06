package org.jarvis.pccontrol.securitymonitoring.model;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record EvidenceBundle(
        Instant capturedAt,
        Path evidenceDirectory,
        Path webcamImagePath,
        Path screenshotPath,
        Path metadataFilePath,
        WorkstationMetadata workstationMetadata,
        VisionScreenAnalysisResponse screenAnalysisResult,
        int incidentRiskScore,
        String incidentSeverity,
        List<String> warnings) {

    public EvidenceBundle {
        incidentRiskScore = Math.max(0, Math.min(100, incidentRiskScore));
        incidentSeverity = incidentSeverity == null ? "" : incidentSeverity;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
