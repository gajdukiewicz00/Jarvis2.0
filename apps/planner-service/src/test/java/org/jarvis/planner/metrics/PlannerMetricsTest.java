package org.jarvis.planner.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlannerMetricsTest {

    private SimpleMeterRegistry registry;
    private PlannerMetrics plannerMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        plannerMetrics = new PlannerMetrics(registry);
    }

    @Test
    void rescheduleIncrementsCounterTaggedByTrigger() {
        plannerMetrics.reschedule("exhausted");
        plannerMetrics.reschedule("exhausted");
        plannerMetrics.reschedule("forced");

        assertEquals(2.0, registry.counter("planner.reschedules", "trigger", "exhausted").count());
        assertEquals(1.0, registry.counter("planner.reschedules", "trigger", "forced").count());
    }

    @Test
    void deferredTasksIncrementsCounterByCount() {
        plannerMetrics.deferredTasks(3);
        plannerMetrics.deferredTasks(2);

        assertEquals(5.0, registry.counter("planner.reschedule.deferred.tasks").count());
    }

    @Test
    void deferredTasksDoesNotIncrementForZeroCount() {
        plannerMetrics.deferredTasks(0);

        assertEquals(0.0, registry.counter("planner.reschedule.deferred.tasks").count());
    }

    @Test
    void recurringTaskGeneratedIncrementsCounterTaggedByRule() {
        plannerMetrics.recurringTaskGenerated("DAILY");
        plannerMetrics.recurringTaskGenerated("DAILY");
        plannerMetrics.recurringTaskGenerated("WEEKLY");

        assertEquals(2.0, registry.counter("planner.recurring.task.generations", "rule", "DAILY").count());
        assertEquals(1.0, registry.counter("planner.recurring.task.generations", "rule", "WEEKLY").count());
    }
}
