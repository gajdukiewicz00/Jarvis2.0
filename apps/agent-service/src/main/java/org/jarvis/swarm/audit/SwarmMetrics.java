package org.jarvis.swarm.audit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Micrometer counters/timer for swarm task lifecycle (exposed via /actuator/prometheus). */
@Component
public class SwarmMetrics {

    private final MeterRegistry registry;
    private final Timer taskDuration;

    public SwarmMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.taskDuration = Timer.builder("swarm.task.duration")
                .description("Agent task execution duration")
                .register(registry);
    }

    public void created() {
        count("created");
    }

    public void running() {
        count("running");
    }

    public void completed() {
        count("completed");
    }

    public void failed() {
        count("failed");
    }

    public void cancelled() {
        count("cancelled");
    }

    public void recordDuration(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            taskDuration.record(duration);
        }
    }

    private void count(String state) {
        registry.counter("swarm.tasks", "state", state).increment();
    }
}
