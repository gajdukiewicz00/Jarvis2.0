package org.jarvis.swarm.permission;

/** Thrown when the global panic switch is engaged and an action is refused. Mapped to HTTP 423. */
public class PanicEngagedException extends RuntimeException {
    public PanicEngagedException() {
        super("Global panic is engaged; agent actions are blocked");
    }
}
