package org.jarvis.swarm.task.retention;

import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure selection logic for the agent-task retention sweep — kept separate from
 * {@link AgentTaskRetentionSweeper} so the policy is unit-testable without a store or a
 * scheduler.
 *
 * <p>A task is only ever a deletion candidate once it has reached a finished status
 * (never RUNNING/QUEUED/PAUSED/CREATED — those may still be in flight). Within a user's
 * finished tasks, the newest {@code keepPerUser} are always kept regardless of age, so a
 * user who only ran a handful of old tasks never loses their entire history; everything
 * else older than {@code cutoff} (by {@link AgentTask#createdAt()}) is selected.</p>
 */
public final class AgentTaskRetentionPolicy {

    /** Statuses eligible for retention cleanup — never an active/in-flight task. */
    public static final Set<AgentTaskStatus> FINISHED_STATUSES =
            Set.of(AgentTaskStatus.COMPLETED, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED);

    private AgentTaskRetentionPolicy() {
    }

    /**
     * @param tasks       candidate tasks (callers typically pre-filter to finished statuses
     *                    via {@link #FINISHED_STATUSES}, but this method re-filters defensively)
     * @param cutoff      tasks created before this instant are eligible for deletion
     * @param keepPerUser newest N finished tasks kept per user regardless of age (values &lt;= 0 keep none)
     * @return task ids selected for deletion, in no particular order
     */
    public static List<String> selectForDeletion(List<AgentTask> tasks, Instant cutoff, int keepPerUser) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        int keep = Math.max(keepPerUser, 0);
        Map<String, List<AgentTask>> byUser = tasks.stream()
                .filter(t -> FINISHED_STATUSES.contains(t.status()))
                .collect(Collectors.groupingBy(AgentTask::userId));

        List<String> toDelete = new ArrayList<>();
        for (List<AgentTask> userTasks : byUser.values()) {
            List<AgentTask> newestFirst = userTasks.stream()
                    .sorted(Comparator.comparing(AgentTask::createdAt).reversed())
                    .toList();
            for (int i = keep; i < newestFirst.size(); i++) {
                AgentTask candidate = newestFirst.get(i);
                if (candidate.createdAt().isBefore(cutoff)) {
                    toDelete.add(candidate.taskId());
                }
            }
        }
        return List.copyOf(toDelete);
    }
}
