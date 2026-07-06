package org.jarvis.swarm.task.jpa;

import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JpaAgentTaskStore}'s AgentTask &lt;-&gt; AgentTaskEntity mapping
 * and delegation, with the Spring Data repository mocked (no database needed).
 */
class JpaAgentTaskStoreTest {

    private final AgentTaskJpaRepository repository = mock(AgentTaskJpaRepository.class);
    private final JpaAgentTaskStore store = new JpaAgentTaskStore(repository);

    private AgentTask sampleTask(String id, Instant now) {
        return AgentTask.created(id, "u1", AgentRole.CODER, "build a thing",
                Set.of(), false, 1, "corr-1", "swarm-1", now);
    }

    @Test
    void saveConvertsToEntitySavesThenConvertsBack() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask task = sampleTask("t1", now);

        when(repository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentTask saved = store.save(task);

        assertThat(saved.taskId()).isEqualTo("t1");
        assertThat(saved.userId()).isEqualTo("u1");
        assertThat(saved.role()).isEqualTo(AgentRole.CODER);
        verify(repository).save(any(AgentTaskEntity.class));
    }

    @Test
    void findByIdReturnsEmptyWhenRepositoryReturnsEmpty() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThat(store.findById("missing")).isEmpty();
    }

    @Test
    void findByIdMapsEntityBackToDomain() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTaskEntity entity = AgentTaskEntity.fromDomain(sampleTask("t2", now));
        when(repository.findById("t2")).thenReturn(Optional.of(entity));

        Optional<AgentTask> found = store.findById("t2");

        assertThat(found).isPresent();
        assertThat(found.get().taskId()).isEqualTo("t2");
    }

    @Test
    void findByUserDelegatesToOrderedRepositoryQuery() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        List<AgentTaskEntity> entities = List.of(
                AgentTaskEntity.fromDomain(sampleTask("t1", now)),
                AgentTaskEntity.fromDomain(sampleTask("t2", now.plusSeconds(1))));
        when(repository.findByUserIdOrderByCreatedAtDesc(eq("u1"))).thenReturn(entities);

        List<AgentTask> found = store.findByUser("u1");

        assertThat(found).extracting(AgentTask::taskId).containsExactly("t1", "t2");
        verify(repository).findByUserIdOrderByCreatedAtDesc("u1");
    }

    @Test
    void findBySwarmDelegatesToOrderedRepositoryQuery() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        List<AgentTaskEntity> entities = List.of(AgentTaskEntity.fromDomain(sampleTask("t1", now)));
        when(repository.findBySwarmIdOrderByCreatedAtAsc(eq("swarm-1"))).thenReturn(entities);

        List<AgentTask> found = store.findBySwarm("swarm-1");

        assertThat(found).extracting(AgentTask::taskId).containsExactly("t1");
        verify(repository).findBySwarmIdOrderByCreatedAtAsc("swarm-1");
    }

    @Test
    void findByIdempotencyKeyDelegatesToRepositoryAndMapsBack() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTaskEntity entity = AgentTaskEntity.fromDomain(sampleTask("t1", now).withIdempotencyKey("key-1"));
        when(repository.findByUserIdAndIdempotencyKey("u1", "key-1")).thenReturn(Optional.of(entity));

        Optional<AgentTask> found = store.findByIdempotencyKey("u1", "key-1");

        assertThat(found).isPresent();
        assertThat(found.get().idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void findByIdempotencyKeyReturnsEmptyWhenRepositoryReturnsEmpty() {
        when(repository.findByUserIdAndIdempotencyKey("u1", "missing")).thenReturn(Optional.empty());

        assertThat(store.findByIdempotencyKey("u1", "missing")).isEmpty();
    }

    @Test
    void findByStatusesDelegatesToRepository() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        List<AgentTaskEntity> entities = List.of(AgentTaskEntity.fromDomain(sampleTask("t1", now)));
        when(repository.findByStatusIn(anyCollection())).thenReturn(entities);

        List<AgentTask> found = store.findByStatuses(Set.of(AgentTaskStatus.COMPLETED, AgentTaskStatus.FAILED));

        assertThat(found).extracting(AgentTask::taskId).containsExactly("t1");
    }

    @Test
    void deleteByIdReturnsFalseWhenTaskDoesNotExist() {
        when(repository.existsById("missing")).thenReturn(false);

        assertThat(store.deleteById("missing")).isFalse();
    }

    @Test
    void deleteByIdDeletesThenReturnsTrueWhenTaskExists() {
        when(repository.existsById("t1")).thenReturn(true);

        assertThat(store.deleteById("t1")).isTrue();
        verify(repository).deleteById("t1");
    }
}
