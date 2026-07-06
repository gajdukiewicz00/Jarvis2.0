package org.jarvis.swarm.task.jpa;

import org.jarvis.swarm.task.AgentTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository backing {@link JpaAgentTaskStore}. */
public interface AgentTaskJpaRepository extends JpaRepository<AgentTaskEntity, String> {

    List<AgentTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentTaskEntity> findBySwarmIdOrderByCreatedAtAsc(String swarmId);

    /** Idempotent-replay lookup: one client key is scoped to one user (see V2 migration index). */
    Optional<AgentTaskEntity> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    /** Retention sweep candidate set — backed by the existing {@code idx_agent_task_status} index. */
    List<AgentTaskEntity> findByStatusIn(Collection<AgentTaskStatus> statuses);
}
