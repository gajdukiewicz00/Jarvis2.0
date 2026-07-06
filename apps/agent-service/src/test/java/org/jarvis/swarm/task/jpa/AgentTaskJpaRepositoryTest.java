package org.jarvis.swarm.task.jpa;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@link AgentTaskEntity} mapping and {@link AgentTaskJpaRepository} query
 * methods against an in-memory H2 database (PostgreSQL compatibility mode) — proves the
 * entity round-trips every field, including the enum/CSV/JSON converters.
 */
@DataJpaTest
@ActiveProfiles("test")
class AgentTaskJpaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AgentTaskJpaRepository repository;

    @Test
    void savesAndReloadsEveryFieldIncludingConvertedCollections() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask task = AgentTask.created("t1", "u1", AgentRole.CODER, "build a thing",
                        Set.of(ToolPermission.READ_FILES, ToolPermission.WRITE_FILES), false, 1,
                        "corr-1", "swarm-1", now)
                .withGranted(Set.of(ToolPermission.WRITE_FILES))
                .withSandbox("/tmp/jarvis-agents/t1")
                .withIdempotencyKey("client-key-1");
        AgentTask running = task.queued(now).running(now);
        AgentTask completed = running.completed("done", List.of("/tmp/a.txt", "/tmp/b.txt"),
                List.of("risk-a"), now.plusSeconds(5));

        repository.save(AgentTaskEntity.fromDomain(completed));
        entityManager.flush();
        entityManager.clear();

        AgentTaskEntity reloaded = repository.findById("t1").orElseThrow();
        AgentTask domain = reloaded.toDomain();

        assertThat(domain.taskId()).isEqualTo("t1");
        assertThat(domain.userId()).isEqualTo("u1");
        assertThat(domain.role()).isEqualTo(AgentRole.CODER);
        assertThat(domain.status()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(domain.permissionsRequested())
                .containsExactlyInAnyOrder(ToolPermission.READ_FILES, ToolPermission.WRITE_FILES);
        assertThat(domain.permissionsGranted()).containsExactly(ToolPermission.WRITE_FILES);
        assertThat(domain.sandboxPath()).isEqualTo("/tmp/jarvis-agents/t1");
        assertThat(domain.artifacts()).containsExactly("/tmp/a.txt", "/tmp/b.txt");
        assertThat(domain.risks()).containsExactly("risk-a");
        assertThat(domain.resultSummary()).isEqualTo("done");
        assertThat(domain.correlationId()).isEqualTo("corr-1");
        assertThat(domain.swarmId()).isEqualTo("swarm-1");
        assertThat(domain.idempotencyKey()).isEqualTo("client-key-1");
    }

    @Test
    void findByUserIdAndIdempotencyKeyFindsTheMatchingRecord() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask task = AgentTask.created("t-idem", "u1", AgentRole.CODER, "build a thing",
                Set.of(), false, 1, "corr-1", "swarm-1", now).withIdempotencyKey("key-1");
        repository.save(AgentTaskEntity.fromDomain(task));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByUserIdAndIdempotencyKey("u1", "key-1")).isPresent();
        assertThat(repository.findByUserIdAndIdempotencyKey("u2", "key-1")).isEmpty();
        assertThat(repository.findByUserIdAndIdempotencyKey("u1", "other-key")).isEmpty();
    }

    @Test
    void findByStatusInReturnsOnlyMatchingStatuses() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask completed = AgentTask.created("done", "u1", AgentRole.CODER, "goal",
                Set.of(), false, 1, "corr-1", null, now).queued(now).running(now)
                .completed("done", List.of(), List.of(), now);
        AgentTask stillQueued = AgentTask.created("queued", "u1", AgentRole.CODER, "goal",
                Set.of(), false, 1, "corr-2", null, now).queued(now);
        repository.save(AgentTaskEntity.fromDomain(completed));
        repository.save(AgentTaskEntity.fromDomain(stillQueued));
        entityManager.flush();
        entityManager.clear();

        List<AgentTaskEntity> found = repository.findByStatusIn(List.of(AgentTaskStatus.COMPLETED));

        assertThat(found).extracting(AgentTaskEntity::getTaskId).containsExactly("done");
    }

    @Test
    void roundTripsEmptyPermissionsAndArtifactsAsEmptyCollectionsNotNull() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask task = AgentTask.created("t-empty", "u1", AgentRole.DOCS, "doc it",
                Set.of(), false, 1, "corr", "swarm", now);

        repository.save(AgentTaskEntity.fromDomain(task));
        entityManager.flush();
        entityManager.clear();

        AgentTask domain = repository.findById("t-empty").orElseThrow().toDomain();
        assertThat(domain.permissionsRequested()).isEmpty();
        assertThat(domain.permissionsGranted()).isEmpty();
        assertThat(domain.artifacts()).isEmpty();
        assertThat(domain.risks()).isEmpty();
    }

    @Test
    void findByUserIdOrdersNewestFirst() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        save("a1", "u1", now);
        save("a2", "u1", now.plusSeconds(10));
        save("a3", "u2", now.plusSeconds(20));
        entityManager.flush();
        entityManager.clear();

        List<AgentTaskEntity> found = repository.findByUserIdOrderByCreatedAtDesc("u1");

        assertThat(found).extracting(AgentTaskEntity::getTaskId).containsExactly("a2", "a1");
    }

    @Test
    void findBySwarmIdOrdersOldestFirst() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        saveWithSwarm("s1", "swarm-x", now);
        saveWithSwarm("s2", "swarm-x", now.plusSeconds(10));
        saveWithSwarm("s3", "swarm-y", now.plusSeconds(20));
        entityManager.flush();
        entityManager.clear();

        List<AgentTaskEntity> found = repository.findBySwarmIdOrderByCreatedAtAsc("swarm-x");

        assertThat(found).extracting(AgentTaskEntity::getTaskId).containsExactly("s1", "s2");
    }

    private void save(String id, String userId, Instant createdAt) {
        AgentTask task = AgentTask.created(id, userId, AgentRole.RESEARCH, "goal",
                Set.of(), false, 1, "corr-" + id, null, createdAt);
        repository.save(AgentTaskEntity.fromDomain(task));
    }

    private void saveWithSwarm(String id, String swarmId, Instant createdAt) {
        AgentTask task = AgentTask.created(id, "u1", AgentRole.RESEARCH, "goal",
                Set.of(), false, 1, "corr-" + id, swarmId, createdAt);
        repository.save(AgentTaskEntity.fromDomain(task));
    }
}
