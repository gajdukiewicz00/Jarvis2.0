package org.jarvis.swarm.task;

import java.util.Set;

/** Lifecycle states of an agent task, with the set of legal forward transitions. */
public enum AgentTaskStatus {
    CREATED,
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    /** FAILED is terminal for a single attempt but may be retried back to QUEUED. */
    public boolean canTransitionTo(AgentTaskStatus target) {
        return switch (this) {
            case CREATED -> Set.of(QUEUED, CANCELLED, FAILED).contains(target);
            // QUEUED may fail before running (panic at start, or queue rejection).
            case QUEUED -> Set.of(RUNNING, PAUSED, CANCELLED, FAILED).contains(target);
            case RUNNING -> Set.of(PAUSED, COMPLETED, FAILED, CANCELLED).contains(target);
            case PAUSED -> Set.of(QUEUED, RUNNING, CANCELLED).contains(target);
            case FAILED -> Set.of(QUEUED).contains(target); // retry only
            case COMPLETED, CANCELLED -> false;             // terminal
        };
    }
}
