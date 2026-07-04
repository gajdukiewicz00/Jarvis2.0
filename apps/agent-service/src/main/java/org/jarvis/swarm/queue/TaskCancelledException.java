package org.jarvis.swarm.queue;

/** Thrown cooperatively by a running task step when cancellation is requested. */
public class TaskCancelledException extends RuntimeException {
    public TaskCancelledException() {
        super("cancelled_by_user");
    }
}
