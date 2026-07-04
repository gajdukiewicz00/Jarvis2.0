package org.jarvis.swarm.run;

/** Thrown when a swarm id is unknown or not owned by the caller. Mapped to HTTP 404. */
public class SwarmNotFoundException extends RuntimeException {
    public SwarmNotFoundException(String id) {
        super("Swarm run not found: " + id);
    }
}
