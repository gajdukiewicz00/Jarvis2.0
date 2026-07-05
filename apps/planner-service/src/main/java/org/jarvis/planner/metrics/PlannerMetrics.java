package org.jarvis.planner.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for planner reschedule and recurring-task-generation
 * activity (exposed via /actuator/prometheus). Mirrors agent-service's
 * SwarmMetrics: thin tagged counters backed by a single injected registry.
 *
 * <p>Backs the {@code planner_reschedules_total{trigger}} and
 * {@code planner_reschedule_deferred_tasks_total} panels documented in
 * {@code observability/README.md}, plus an additional
 * {@code planner_recurring_task_generations_total{rule}} counter for
 * {@link org.jarvis.planner.service.RecurringTaskGenerator}.
 */
@Component
public class PlannerMetrics {

    private final MeterRegistry registry;

    public PlannerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records a "reschedule when tired" pass that actually rescheduled,
     * tagged by what triggered it: {@code exhausted} (energy-driven) or
     * {@code forced} (explicit override).
     */
    public void reschedule(String trigger) {
        registry.counter("planner.reschedules", "trigger", trigger).increment();
    }

    /** Records how many tasks a reschedule pass deferred. */
    public void deferredTasks(int count) {
        if (count > 0) {
            registry.counter("planner.reschedule.deferred.tasks").increment(count);
        }
    }

    /** Records a recurring-task occurrence generation, tagged by recurrence rule. */
    public void recurringTaskGenerated(String rule) {
        registry.counter("planner.recurring.task.generations", "rule", rule).increment();
    }
}
