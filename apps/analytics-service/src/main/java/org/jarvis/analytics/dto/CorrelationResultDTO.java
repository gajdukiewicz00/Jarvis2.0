package org.jarvis.analytics.dto;

/**
 * Pearson-style correlation between two daily metric series (e.g. sleep hours
 * vs. tracked work hours). {@code coefficient} is {@code null} when there were
 * too few overlapping days or either series had zero variance.
 */
public record CorrelationResultDTO(
        String metricA,
        String metricB,
        Double coefficient,
        int sampleSize,
        String strength,
        String direction,
        String explanation) {
}
