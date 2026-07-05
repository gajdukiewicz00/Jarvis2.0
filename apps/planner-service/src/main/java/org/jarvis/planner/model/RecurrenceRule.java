package org.jarvis.planner.model;

/**
 * RRULE-lite recurrence pattern for a recurring task template. Intentionally
 * small (no RFC 5545 parsing) — just enough to cover daily assistant needs.
 */
public enum RecurrenceRule {
    /** Not a recurring template — a normal, one-off task. */
    NONE,
    /** Generates a new occurrence every calendar day from the anchor date. */
    DAILY,
    /** Generates a new occurrence on the same day-of-week as the anchor date. */
    WEEKLY,
    /** Generates a new occurrence every {@code recurrenceIntervalDays} days from the anchor date. */
    INTERVAL
}
