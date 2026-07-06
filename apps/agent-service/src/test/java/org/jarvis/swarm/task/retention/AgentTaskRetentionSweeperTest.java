package org.jarvis.swarm.task.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.swarm.audit.SwarmMetrics;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.queue.AgentTaskService;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStore;
import org.jarvis.swarm.task.InMemoryAgentTaskStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** Wiring tests for the sweeper — selection-logic edge cases live in {@link AgentTaskRetentionPolicyTest}. */
class AgentTaskRetentionSweeperTest {

    private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");

    private final AgentTaskStore store = new InMemoryAgentTaskStore();
    private final AgentTaskService taskService = Mockito.mock(AgentTaskService.class);
    private final SwarmMetrics metrics = new SwarmMetrics(new SimpleMeterRegistry());
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private AgentTask oldCompletedTask(String id, String userId, Instant createdAt) {
        AgentTask created = AgentTask.created(id, userId, AgentRole.CODER, "goal",
                Set.of(), false, 1, "corr-" + id, null, createdAt);
        return created.queued(createdAt).running(createdAt).completed("done", java.util.List.of(), java.util.List.of(), createdAt);
    }

    @Test
    void disabledRetentionDeletesNothing() {
        store.save(oldCompletedTask("t1", "u1", NOW.minusSeconds(90L * 86_400)));
        SwarmProperties.Retention disabled = new SwarmProperties.Retention(false, 30, 0, 3_600_000L);
        AgentTaskRetentionSweeper sweeper = new AgentTaskRetentionSweeper(store, taskService,
                propsWith(disabled), clock, metrics);

        sweeper.sweep();

        assertThat(store.findById("t1")).isPresent();
    }

    @Test
    void enabledSweepDeletesOldFinishedTaskAndEvictsCachedResult() {
        store.save(oldCompletedTask("t1", "u1", NOW.minusSeconds(90L * 86_400)));
        SwarmProperties.Retention enabled = new SwarmProperties.Retention(true, 30, 0, 3_600_000L);
        AgentTaskRetentionSweeper sweeper = new AgentTaskRetentionSweeper(store, taskService,
                propsWith(enabled), clock, metrics);

        int deleted = sweeper.sweepOnce(enabled);

        assertThat(deleted).isEqualTo(1);
        assertThat(store.findById("t1")).isEmpty();
        verify(taskService).evictResult("t1");
    }

    @Test
    void sweepKeepsRecentAndActiveTasks() {
        store.save(oldCompletedTask("old", "u1", NOW.minusSeconds(90L * 86_400)));
        AgentTask recent = AgentTask.created("recent", "u1", AgentRole.CODER, "goal",
                Set.of(), false, 1, "corr-recent", null, NOW.minusSeconds(60));
        store.save(recent.queued(NOW));
        SwarmProperties.Retention retention = new SwarmProperties.Retention(true, 30, 0, 3_600_000L);
        AgentTaskRetentionSweeper sweeper = new AgentTaskRetentionSweeper(store, taskService,
                propsWith(retention), clock, metrics);

        int deleted = sweeper.sweepOnce(retention);

        assertThat(deleted).isEqualTo(1);
        assertThat(store.findById("recent")).isPresent();
    }

    private SwarmProperties propsWith(SwarmProperties.Retention retention) {
        return new SwarmProperties(true,
                new SwarmProperties.Workspace("/tmp/jarvis-agents-test", ""),
                new SwarmProperties.Queue(64, 3),
                new SwarmProperties.Task(120, 1),
                new SwarmProperties.SwarmRun(10, 7),
                retention);
    }
}
