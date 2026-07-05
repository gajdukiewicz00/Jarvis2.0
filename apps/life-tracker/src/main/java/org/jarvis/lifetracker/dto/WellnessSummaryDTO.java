package org.jarvis.lifetracker.dto;

import org.jarvis.lifetracker.domain.WellnessType;

import java.time.LocalDate;

/**
 * Aggregate stats for a numeric wellness metric (weight / mood / steps / sleep / workout)
 * over a date window.
 */
public record WellnessSummaryDTO(
        WellnessType type,
        LocalDate from,
        LocalDate to,
        int entryCount,
        Double average,
        Double min,
        Double max,
        Double latest,
        LocalDate latestDay
) {
}
