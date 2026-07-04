package org.jarvis.swarm.task;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for agent tasks (in-memory for the MVP; swappable later). */
public interface AgentTaskStore {

    AgentTask save(AgentTask task);

    Optional<AgentTask> findById(String id);

    List<AgentTask> findByUser(String userId);

    List<AgentTask> findBySwarm(String swarmId);
}
