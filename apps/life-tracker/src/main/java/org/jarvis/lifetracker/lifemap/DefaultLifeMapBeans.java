package org.jarvis.lifetracker.lifemap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 11 — fallback beans so the life-map module renders something even
 * before Phase 12 wires it to the real finance repositories / health
 * providers. Production deployments override these with concrete beans.
 */
@Configuration
public class DefaultLifeMapBeans {

    @Bean
    @ConditionalOnMissingBean(FinanceTotals.Provider.class)
    public FinanceTotals.Provider emptyFinanceProvider() {
        return (userId, day) -> FinanceTotals.empty();
    }

    @Bean
    @ConditionalOnMissingBean(DailySummaryService.SleepProvider.class)
    public DailySummaryService.SleepProvider emptySleepProvider() {
        return new DailySummaryService.SleepProvider() {};
    }
}
