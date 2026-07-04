package org.jarvis.lifetracker.lifemap;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Phase 11 — DTO container for life-map REST surfaces. Records keep the
 * file small and the wire format stable.
 */
public final class LifeMapDtos {

    private LifeMapDtos() {}

    /** Rolled-up view returned by {@code GET /api/v1/life-map/summary}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DailySummary(
            LocalDate date,
            long totalTrackedSeconds,
            Map<TimeCategory, Long> secondsByCategory,
            BigDecimal financeIncome,
            BigDecimal financeExpense,
            BigDecimal financeBudget,
            int tasksOpen,
            int tasksDoneToday,
            Double sleepHours,
            int visionIncidentsLast24h,
            int jarvisLiveFeedCountLast24h,
            List<ProactiveWarning> warnings
    ) {}

    /** One row of the activity timeline ({@code GET /life-map/activity}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActivityEntry(
            String entryId,
            Instant startedAt,
            Instant endedAt,
            long durationSeconds,
            TimeCategory category,
            String appName,
            String windowTitle,
            String source
    ) {}

    /** Returned by {@code POST /life-map/time-entries}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimeEntryRequest(
            @NotBlank String userId,
            String appName,
            String windowTitle,
            Instant startedAt,
            Instant endedAt,
            Long durationSeconds,
            TimeCategory categoryHint
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProactiveWarning(
            String warningId,
            String code,
            Severity severity,
            String message,
            Map<String, Object> evidence,
            Instant occurredAt
    ) {
        public enum Severity { INFO, WARN, CRITICAL }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecommendationExplanation(
            String warningId,
            String code,
            String rule,
            String narrative,
            Map<String, Object> evidence,
            Instant generatedAt
    ) {}
}
