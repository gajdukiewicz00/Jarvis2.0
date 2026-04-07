package org.jarvis.visionsecurity.model;

import java.time.Instant;

public record PipelineSnapshotResult(
        String userId,
        Instant createdAt,
        String outputDirectory,
        PipelineResult pipeline
) {
}
