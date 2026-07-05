package org.jarvis.media.job;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Micrometer counters/timer for media job lifecycle (exposed via /actuator/prometheus). */
@Component
public class MediaJobMetrics {

    private final MeterRegistry registry;

    public MediaJobMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void created(JobType type) {
        count(type, "created");
    }

    public void running(JobType type) {
        count(type, "running");
    }

    public void completed(JobType type) {
        count(type, "completed");
    }

    public void failed(JobType type) {
        count(type, "failed");
    }

    public void cancelled(JobType type) {
        count(type, "cancelled");
    }

    public void recordDuration(JobType type, Duration duration) {
        if (duration != null && !duration.isNegative()) {
            Timer.builder("media.job.duration")
                    .description("Media job execution duration")
                    .tag("type", type.name())
                    .register(registry)
                    .record(duration);
        }
    }

    private void count(JobType type, String status) {
        registry.counter("media.jobs", "type", type.name(), "status", status).increment();
    }
}
