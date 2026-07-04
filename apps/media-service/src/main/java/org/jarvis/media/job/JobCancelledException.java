package org.jarvis.media.job;

/** Thrown cooperatively by a long-running job step when cancellation is requested. */
public class JobCancelledException extends RuntimeException {
    public JobCancelledException() {
        super("cancelled_by_user");
    }
}
