package org.jarvis.swarm.task;

import java.util.Set;

/** Lifecycle states of an agent task, with the set of legal forward transitions. */
public enum AgentTaskStatus {
    CREATED,
    QUEUED,
    RUNNING,
    PAUSED,
    /** A CODER patch proposal has been built but not applied — see AgentTaskService#approve/#reject. */
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED;

    /**
     * True for any status that ends a single attempt: COMPLETED, CANCELLED, and FAILED.
     * FAILED is included because callers like {@link org.jarvis.swarm.queue.AgentTaskService#cancel}
     * must treat an already-FAILED task as a no-op rather than attempting the illegal
     * FAILED -&gt; CANCELLED transition. A separate retry path (FAILED -&gt; QUEUED, see
     * {@link #canTransitionTo}) is still allowed for a single attempt's failure.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    /** FAILED is terminal for a single attempt but may be retried back to QUEUED. */
    public boolean canTransitionTo(AgentTaskStatus target) {
        return switch (this) {
            case CREATED -> Set.of(QUEUED, CANCELLED, FAILED).contains(target);
            // QUEUED may fail before running (panic at start, or queue rejection).
            case QUEUED -> Set.of(RUNNING, PAUSED, CANCELLED, FAILED).contains(target);
            case RUNNING -> Set.of(PAUSED, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED).contains(target);
            case PAUSED -> Set.of(QUEUED, RUNNING, CANCELLED).contains(target);
            // approve() -> COMPLETED (applied to sandbox); reject() -> CANCELLED (discarded).
            case AWAITING_APPROVAL -> Set.of(COMPLETED, CANCELLED).contains(target);
            case FAILED -> Set.of(QUEUED).contains(target); // retry only
            case COMPLETED, CANCELLED -> false;             // terminal
        };
    }
}
