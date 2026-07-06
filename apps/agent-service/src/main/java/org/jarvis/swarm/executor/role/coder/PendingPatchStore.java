package org.jarvis.swarm.executor.role.coder;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory holding pen for CODER patch proposals awaiting approval, keyed by taskId.
 * Mirrors the lifecycle of {@code AgentTaskService}'s own {@code tokens}/{@code pauses}
 * maps: populated when a task starts, consumed (or discarded) once its human decision
 * arrives. Not persisted — same durability tradeoff as the rest of the in-flight run
 * state, acceptable because a proposal that hasn't been approved has never touched disk.
 */
@Component
public class PendingPatchStore {

    private final ConcurrentHashMap<String, PatchProposal> pending = new ConcurrentHashMap<>();

    /** Stage a proposal for a task awaiting approval. */
    public void stage(String taskId, PatchProposal proposal) {
        pending.put(taskId, proposal);
    }

    /** Remove and return the staged proposal for a task (consumed by an approve). */
    public Optional<PatchProposal> take(String taskId) {
        return Optional.ofNullable(pending.remove(taskId));
    }

    /** Discard a staged proposal without applying it (consumed by a reject). */
    public void discard(String taskId) {
        pending.remove(taskId);
    }

    public boolean hasPending(String taskId) {
        return pending.containsKey(taskId);
    }
}
