package org.jarvis.memory.obsidian;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer counters/timer for memory TTL cleanup sweeps and ingest-time dedup
 * rejections (exposed via /actuator/prometheus).
 */
@Component
public class MemoryMetrics {

    private final MeterRegistry registry;

    public MemoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One TTL sweep ({@link MemoryExpiryCleanupService}) completed, tagged by outcome. */
    public void cleanupRun(String status) {
        registry.counter("memory.cleanup.runs", "status", status).increment();
    }

    /** Notes actually forgotten by a TTL sweep, added to the running total. */
    public void cleanupExpired(int count) {
        if (count > 0) {
            registry.counter("memory.cleanup.expired").increment(count);
        }
    }

    public void recordCleanupDuration(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            Timer.builder("memory.cleanup.duration")
                    .description("Memory TTL cleanup sweep duration")
                    .register(registry)
                    .record(duration);
        }
    }

    /**
     * An ingest-time write ({@link MemoryNoteService}) was rejected outright because a
     * content-identical ACTIVE note already exists and the dedup strategy is REJECT.
     */
    public void dedupRejected() {
        registry.counter("memory.dedup.rejected").increment();
    }
}
