package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final LifeTrackerClient lifeTrackerClient;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Get expense summaries grouped by month with optional date filtering
     */
    public List<ExpenseSummaryDTO> getExpensesByMonth(LocalDate from, LocalDate to) {
        log.info("Aggregating expenses by month (from: {}, to: {})", from, to);
        List<ExpenseDTO> expenses = lifeTrackerClient.getExpenses();

        // Filter by date range if provided
        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getDate(), from, to))
                    .collect(Collectors.toList());
        }

        Map<String, List<ExpenseDTO>> byMonth = expenses.stream()
                .collect(Collectors.groupingBy(expense -> expense.getDate().format(MONTH_FORMATTER)));

        return byMonth.entrySet().stream()
                .map(entry -> {
                    String period = entry.getKey();
                    List<ExpenseDTO> monthExpenses = entry.getValue();
                    BigDecimal total = monthExpenses.stream()
                            .map(ExpenseDTO::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    String currency = monthExpenses.isEmpty() ? "EUR"
                            : monthExpenses.get(0).getCurrency() != null ? monthExpenses.get(0).getCurrency() : "EUR";

                    return new ExpenseSummaryDTO(period, "All", total, currency, monthExpenses.size());
                })
                .sorted(Comparator.comparing(ExpenseSummaryDTO::getPeriod).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get expense summaries grouped by category with optional date filtering
     */
    public List<ExpenseSummaryDTO> getExpensesByCategory(LocalDate from, LocalDate to) {
        log.info("Aggregating expenses by category (from: {}, to: {})", from, to);
        List<ExpenseDTO> expenses = lifeTrackerClient.getExpenses();

        // Filter by date range if provided
        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getDate(), from, to))
                    .collect(Collectors.toList());
        }

        Map<String, List<ExpenseDTO>> byCategory = expenses.stream()
                .collect(Collectors.groupingBy(
                        expense -> expense.getCategory() != null ? expense.getCategory() : "Uncategorized"));

        return byCategory.entrySet().stream()
                .map(entry -> {
                    String category = entry.getKey();
                    List<ExpenseDTO> categoryExpenses = entry.getValue();
                    BigDecimal total = categoryExpenses.stream()
                            .map(ExpenseDTO::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    String currency = categoryExpenses.isEmpty() ? "EUR"
                            : categoryExpenses.get(0).getCurrency() != null ? categoryExpenses.get(0).getCurrency()
                                    : "EUR";

                    return new ExpenseSummaryDTO("All", category, total, currency, categoryExpenses.size());
                })
                .sorted(Comparator.comparing(ExpenseSummaryDTO::getTotalAmount).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get expense trend as chart-ready data
     */
    public ChartDataDTO getExpenseTrend(String period, LocalDate from, LocalDate to) {
        log.info("Generating expense trend (period: {}, from: {}, to: {})", period, from, to);

        List<ExpenseSummaryDTO> summaries = switch (period.toLowerCase()) {
            case "week" -> getExpensesByWeek(from, to);
            case "year" -> getExpensesByYear(from, to);
            default -> getExpensesByMonth(from, to);
        };

        List<String> labels = summaries.stream()
                .map(ExpenseSummaryDTO::getPeriod)
                .collect(Collectors.toList());

        List<Number> values = summaries.stream()
                .map(ExpenseSummaryDTO::getTotalAmount)
                .collect(Collectors.toList());

        return new ChartDataDTO(
                "line",
                labels,
                values,
                "Expense Trend",
                "Period",
                "Amount");
    }

    private List<ExpenseSummaryDTO> getExpensesByWeek(LocalDate from, LocalDate to) {
        // Simplified: group by ISO week
        DateTimeFormatter weekFormatter = DateTimeFormatter.ofPattern("yyyy-'W'ww");
        List<ExpenseDTO> expenses = lifeTrackerClient.getExpenses();

        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getDate(), from, to))
                    .collect(Collectors.toList());
        }

        Map<String, List<ExpenseDTO>> byWeek = expenses.stream()
                .collect(Collectors.groupingBy(expense -> expense.getDate().format(weekFormatter)));

        return byWeek.entrySet().stream()
                .map(entry -> {
                    String period = entry.getKey();
                    List<ExpenseDTO> weekExpenses = entry.getValue();
                    BigDecimal total = weekExpenses.stream()
                            .map(ExpenseDTO::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    String currency = weekExpenses.isEmpty() ? "EUR" : weekExpenses.get(0).getCurrency();
                    return new ExpenseSummaryDTO(period, "All", total, currency, weekExpenses.size());
                })
                .sorted(Comparator.comparing(ExpenseSummaryDTO::getPeriod).reversed())
                .collect(Collectors.toList());
    }

    private List<ExpenseSummaryDTO> getExpensesByYear(LocalDate from, LocalDate to) {
        DateTimeFormatter yearFormatter = DateTimeFormatter.ofPattern("yyyy");
        List<ExpenseDTO> expenses = lifeTrackerClient.getExpenses();

        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getDate(), from, to))
                    .collect(Collectors.toList());
        }

        Map<String, List<ExpenseDTO>> byYear = expenses.stream()
                .collect(Collectors.groupingBy(expense -> expense.getDate().format(yearFormatter)));

        return byYear.entrySet().stream()
                .map(entry -> {
                    String period = entry.getKey();
                    List<ExpenseDTO> yearExpenses = entry.getValue();
                    BigDecimal total = yearExpenses.stream()
                            .map(ExpenseDTO::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    String currency = yearExpenses.isEmpty() ? "EUR" : yearExpenses.get(0).getCurrency();
                    return new ExpenseSummaryDTO(period, "All", total, currency, yearExpenses.size());
                })
                .sorted(Comparator.comparing(ExpenseSummaryDTO::getPeriod).reversed())
                .collect(Collectors.toList());
    }

    private boolean isWithinDateRange(LocalDateTime dateTime, LocalDate from, LocalDate to) {
        LocalDate date = dateTime.toLocalDate();
        if (from != null && date.isBefore(from))
            return false;
        if (to != null && date.isAfter(to))
            return false;
        return true;
    }

    /**
     * Get time tracking statistics by category
     */
    public List<TimeStatisticsDTO> getTimeStatistics() {
        log.info("Calculating time tracking statistics");
        List<org.jarvis.analytics.dto.TimeRecordDTO> timeRecords = lifeTrackerClient.getTimeRecords();

        Map<String, List<org.jarvis.analytics.dto.TimeRecordDTO>> byCategory = timeRecords.stream()
                .filter(record -> record.getDurationSeconds() != null)
                .collect(Collectors
                        .groupingBy(record -> record.getCategory() != null ? record.getCategory() : "Uncategorized"));

        return byCategory.entrySet().stream()
                .map(entry -> {
                    String category = entry.getKey();
                    List<org.jarvis.analytics.dto.TimeRecordDTO> records = entry.getValue();

                    long totalSeconds = records.stream()
                            .mapToLong(org.jarvis.analytics.dto.TimeRecordDTO::getDurationSeconds)
                            .sum();

                    double totalHours = totalSeconds / 3600.0;
                    int count = records.size();
                    double averageHours = count > 0 ? totalHours / count : 0.0;

                    return new TimeStatisticsDTO(category,
                            Math.round(totalHours * 100.0) / 100.0,
                            count,
                            Math.round(averageHours * 100.0) / 100.0);
                })
                .sorted(Comparator.comparing(TimeStatisticsDTO::getTotalDurationHours).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get calendar event statistics
     */
    public CalendarStatisticsDTO getCalendarStatistics() {
        log.info("Calculating calendar statistics");
        List<org.jarvis.analytics.dto.CalendarEventDTO> events = lifeTrackerClient.getCalendarEvents();

        LocalDateTime now = LocalDateTime.now();
        int total = events.size();
        int upcoming = 0;
        int past = 0;
        int allDay = 0;

        for (org.jarvis.analytics.dto.CalendarEventDTO event : events) {
            // Check if all day event
            if (event.isAllDay()) {
                allDay++;
            }

            // Count upcoming vs past
            if (event.getStartTime() != null) {
                if (event.getStartTime().isAfter(now)) {
                    upcoming++;
                } else {
                    past++;
                }
            }
        }

        return new CalendarStatisticsDTO(total, upcoming, past, allDay);
    }
}
