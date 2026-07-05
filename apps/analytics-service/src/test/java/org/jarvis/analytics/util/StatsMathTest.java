package org.jarvis.analytics.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsMathTest {

    @Test
    void meanComputesArithmeticAverage() {
        assertEquals(4.0, StatsMath.mean(List.of(2.0, 4.0, 6.0)));
    }

    @Test
    void meanReturnsZeroForEmptyOrNullInput() {
        assertEquals(0.0, StatsMath.mean(List.of()));
        assertEquals(0.0, StatsMath.mean(null));
    }

    @Test
    void stdDevComputesPopulationStandardDeviation() {
        // Well-known example: population stdDev of [2,4,4,4,5,5,7,9] is exactly 2.0.
        List<Double> values = List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
        assertEquals(2.0, StatsMath.stdDev(values), 0.0001);
    }

    @Test
    void stdDevReturnsZeroForFewerThanTwoValues() {
        assertEquals(0.0, StatsMath.stdDev(List.of(5.0)));
        assertEquals(0.0, StatsMath.stdDev(List.of()));
    }

    @Test
    void pearsonReturnsPerfectPositiveCorrelationForLinearSeries() {
        List<Double> xs = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> ys = List.of(2.0, 4.0, 6.0, 8.0, 10.0);

        Optional<Double> r = StatsMath.pearson(xs, ys);

        assertTrue(r.isPresent());
        assertEquals(1.0, r.get(), 0.0001);
    }

    @Test
    void pearsonReturnsPerfectNegativeCorrelationForInverseSeries() {
        List<Double> xs = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> ys = List.of(10.0, 8.0, 6.0, 4.0, 2.0);

        Optional<Double> r = StatsMath.pearson(xs, ys);

        assertTrue(r.isPresent());
        assertEquals(-1.0, r.get(), 0.0001);
    }

    @Test
    void pearsonReturnsEmptyWhenFewerThanThreePairs() {
        assertTrue(StatsMath.pearson(List.of(1.0, 2.0), List.of(1.0, 2.0)).isEmpty());
    }

    @Test
    void pearsonReturnsEmptyWhenEitherSeriesHasZeroVariance() {
        List<Double> xs = List.of(5.0, 5.0, 5.0, 5.0);
        List<Double> ys = List.of(1.0, 2.0, 3.0, 4.0);

        assertTrue(StatsMath.pearson(xs, ys).isEmpty());
    }

    @Test
    void strengthLabelClassifiesCoefficientMagnitude() {
        assertEquals("strong", StatsMath.strengthLabel(0.85));
        assertEquals("strong", StatsMath.strengthLabel(-0.85));
        assertEquals("moderate", StatsMath.strengthLabel(0.5));
        assertEquals("weak", StatsMath.strengthLabel(0.25));
        assertEquals("none", StatsMath.strengthLabel(0.05));
    }
}
