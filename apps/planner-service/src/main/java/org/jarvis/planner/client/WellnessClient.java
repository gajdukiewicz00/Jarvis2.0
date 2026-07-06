package org.jarvis.planner.client;

import org.jarvis.planner.model.WellnessSignal;

/**
 * #12 — fetches a user's current {@link WellnessSignal} (sleep, steps,
 * derived energy). Implementations MUST degrade gracefully to
 * {@link WellnessSignal#neutralDefault()} rather than throwing when the
 * upstream data source (life-tracker) is unavailable, so callers such as
 * {@link org.jarvis.planner.service.PlanAdjustmentService} never need to
 * treat this as a hard dependency.
 */
public interface WellnessClient {

    WellnessSignal fetchSignal(String userId);
}
