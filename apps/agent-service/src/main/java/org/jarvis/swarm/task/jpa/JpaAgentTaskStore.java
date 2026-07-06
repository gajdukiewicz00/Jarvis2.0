package org.jarvis.swarm.task.jpa;

import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.jarvis.swarm.task.AgentTaskStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Postgres-backed {@link AgentTaskStore}: every lifecycle transition is written through
 * immediately (same write-through contract as {@code FileBackedAgentTaskStore}), so a
 * RUNNING or FAILED task's last known state survives a pod restart. Opt in with
 * {@code jarvis.agent.task-store=postgres}; the file-backed store is the effective
 * default and this bean (along with all JPA/DataSource/Flyway auto-configuration, see
 * {@link PostgresTaskStoreAutoConfiguration}) is otherwise never activated.
 */
@Repository
@ConditionalOnProperty(name = "jarvis.agent.task-store", havingValue = "postgres")
public class JpaAgentTaskStore implements AgentTaskStore {

    private final AgentTaskJpaRepository repository;

    public JpaAgentTaskStore(AgentTaskJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public AgentTask save(AgentTask task) {
        AgentTaskEntity saved = repository.save(AgentTaskEntity.fromDomain(task));
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTask> findById(String id) {
        return repository.findById(id).map(AgentTaskEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> findByUser(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(AgentTaskEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> findBySwarm(String swarmId) {
        return repository.findBySwarmIdOrderByCreatedAtAsc(swarmId).stream()
                .map(AgentTaskEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTask> findByIdempotencyKey(String userId, String idempotencyKey) {
        return repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(AgentTaskEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> findByStatuses(Set<AgentTaskStatus> statuses) {
        return repository.findByStatusIn(statuses).stream()
                .map(AgentTaskEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean deleteById(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }
}
