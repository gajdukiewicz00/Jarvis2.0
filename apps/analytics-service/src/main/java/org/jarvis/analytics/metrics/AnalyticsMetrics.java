package org.jarvis.analytics.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Micrometer counter/timer for analytics job and insight-computation
 * executions (exposed via /actuator/prometheus), mirroring agent-service's
 * SwarmMetrics: a {@code type}/{@code status}-tagged counter for outcomes
 * plus an untagged timer for duration.
 *
 * <p>Backs the {@code analytics_jobs_total{type,status}} and
 * {@code analytics_job_duration_seconds} panels documented in
 * {@code observability/README.md}.
 */
@Component
public class AnalyticsMetrics {

    private final MeterRegistry registry;
    private final Timer jobDuration;

    public AnalyticsMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.jobDuration = Timer.builder("analytics.job.duration")
                .description("Analytics job / insight computation duration")
                .register(registry);
    }

    /**
     * Runs {@code job}, recording its duration and a success/error outcome
     * tagged by {@code type}. Any {@link RuntimeException} thrown by
     * {@code job} is recorded as an error outcome and rethrown unchanged so
     * callers observe identical behavior to the uninstrumented call.
     */
    public <T> T recordJob(String type, Supplier<T> job) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = job.get();
            count(type, "success");
            return result;
        } catch (RuntimeException e) {
            count(type, "error");
            throw e;
        } finally {
            sample.stop(jobDuration);
        }
    }

    private void count(String type, String status) {
        registry.counter("analytics.jobs", "type", type, "status", status).increment();
    }
}
