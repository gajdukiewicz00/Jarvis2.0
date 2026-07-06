package org.jarvis.swarm.task;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence boundary for agent tasks (in-memory for the MVP; swappable later). */
public interface AgentTaskStore {

    AgentTask save(AgentTask task);

    Optional<AgentTask> findById(String id);

    List<AgentTask> findByUser(String userId);

    List<AgentTask> findBySwarm(String swarmId);

    /** Idempotent-replay lookup: a client idempotency key is scoped to a single user. */
    Optional<AgentTask> findByIdempotencyKey(String userId, String idempotencyKey);

    /** Retention sweep candidate set (e.g. every terminal/finished status). */
    List<AgentTask> findByStatuses(Set<AgentTaskStatus> statuses);

    /** Permanently removes a task record. Returns false if no such task existed. */
    boolean deleteById(String id);
}
