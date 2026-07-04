package org.jarvis.media.web;

import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobStatus;
import org.jarvis.media.job.JobType;
import org.jarvis.media.job.MediaJob;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API view of a media job. */
public record JobView(
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

    public static JobView from(MediaJob job) {
        return new JobView(
                job.id(), job.userId(), job.type(), job.status(), job.inputFile(),
                job.outputFiles(), job.createdAt(), job.updatedAt(), job.errorMessage(), job.details());
    }
}
