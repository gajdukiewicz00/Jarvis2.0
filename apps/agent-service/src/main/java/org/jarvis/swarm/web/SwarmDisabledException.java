package org.jarvis.swarm.web;

/** Thrown when {@code swarm.enabled=false} and an endpoint is called. Mapped to HTTP 503. */
public class SwarmDisabledException extends RuntimeException {
    public SwarmDisabledException() {
        super("Agent swarm is disabled");
    }
}
