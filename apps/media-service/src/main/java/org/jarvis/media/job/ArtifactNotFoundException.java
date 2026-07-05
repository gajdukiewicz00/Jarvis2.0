package org.jarvis.media.job;

/**
 * Thrown when a requested job artifact index is out of range, or the artifact file
 * is no longer present on disk. Mapped to HTTP 404 — never distinguishes "index out
 * of range" from "file missing" in the client-facing message, so a caller cannot
 * probe workspace layout by trial and error.
 */
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(String jobId, int index) {
        super("Artifact not found for job " + jobId + " at index " + index);
    }
}
