package org.jarvis.analytics.controller;

import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.*;
import org.jarvis.analytics.service.AnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Analytics controller providing aggregated data from life-tracker.
 * 
 * Handles upstream errors gracefully:
 * - Returns partial data when possible (overview endpoint)
 * - Propagates errors with context for other endpoints
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final LifeTrackerClient lifeTrackerClient;
    private final AnalyticsService analyticsService;

    /**
     * Get analytics overview with expense and time summaries.
     * This endpoint returns partial data even if some upstream calls fail.
     */
    @GetMapping("/overview")
    public ResponseEntity<AnalyticsOverviewDTO> getOverview() {
        log.info("Generating analytics overview");
        AnalyticsOverviewDTO.AnalyticsOverviewDTOBuilder builder = AnalyticsOverviewDTO.builder();

        // Fetch expenses with error handling
        try {
            List<ExpenseDTO> expenses = lifeTrackerClient.getExpenses();
            if (expenses != null && !expenses.isEmpty()) {
                List<ExpenseDTO> filtered = expenses.stream()
                        .filter(expense -> expense.getType() == null
                                || "EXPENSE".equalsIgnoreCase(expense.getType()))
                        .toList();
                BigDecimal totalExpenses = filtered.stream()
                        .map(ExpenseDTO::getAmount)
                        .filter(amount -> amount != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                builder.totalExpenses(totalExpenses);
                builder.expenseCount(filtered.size());
            } else {
                builder.totalExpenses(BigDecimal.ZERO);
                builder.expenseCount(0);
            }
        } catch (RetryableException e) {
            log.warn("Timeout fetching expenses from life-tracker: {}", e.getMessage());
            builder.expensesError("Life-tracker service timeout");
        } catch (FeignException e) {
            log.warn("Failed to fetch expenses from life-tracker [{}]: {}", 
                    e.status(), e.getMessage());
            builder.expensesError("Life-tracker service error: " + e.status());
        } catch (Exception e) {
            log.error("Unexpected error fetching expenses: {}", e.getMessage());
            builder.expensesError("Unexpected error fetching expenses");
        }

        // Fetch time records with error handling
        try {
            List<TimeRecordDTO> timeRecords = lifeTrackerClient.getTimeRecords();
            if (timeRecords != null && !timeRecords.isEmpty()) {
                long totalDuration = timeRecords.stream()
                        .mapToLong(TimeRecordDTO::getDurationSeconds)
                        .sum();
                builder.totalTimeTrackedSeconds(totalDuration);
                builder.timeRecordCount(timeRecords.size());
            } else {
                builder.totalTimeTrackedSeconds(0L);
                builder.timeRecordCount(0);
            }
        } catch (RetryableException e) {
            log.warn("Timeout fetching time records from life-tracker: {}", e.getMessage());
            builder.timeError("Life-tracker service timeout");
        } catch (FeignException e) {
            log.warn("Failed to fetch time records from life-tracker [{}]: {}", 
                    e.status(), e.getMessage());
            builder.timeError("Life-tracker service error: " + e.status());
        } catch (Exception e) {
            log.error("Unexpected error fetching time records: {}", e.getMessage());
            builder.timeError("Unexpected error fetching time records");
        }

        AnalyticsOverviewDTO overview = builder.build();
        
        // Return 200 even with partial data, errors are in the response body
        return ResponseEntity.ok(overview);
    }

    /**
     * Get raw expense data.
     * Feign errors propagate to GlobalExceptionHandler.
     */
    @GetMapping("/raw/expenses")
    public ResponseEntity<List<ExpenseDTO>> getRawExpenses() {
        log.debug("Fetching raw expense data");
        List<ExpenseDTO> expenses = lifeTrackerClient.getExpenses();
        return ResponseEntity.ok(expenses != null ? expenses : Collections.emptyList());
    }

    /**
     * Get expenses grouped by month.
     */
    @GetMapping("/expenses/by-month")
    public ResponseEntity<List<ExpenseSummaryDTO>> getExpensesByMonth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        log.debug("Getting expenses grouped by month (from: {}, to: {})", from, to);
        return ResponseEntity.ok(analyticsService.getExpensesByMonth(from, to));
    }

    /**
     * Get expenses grouped by category.
     */
    @GetMapping("/expenses/by-category")
    public ResponseEntity<List<ExpenseSummaryDTO>> getExpensesByCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        log.debug("Getting expenses grouped by category (from: {}, to: {})", from, to);
        return ResponseEntity.ok(analyticsService.getExpensesByCategory(from, to));
    }

    /**
     * Get expense trend chart data.
     */
    @GetMapping("/expenses/trend")
    public ResponseEntity<ChartDataDTO> getExpenseTrend(
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        log.debug("Getting expense trend (period: {}, from: {}, to: {})", period, from, to);
        return ResponseEntity.ok(analyticsService.getExpenseTrend(period, from, to));
    }

    /**
     * Get raw time record data.
     */
    @GetMapping("/raw/time-records")
    public ResponseEntity<List<TimeRecordDTO>> getRawTimeRecords() {
        log.debug("Fetching raw time record data");
        List<TimeRecordDTO> records = lifeTrackerClient.getTimeRecords();
        return ResponseEntity.ok(records != null ? records : Collections.emptyList());
    }

    /**
     * Get time tracking statistics.
     */
    @GetMapping("/time/summary")
    public ResponseEntity<List<TimeStatisticsDTO>> getTimeStatistics() {
        log.debug("Getting time tracking statistics");
        return ResponseEntity.ok(analyticsService.getTimeStatistics());
    }

    /**
     * Get calendar statistics.
     */
    @GetMapping("/calendar/summary")
    public ResponseEntity<CalendarStatisticsDTO> getCalendarStatistics() {
        log.debug("Getting calendar statistics");
        return ResponseEntity.ok(analyticsService.getCalendarStatistics());
    }
}
