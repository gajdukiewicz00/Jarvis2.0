package org.jarvis.analytics.dto;

import java.time.LocalDate;

/**
 * A single day whose value fell above {@code mean + k*stdDev} for a tracked
 * daily metric (expenses, sleep hours, work hours), with a human-readable why.
 */
public record AnomalyDTO(
        String metric,
        LocalDate day,
        double value,
        double mean,
        double stdDev,
        String explanation) {
}
