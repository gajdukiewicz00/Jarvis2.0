package org.jarvis.lifetracker.lifemap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Phase 11 — assembles the per-day life-map summary.
 *
 * <p>Source of truth for time / finance is the life-tracker store (Pass 1
 * uses {@link InMemoryActivityStore} and an injectable
 * {@link FinanceTotals.Provider}). Tasks / vision / memory metrics come
 * from {@link CrossServiceClient}; failures degrade to zeros without
 * crashing the summary.</p>
 *
 * <p>Sleep is read from a pluggable {@link SleepProvider}; Pass 1 ships
 * an empty default — Phase 12 wires Google Fit / Health Connect / Samsung
 * Health here.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private final InMemoryActivityStore activityStore;
    private final CrossServiceClient cross;
    private final ProactiveWarningEngine warningEngine;
    private final FinanceTotals.Provider financeProvider;
    private final SleepProvider sleepProvider;

    public LifeMapDtos.DailySummary summarise(String userId, LocalDate day) {
        LocalDate effective = day == null ? LocalDate.now() : day;
        Map<TimeCategory, Long> byCategory = activityStore.secondsByCategoryForDay(userId, effective);
        long total = byCategory.values().stream().mapToLong(Long::longValue).sum();

        FinanceTotals finance = financeProvider.totalsFor(userId, effective);
        CrossServiceClient.Tasks tasks = cross.fetchTasks(userId);
        int incidents = cross.fetchVisionIncidentCount(userId);
        int memoryWrites = cross.fetchMemoryWriteCount(userId);
        Double sleep = sleepProvider.lastNightHours(userId, effective);

        LifeMapDtos.DailySummary preview = new LifeMapDtos.DailySummary(
                effective, total, byCategory,
                finance.income(), finance.expense(), finance.budget(),
                tasks.open(), tasks.doneToday(),
                sleep, incidents, memoryWrites,
                java.util.List.of());

        var warnings = warningEngine.evaluate(preview);
        return new LifeMapDtos.DailySummary(
                preview.date(), preview.totalTrackedSeconds(),
                preview.secondsByCategory(),
                preview.financeIncome(), preview.financeExpense(), preview.financeBudget(),
                preview.tasksOpen(), preview.tasksDoneToday(),
                preview.sleepHours(),
                preview.visionIncidentsLast24h(),
                preview.jarvisLiveFeedCountLast24h(),
                warnings);
    }

    /** Pluggable finance accessor — wired by tests + production beans. */
    public interface FinanceTotalsProvider {
        FinanceTotals totalsFor(String userId, LocalDate day);
    }

    /** Pluggable sleep accessor — Pass 1 default returns empty. */
    public interface SleepProvider {
        default Double lastNightHours(String userId, LocalDate day) { return null; }
    }
}
