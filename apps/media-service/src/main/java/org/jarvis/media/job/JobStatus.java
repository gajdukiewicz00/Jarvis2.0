package org.jarvis.media.job;

/** Lifecycle states of a media job. */
public enum JobStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
