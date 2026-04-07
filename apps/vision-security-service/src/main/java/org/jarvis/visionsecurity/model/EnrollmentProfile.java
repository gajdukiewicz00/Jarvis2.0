package org.jarvis.visionsecurity.model;

import java.time.Instant;

public record EnrollmentProfile(
        String userId,
        Instant enrolledAt,
        int sampleCount,
        double ownerThreshold,
        double uncertainThreshold,
        String sampleDirectory
) {
}
