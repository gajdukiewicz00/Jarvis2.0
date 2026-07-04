package org.jarvis.lifetracker.lifemap;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Phase 11 — narrow facade over life-tracker's existing finance store.
 *
 * <p>{@link Provider} is a tiny interface so we don't have to drag the
 * full finance JPA stack into the new package. Production wiring will
 * land a bean that implements it on top of the existing repositories;
 * Pass 1 ships a {@code DefaultEmpty} bean so the summary renders even
 * before the wiring is finished.</p>
 */
public record FinanceTotals(BigDecimal income, BigDecimal expense, BigDecimal budget) {

    public static FinanceTotals empty() {
        return new FinanceTotals(BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    public interface Provider {
        FinanceTotals totalsFor(String userId, LocalDate day);
    }
}
