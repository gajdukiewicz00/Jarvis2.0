package org.jarvis.analytics.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalyticsMetricsTest {

    private SimpleMeterRegistry registry;
    private AnalyticsMetrics analyticsMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        analyticsMetrics = new AnalyticsMetrics(registry);
    }

    @Test
    void recordJobIncrementsSuccessCounterAndTimerOnNormalCompletion() {
        String result = analyticsMetrics.recordJob("expenses_by_month", () -> "ok");

        assertEquals("ok", result);
        assertEquals(1.0, registry.counter("analytics.jobs", "type", "expenses_by_month", "status", "success").count());
        assertEquals(0.0, registry.counter("analytics.jobs", "type", "expenses_by_month", "status", "error").count());
        assertEquals(1L, registry.find("analytics.job.duration").timer().count());
    }

    @Test
    void recordJobIncrementsErrorCounterAndRethrowsOnRuntimeException() {
        RuntimeException boom = new IllegalStateException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> analyticsMetrics.recordJob("day_score", () -> {
            throw boom;
        }));

        assertEquals(boom, thrown);
        assertEquals(1.0, registry.counter("analytics.jobs", "type", "day_score", "status", "error").count());
        assertEquals(0.0, registry.counter("analytics.jobs", "type", "day_score", "status", "success").count());
        assertEquals(1L, registry.find("analytics.job.duration").timer().count());
    }

    @Test
    void recordJobTagsCountersIndependentlyPerType() {
        analyticsMetrics.recordJob("sleep_summary", () -> 1);
        analyticsMetrics.recordJob("overtime_summary", () -> 2);

        assertEquals(1.0, registry.counter("analytics.jobs", "type", "sleep_summary", "status", "success").count());
        assertEquals(1.0, registry.counter("analytics.jobs", "type", "overtime_summary", "status", "success").count());
        assertEquals(2L, registry.find("analytics.job.duration").timer().count());
    }
}
