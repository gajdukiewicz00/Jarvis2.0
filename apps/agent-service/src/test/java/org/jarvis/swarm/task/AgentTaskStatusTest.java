package org.jarvis.swarm.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTaskStatusTest {

    @Test
    void validForwardTransitionsAreAllowed() {
        assertThat(AgentTaskStatus.CREATED.canTransitionTo(AgentTaskStatus.QUEUED)).isTrue();
        assertThat(AgentTaskStatus.QUEUED.canTransitionTo(AgentTaskStatus.RUNNING)).isTrue();
        assertThat(AgentTaskStatus.RUNNING.canTransitionTo(AgentTaskStatus.PAUSED)).isTrue();
        assertThat(AgentTaskStatus.PAUSED.canTransitionTo(AgentTaskStatus.RUNNING)).isTrue();
        assertThat(AgentTaskStatus.RUNNING.canTransitionTo(AgentTaskStatus.COMPLETED)).isTrue();
        assertThat(AgentTaskStatus.FAILED.canTransitionTo(AgentTaskStatus.QUEUED)).isTrue(); // retry
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertThat(AgentTaskStatus.COMPLETED.canTransitionTo(AgentTaskStatus.RUNNING)).isFalse();
        assertThat(AgentTaskStatus.CANCELLED.canTransitionTo(AgentTaskStatus.QUEUED)).isFalse();
        assertThat(AgentTaskStatus.CREATED.canTransitionTo(AgentTaskStatus.COMPLETED)).isFalse();
    }

    @Test
    void modelEnforcesTransitionsAndThrowsOnIllegalMove() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask task = AgentTask.created("t1", "u1",
                org.jarvis.swarm.role.AgentRole.DOCS, "doc it", Set.of(), false, 1, "c", null, now);

        AgentTask completed = task.queued(now).running(now).completed("done", java.util.List.of(),
                java.util.List.of(), now);
        assertThat(completed.status()).isEqualTo(AgentTaskStatus.COMPLETED);

        // can't pause a completed task
        assertThatThrownBy(() -> completed.paused(now)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void runningStampsStartedAtOnceAndCompletedStampsFinishedAt() {
        Instant t0 = Instant.parse("2026-06-24T10:00:00Z");
        Instant t1 = Instant.parse("2026-06-24T10:00:05Z");
        AgentTask running = AgentTask.created("t1", "u1",
                        org.jarvis.swarm.role.AgentRole.CODER, "g", Set.of(), false, 1, "c", null, t0)
                .queued(t0).running(t1);
        assertThat(running.startedAt()).isEqualTo(t1);
        AgentTask done = running.completed("ok", java.util.List.of(), java.util.List.of(), t1);
        assertThat(done.finishedAt()).isEqualTo(t1);
        assertThat(done.startedAt()).isEqualTo(t1);
    }
}
