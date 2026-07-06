package org.jarvis.planner.model;

/**
 * #12 — wellness snapshot (sleep, steps, derived energy) used by
 * {@link org.jarvis.planner.service.PlanAdjustmentService} to suggest a
 * {@link PlanMode} for the day. Always non-null with safe neutral defaults
 * when the upstream life-tracker signal is unavailable — see
 * {@link org.jarvis.planner.client.WellnessClient}.
 *
 * @param sleepHours most recent night's sleep duration in hours, or {@code null} if unknown
 * @param steps most recent day's step count, or {@code null} if unknown
 * @param energy best-effort energy estimate derived from the above, defaulting to {@link EnergyLevel#NORMAL}
 */
public record WellnessSignal(Double sleepHours, Integer steps, EnergyLevel energy) {

    /** Neutral, safe-by-default signal used when life-tracker can't be reached. */
    public static WellnessSignal neutralDefault() {
        return new WellnessSignal(null, null, EnergyLevel.NORMAL);
    }
}
