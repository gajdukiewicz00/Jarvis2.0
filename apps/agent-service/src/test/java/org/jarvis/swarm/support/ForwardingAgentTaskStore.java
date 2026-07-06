package org.jarvis.swarm.support;

import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.jarvis.swarm.task.AgentTaskStore;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Delegates every {@link AgentTaskStore} method to a wrapped store. Tests subclass this
 * and override only the method(s) they need to instrument (e.g. to inject deterministic
 * timing control for concurrency tests) instead of re-implementing the whole interface.
 */
public class ForwardingAgentTaskStore implements AgentTaskStore {

    private final AgentTaskStore delegate;

    public ForwardingAgentTaskStore(AgentTaskStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public AgentTask save(AgentTask task) {
        return delegate.save(task);
    }

    @Override
    public Optional<AgentTask> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public List<AgentTask> findByUser(String userId) {
        return delegate.findByUser(userId);
    }

    @Override
    public List<AgentTask> findBySwarm(String swarmId) {
        return delegate.findBySwarm(swarmId);
    }

    @Override
    public Optional<AgentTask> findByIdempotencyKey(String userId, String idempotencyKey) {
        return delegate.findByIdempotencyKey(userId, idempotencyKey);
    }

    @Override
    public List<AgentTask> findByStatuses(Set<AgentTaskStatus> statuses) {
        return delegate.findByStatuses(statuses);
    }

    @Override
    public boolean deleteById(String id) {
        return delegate.deleteById(id);
    }
}
