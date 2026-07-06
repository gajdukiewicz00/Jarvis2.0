package org.jarvis.swarm.task;

/**
 * Thrown when a requested task artifact (e.g. DIFF.patch) is not recorded on the task or
 * is no longer present on disk. Mapped to HTTP 404 — never distinguishes "not recorded"
 * from "file missing" in the client-facing message, mirroring media-service's
 * {@code ArtifactNotFoundException} so a caller cannot probe sandbox layout by trial.
 */
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(String taskId, String kind) {
        super("Artifact not found for task " + taskId + ": " + kind);
    }
}
