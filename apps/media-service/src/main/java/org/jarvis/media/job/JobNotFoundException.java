package org.jarvis.media.job;

/** Thrown when a job id is unknown or not owned by the caller. Mapped to HTTP 404. */
public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String id) {
        super("Media job not found: " + id);
    }
}
