package org.jarvis.planner.model;

import java.util.Locale;

/**
 * Explicit, persisted plan-mode selection (distinct from the measured
 * {@link EnergyLevel}) that steers
 * {@link org.jarvis.planner.service.EnergyAwareRanker} toward a particular
 * kind of day. Chosen by the user and kept until changed — unlike energy,
 * which is a transient, frequently-updated signal.
 */
public enum PlanMode {
    /** No additional adjustment beyond energy/priority/deadline scoring. */
    NORMAL,
    /** Favour hard / deep-work tasks, same direction as high energy. */
    DEEP_WORK,
    /** Favour light tasks and avoid heavy ones — a rest/recovery day. */
    RECOVERY,
    /** Favour tasks in {@link TaskCategory#STUDY}. */
    STUDY,
    /** Favour only essential (urgent or overdue) tasks — a bare minimum day. */
    MINIMUM_VIABLE_DAY;

    /** Map free text ("deep work", "deep-work", "study") to a mode, defaulting to NORMAL. */
    public static PlanMode fromText(String s) {
        if (s == null || s.isBlank()) {
            return NORMAL;
        }
        String normalized = s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return PlanMode.valueOf(normalized);
        } catch (IllegalArgumentException ignore) {
            return NORMAL;
        }
    }
}
