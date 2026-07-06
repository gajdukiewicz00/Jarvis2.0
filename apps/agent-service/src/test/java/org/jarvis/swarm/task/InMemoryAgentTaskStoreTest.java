package org.jarvis.swarm.task;

import org.jarvis.swarm.role.AgentRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentTaskStoreTest {

    private final InMemoryAgentTaskStore store = new InMemoryAgentTaskStore();

    private AgentTask task(String id, String userId, Instant createdAt) {
        return AgentTask.created(id, userId, AgentRole.CODER, "goal", Set.of(), false, 1,
                "corr-" + id, null, createdAt);
    }

    @Test
    void findByIdempotencyKeyMatchesOnlyTheOwningUser() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        store.save(task("t1", "u1", now).withIdempotencyKey("key-1"));

        assertThat(store.findByIdempotencyKey("u1", "key-1")).isPresent();
        assertThat(store.findByIdempotencyKey("u2", "key-1")).isEmpty();
        assertThat(store.findByIdempotencyKey("u1", "other-key")).isEmpty();
    }

    @Test
    void findByStatusesReturnsOnlyMatchingTasks() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask completed = task("done", "u1", now).queued(now).running(now)
                .completed("done", List.of(), List.of(), now);
        AgentTask stillQueued = task("queued", "u1", now).queued(now);
        store.save(completed);
        store.save(stillQueued);

        assertThat(store.findByStatuses(Set.of(AgentTaskStatus.COMPLETED)))
                .extracting(AgentTask::taskId).containsExactly("done");
    }

    @Test
    void deleteByIdRemovesTaskAndReportsWhetherOneExisted() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        store.save(task("t1", "u1", now));

        assertThat(store.deleteById("t1")).isTrue();
        assertThat(store.findById("t1")).isEmpty();
        assertThat(store.deleteById("t1")).isFalse();
    }
}
