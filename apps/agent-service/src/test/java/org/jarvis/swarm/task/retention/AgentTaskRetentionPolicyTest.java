package org.jarvis.swarm.task.retention;

import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskRetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(30L * 86_400); // 30 days

    private AgentTask finished(String id, String userId, AgentTaskStatus status, Instant createdAt) {
        AgentTask created = AgentTask.created(id, userId, AgentRole.CODER, "goal",
                Set.of(), false, 1, "corr-" + id, null, createdAt);
        return switch (status) {
            case COMPLETED -> created.queued(createdAt).running(createdAt)
                    .completed("done", List.of(), List.of(), createdAt);
            case FAILED -> created.queued(createdAt).running(createdAt).failed("boom", createdAt);
            case CANCELLED -> created.cancelled(createdAt);
            default -> throw new IllegalArgumentException("not a finished status: " + status);
        };
    }

    private AgentTask active(String id, String userId, Instant createdAt) {
        return AgentTask.created(id, userId, AgentRole.CODER, "goal", Set.of(), false, 1,
                "corr-" + id, null, createdAt).queued(createdAt);
    }

    @Test
    void emptyInputSelectsNothing() {
        assertThat(AgentTaskRetentionPolicy.selectForDeletion(List.of(), CUTOFF, 10)).isEmpty();
    }

    @Test
    void oldFinishedTaskBeyondKeepCountIsSelected() {
        AgentTask old = finished("t1", "u1", AgentTaskStatus.COMPLETED,
                NOW.minusSeconds(60L * 86_400));

        List<String> selected = AgentTaskRetentionPolicy.selectForDeletion(List.of(old), CUTOFF, 0);

        assertThat(selected).containsExactly("t1");
    }

    @Test
    void recentFinishedTaskIsNeverSelectedRegardlessOfKeepCount() {
        AgentTask recent = finished("t1", "u1", AgentTaskStatus.COMPLETED,
                NOW.minusSeconds(60));

        List<String> selected = AgentTaskRetentionPolicy.selectForDeletion(List.of(recent), CUTOFF, 0);

        assertThat(selected).isEmpty();
    }

    @Test
    void activeTaskIsNeverSelectedEvenWhenVeryOld() {
        AgentTask stillQueued = active("t1", "u1", NOW.minusSeconds(120L * 86_400));

        List<String> selected = AgentTaskRetentionPolicy.selectForDeletion(List.of(stillQueued), CUTOFF, 0);

        assertThat(selected).isEmpty();
    }

    @Test
    void keepsNewestNPerUserRegardlessOfAge() {
        // Three old, finished tasks for the same user; keepPerUser=2 must spare the two newest.
        AgentTask oldest = finished("t1", "u1", AgentTaskStatus.COMPLETED,
                NOW.minusSeconds(90L * 86_400));
        AgentTask middle = finished("t2", "u1", AgentTaskStatus.FAILED,
                NOW.minusSeconds(80L * 86_400));
        AgentTask newest = finished("t3", "u1", AgentTaskStatus.CANCELLED,
                NOW.minusSeconds(70L * 86_400));

        List<String> selected = AgentTaskRetentionPolicy.selectForDeletion(
                List.of(oldest, middle, newest), CUTOFF, 2);

        assertThat(selected).containsExactly("t1");
    }

    @Test
    void retentionIsScopedPerUserNotGlobally() {
        AgentTask userAOld = finished("a1", "userA", AgentTaskStatus.COMPLETED,
                NOW.minusSeconds(90L * 86_400));
        AgentTask userBOld = finished("b1", "userB", AgentTaskStatus.COMPLETED,
                NOW.minusSeconds(90L * 86_400));

        // keepPerUser=1 spares each user's one (and only) old task independently.
        List<String> selected = AgentTaskRetentionPolicy.selectForDeletion(
                List.of(userAOld, userBOld), CUTOFF, 1);

        assertThat(selected).isEmpty();
    }

    @Test
    void negativeKeepPerUserIsTreatedAsZero() {
        AgentTask old = finished("t1", "u1", AgentTaskStatus.COMPLETED,
                NOW.minusSeconds(60L * 86_400));

        List<String> selected = AgentTaskRetentionPolicy.selectForDeletion(List.of(old), CUTOFF, -5);

        assertThat(selected).containsExactly("t1");
    }
}
