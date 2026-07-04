package org.jarvis.swarm.task;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory task store. The MVP avoids a database so the service stays
 * isolated (no migrations, no NetworkPolicy allowlist). Tasks are ephemeral and reset
 * on restart — documented as a known limitation.
 */
@Repository
public class InMemoryAgentTaskStore implements AgentTaskStore {

    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();

    @Override
    public AgentTask save(AgentTask task) {
        tasks.put(task.taskId(), task);
        return task;
    }

    @Override
    public Optional<AgentTask> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public List<AgentTask> findByUser(String userId) {
        return tasks.values().stream()
                .filter(t -> t.userId() != null && t.userId().equals(userId))
                .sorted(Comparator.comparing(AgentTask::createdAt).reversed())
                .toList();
    }

    @Override
    public List<AgentTask> findBySwarm(String swarmId) {
        return tasks.values().stream()
                .filter(t -> swarmId.equals(t.swarmId()))
                .sorted(Comparator.comparing(AgentTask::createdAt))
                .toList();
    }
}
