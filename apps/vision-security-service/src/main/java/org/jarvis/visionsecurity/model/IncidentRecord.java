package org.jarvis.visionsecurity.model;

import java.time.Instant;
import java.util.List;

public record IncidentRecord(
        String incidentId,
        String userId,
        Instant createdAt,
        DecisionType decision,
        int faceCount,
        String reason,
        List<String> semanticTags,
        ScreenContextEvidence screenContext,
        StagePaths stagePaths,
        String incidentDirectory,
        String webcamPhotoPath,
        String screenshotPath,
        String ocrTextPath,
        EmailDelivery emailDelivery
) {
}
