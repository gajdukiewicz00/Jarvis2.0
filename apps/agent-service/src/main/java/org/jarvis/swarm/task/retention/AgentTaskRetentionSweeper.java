package org.jarvis.swarm.task.retention;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.swarm.audit.SwarmMetrics;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.queue.AgentTaskService;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStore;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodic sweep of old, finished agent-task records — mirrors the shape of
 * {@code MemoryExpiryCleanupService} (memory-service) and
 * {@code RawFrameRetentionScheduler} (vision-security-service): a scheduled method that
 * delegates the actual decision to a pure, independently-testable selection function
 * ({@link AgentTaskRetentionPolicy}), then performs the deletions through the same
 * {@link AgentTaskStore} abstraction every backend (in-memory, file, Postgres) implements.
 *
 * <p>Disabled mode ({@code swarm.retention.enabled=false}) keeps the bean but exits the
 * sweep early — safe default for tests.</p>
 */
@Slf4j
@Component
@EnableScheduling
public class AgentTaskRetentionSweeper {

    private final AgentTaskStore store;
    private final AgentTaskService taskService;
    private final SwarmProperties props;
    private final Clock clock;
    private final SwarmMetrics metrics;

    public AgentTaskRetentionSweeper(AgentTaskStore store, AgentTaskService taskService,
                                     SwarmProperties props, Clock clock, SwarmMetrics metrics) {
        this.store = store;
        this.taskService = taskService;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${swarm.retention.sweep-interval-ms:3600000}")
    public void sweep() {
        SwarmProperties.Retention retention = props.retention();
        if (!retention.enabled()) {
            log.debug("agent-task retention disabled — skipping sweep");
            return;
        }
        int deleted = sweepOnce(retention);
        if (deleted > 0) {
            log.info("agent-task retention sweep: deleted {} finished task(s) (maxAgeDays={}, keepPerUser={})",
                    deleted, retention.maxAgeDays(), retention.keepPerUser());
        }
    }

    /** Runs one sweep pass and returns the number of records deleted. Visible for tests. */
    int sweepOnce(SwarmProperties.Retention retention) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(retention.maxAgeDays()));
        List<AgentTask> finished = store.findByStatuses(AgentTaskRetentionPolicy.FINISHED_STATUSES);
        List<String> toDelete = AgentTaskRetentionPolicy.selectForDeletion(finished, cutoff, retention.keepPerUser());
        for (String taskId : toDelete) {
            if (store.deleteById(taskId)) {
                taskService.evictResult(taskId);
            }
        }
        metrics.retentionDeleted(toDelete.size());
        return toDelete.size();
    }
}
