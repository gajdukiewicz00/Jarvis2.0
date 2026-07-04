package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
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
    private final Clock clock;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Set<String> SLEEP_KEYWORDS = Set.of("sleep", "slept", "nap", "rest");
    private static final Set<String> WORK_KEYWORDS = Set.of("work", "focus", "coding", "meeting", "study");

    /**
     * Get expense summaries grouped by month with optional date filtering
     */
    public List<ExpenseSummaryDTO> getExpensesByMonth(LocalDate from, LocalDate to) {
        log.info("Aggregating expenses by month (from: {}, to: {})", from, to);
        List<ExpenseDTO> expenses = safeList(lifeTrackerClient.getExpenses());
        expenses = expenses.stream()
                .filter(this::isExpense)
                .collect(Collectors.toList());

        // Filter by date range if provided
        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getOccurredAt(), from, to))
                    .collect(Collectors.toList());
        }

        Map<String, List<ExpenseDTO>> byMonth = expenses.stream()
                .collect(Collectors.groupingBy(expense -> expense.getOccurredAt().format(MONTH_FORMATTER)));

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
        List<ExpenseDTO> expenses = safeList(lifeTrackerClient.getExpenses());
        expenses = expenses.stream()
                .filter(this::isExpense)
                .collect(Collectors.toList());

        // Filter by date range if provided
        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getOccurredAt(), from, to))
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
        List<ExpenseDTO> expenses = safeList(lifeTrackerClient.getExpenses());
        expenses = expenses.stream()
                .filter(this::isExpense)
                .collect(Collectors.toList());

        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getOccurredAt(), from, to))
                    .collect(Collectors.toList());
        }

        Map<String, List<ExpenseDTO>> byWeek = expenses.stream()
                .collect(Collectors.groupingBy(expense -> expense.getOccurredAt().format(weekFormatter)));

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
        List<ExpenseDTO> expenses = safeList(lifeTrackerClient.getExpenses());
        expenses = expenses.stream()
                .filter(this::isExpense)
                .collect(Collectors.toList());

        if (from != null || to != null) {
            expenses = expenses.stream()
                    .filter(e -> isWithinDateRange(e.getOccurredAt(), from, to))
                    .collect(Collectors.toList());
        }

        Map<String, List<ExpenseDTO>> byYear = expenses.stream()
                .collect(Collectors.groupingBy(expense -> expense.getOccurredAt().format(yearFormatter)));

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

    private boolean isExpense(ExpenseDTO expense) {
        String type = expense.getType();
        return type == null || "EXPENSE".equalsIgnoreCase(type);
    }

    /**
     * Get time tracking statistics by category
     */
    public List<TimeStatisticsDTO> getTimeStatistics() {
        log.info("Calculating time tracking statistics");
        List<org.jarvis.analytics.dto.TimeRecordDTO> timeRecords = safeList(lifeTrackerClient.getTimeRecords());

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
        List<org.jarvis.analytics.dto.CalendarEventDTO> events = safeList(lifeTrackerClient.getCalendarEvents());

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

    public SleepSummaryDTO getSleepSummary(int trailingDays) {
        int normalizedDays = normalizePositive(trailingDays, "trailingDays");
        LocalDate cutoff = LocalDate.now(clock).minusDays(normalizedDays - 1L);

        LocalDate today = LocalDate.now(clock);

        // Legacy estimate: per-day sum of sleep-tagged time records (hours).
        Map<LocalDate, Double> sleepByDay = new HashMap<>();
        safeList(lifeTrackerClient.getTimeRecords()).stream()
                .filter(this::hasDuration)
                .filter(this::isSleepRecord)
                .filter(record -> isWithinTrailingWindow(record, cutoff))
                .forEach(record -> sleepByDay.merge(record.getStartTime().toLocalDate(),
                        record.getDurationSeconds() / 3600.0, Double::sum));

        // Authoritative source: WellnessLog SLEEP entries (health-entry / phone
        // Health Connect sync). numericValue = hours slept; latest entry per day
        // wins and overrides the legacy time-record estimate. Without this, sleep
        // logged via /wellness/health-entry was invisible → day-score read 0.0.
        for (org.jarvis.analytics.dto.WellnessLogDTO w : safeList(lifeTrackerClient.getWellnessTrend("SLEEP"))) {
            if (w == null || w.getNumericValue() == null || w.getDay() == null) {
                continue;
            }
            if (w.getDay().isBefore(cutoff) || w.getDay().isAfter(today)) {
                continue;
            }
            sleepByDay.put(w.getDay(), w.getNumericValue());
        }

        double totalSleepHours = roundHours(sleepByDay.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum());
        int daysSampled = sleepByDay.size();
        Double averageHours = daysSampled > 0 ? roundHours(totalSleepHours / daysSampled) : null;

        return new SleepSummaryDTO(averageHours, daysSampled, normalizedDays, totalSleepHours);
    }

    public OvertimeSummaryDTO getOvertimeSummary(int trailingDays, int baselineHours) {
        int normalizedDays = normalizePositive(trailingDays, "trailingDays");
        int normalizedBaseline = normalizePositive(baselineHours, "baselineHours");
        LocalDate cutoff = LocalDate.now(clock).minusDays(normalizedDays - 1L);

        double trackedWorkHours = roundHours(safeList(lifeTrackerClient.getTimeRecords()).stream()
                .filter(this::hasDuration)
                .filter(this::isWorkRecord)
                .filter(record -> isWithinTrailingWindow(record, cutoff))
                .mapToLong(TimeRecordDTO::getDurationSeconds)
                .sum() / 3600.0);

        int overtimeHours = Math.max(0, (int) Math.round(trackedWorkHours - normalizedBaseline));
        return new OvertimeSummaryDTO(overtimeHours, trackedWorkHours, normalizedBaseline, normalizedDays);
    }

    private boolean hasDuration(TimeRecordDTO record) {
        return record != null && record.getDurationSeconds() != null && record.getDurationSeconds() > 0
                && record.getStartTime() != null;
    }

    private boolean isWithinTrailingWindow(TimeRecordDTO record, LocalDate cutoff) {
        LocalDate recordDate = record.getStartTime().toLocalDate();
        LocalDate today = LocalDate.now(clock);
        return (recordDate.isEqual(cutoff) || recordDate.isAfter(cutoff)) && !recordDate.isAfter(today);
    }

    private boolean isSleepRecord(TimeRecordDTO record) {
        return matchesKeyword(record.getCategory(), SLEEP_KEYWORDS) || matchesKeyword(record.getActivity(), SLEEP_KEYWORDS);
    }

    private boolean isWorkRecord(TimeRecordDTO record) {
        return matchesKeyword(record.getCategory(), WORK_KEYWORDS) || matchesKeyword(record.getActivity(), WORK_KEYWORDS);
    }

    private boolean matchesKeyword(String value, Set<String> keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(normalized::contains);
    }

    private int normalizePositive(int value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return value;
    }

    private double roundHours(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
