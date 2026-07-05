package org.jarvis.analytics.util;

import java.util.List;
import java.util.Optional;

/**
 * Pure statistical helpers used by the insight engine's correlation,
 * consistency, and anomaly-detection features. No I/O, no Spring wiring —
 * safe to unit test directly.
 */
public final class StatsMath {

    private static final double STRONG_THRESHOLD = 0.7;
    private static final double MODERATE_THRESHOLD = 0.4;
    private static final double WEAK_THRESHOLD = 0.2;
    private static final int MIN_PEARSON_PAIRS = 3;

    private StatsMath() {
    }

    public static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /**
     * Population standard deviation (divides by N, not N-1) — callers pass the
     * full observed trailing window, not a sample drawn from a larger population.
     */
    public static double stdDev(List<Double> values) {
        if (values == null || values.size() < 2) {
            return 0.0;
        }
        double m = mean(values);
        double sumSq = 0.0;
        for (double v : values) {
            sumSq += (v - m) * (v - m);
        }
        return Math.sqrt(sumSq / values.size());
    }

    /**
     * Pearson correlation coefficient between two equal-length, index-aligned
     * series. Returns empty when fewer than {@value #MIN_PEARSON_PAIRS} pairs
     * are available or either series has zero variance (undefined correlation).
     */
    public static Optional<Double> pearson(List<Double> xs, List<Double> ys) {
        if (xs == null || ys == null || xs.size() != ys.size() || xs.size() < MIN_PEARSON_PAIRS) {
            return Optional.empty();
        }
        double meanX = mean(xs);
        double meanY = mean(ys);
        double covariance = 0.0;
        double varX = 0.0;
        double varY = 0.0;
        for (int i = 0; i < xs.size(); i++) {
            double dx = xs.get(i) - meanX;
            double dy = ys.get(i) - meanY;
            covariance += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX == 0.0 || varY == 0.0) {
            return Optional.empty();
        }
        return Optional.of(covariance / Math.sqrt(varX * varY));
    }

    /** Human-readable strength label for a Pearson coefficient's absolute value. */
    public static String strengthLabel(double coefficient) {
        double abs = Math.abs(coefficient);
        if (abs >= STRONG_THRESHOLD) {
            return "strong";
        }
        if (abs >= MODERATE_THRESHOLD) {
            return "moderate";
        }
        if (abs >= WEAK_THRESHOLD) {
            return "weak";
        }
        return "none";
    }
}
