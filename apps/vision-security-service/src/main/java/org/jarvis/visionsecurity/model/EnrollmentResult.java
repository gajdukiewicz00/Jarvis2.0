package org.jarvis.visionsecurity.model;

import java.time.Instant;

public record EnrollmentResult(
        String userId,
        Instant enrolledAt,
        int sampleCount,
        double ownerThreshold,
        double uncertainThreshold,
        String sampleDirectory
) {
}
