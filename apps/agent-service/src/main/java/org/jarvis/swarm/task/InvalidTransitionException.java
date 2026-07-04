package org.jarvis.swarm.task;

/** Thrown when an illegal status transition is attempted. Mapped to HTTP 409. */
public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(AgentTaskStatus from, AgentTaskStatus to) {
        super("Invalid task transition: " + from + " -> " + to);
    }
}
