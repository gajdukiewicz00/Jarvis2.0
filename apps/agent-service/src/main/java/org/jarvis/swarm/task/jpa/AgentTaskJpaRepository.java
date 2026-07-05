package org.jarvis.swarm.task.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data JPA repository backing {@link JpaAgentTaskStore}. */
public interface AgentTaskJpaRepository extends JpaRepository<AgentTaskEntity, String> {

    List<AgentTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<AgentTaskEntity> findBySwarmIdOrderByCreatedAtAsc(String swarmId);
}
