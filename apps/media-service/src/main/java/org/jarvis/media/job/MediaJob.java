package org.jarvis.media.job;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable media job record. State transitions never mutate — each returns a new
 * instance with an updated status, artifacts, and {@code updatedAt} timestamp.
 *
 * @param details additive, type-specific structured payload (probe streams,
 *                subtitle warnings, quality report). Never holds raw secrets.
 */
public record MediaJob(
        String id,
        String userId,
        JobType type,
        JobStatus status,
        String inputFile,
        List<JobArtifact> outputFiles,
        Instant createdAt,
        Instant updatedAt,
        String errorMessage,
        Map<String, Object> details) {

    public MediaJob {
        outputFiles = outputFiles == null ? List.of() : List.copyOf(outputFiles);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static MediaJob created(String id, String userId, JobType type, String inputFile, Instant now) {
        return new MediaJob(id, userId, type, JobStatus.CREATED, inputFile, List.of(), now, now, null, Map.of());
    }

    public MediaJob running(Instant now) {
        return new MediaJob(id, userId, type, JobStatus.RUNNING, inputFile, outputFiles, createdAt, now, null, details);
    }

    public MediaJob completed(List<JobArtifact> artifacts, Map<String, Object> det, Instant now) {
        return new MediaJob(id, userId, type, JobStatus.COMPLETED, inputFile, artifacts, createdAt, now, null, det);
    }

    public MediaJob failed(String message, Instant now) {
        return new MediaJob(id, userId, type, JobStatus.FAILED, inputFile, outputFiles, createdAt, now, message, details);
    }

    public MediaJob cancelled(Instant now) {
        return new MediaJob(id, userId, type, JobStatus.CANCELLED, inputFile, outputFiles, createdAt, now,
                "cancelled_by_user", details);
    }
}
