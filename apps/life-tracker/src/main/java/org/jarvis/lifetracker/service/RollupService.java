package org.jarvis.lifetracker.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.FinanceSummaryDTO;
import org.jarvis.lifetracker.dto.RollupDTO;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Weekly / monthly rollups that aggregate finance + wellness data for a user (Roadmap P1 #11). */
@Service
@RequiredArgsConstructor
public class RollupService {

    private static final List<WellnessType> AVERAGEABLE_TYPES = List.of(
            WellnessType.WEIGHT, WellnessType.MOOD, WellnessType.STEPS, WellnessType.SLEEP, WellnessType.WORKOUT);

    private final FinanceService financeService;
    private final WellnessLogRepository wellnessLogRepository;

    public RollupDTO weeklyRollup(String userId, LocalDate anchorDate) {
        LocalDate effective = anchorDate != null ? anchorDate : LocalDate.now();
        LocalDate start = effective.with(DayOfWeek.MONDAY);
        LocalDate end = start.plusDays(6);
        return buildRollup(userId, "WEEK", start, end);
    }

    public RollupDTO monthlyRollup(String userId, YearMonth month) {
        YearMonth effective = month != null ? month : YearMonth.now();
        return buildRollup(userId, "MONTH", effective.atDay(1), effective.atEndOfMonth());
    }

    private RollupDTO buildRollup(String userId, String period, LocalDate start, LocalDate end) {
        FinanceSummaryDTO financeSummary = financeService.summarizeRange(userId, start, end, period);
        List<ExpenseDTO> transactions = financeService.listTransactions(
                userId, start.atStartOfDay(), end.atTime(23, 59, 59), null, null);
        List<WellnessLog> wellnessLogs =
                wellnessLogRepository.findByUserIdAndDayBetweenOrderByLoggedAtAsc(userId, start, end);

        Map<WellnessType, Double> averages = new EnumMap<>(WellnessType.class);
        for (WellnessType type : AVERAGEABLE_TYPES) {
            List<Double> values = wellnessLogs.stream()
                    .filter(log -> log.getType() == type && log.getNumericValue() != null)
                    .map(WellnessLog::getNumericValue)
                    .toList();
            if (!values.isEmpty()) {
                averages.put(type, values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            }
        }

        return new RollupDTO(period, start, end, financeSummary.getTotalIncome(), financeSummary.getTotalExpense(),
                financeSummary.getCurrency(), financeSummary.getByCategory(), averages,
                habitCompletionRate(wellnessLogs), wellnessLogs.size(), transactions.size());
    }

    private Double habitCompletionRate(List<WellnessLog> wellnessLogs) {
        List<WellnessLog> habitLogs = wellnessLogs.stream()
                .filter(log -> log.getType() == WellnessType.HABIT)
                .toList();
        if (habitLogs.isEmpty()) {
            return null;
        }
        // Collapse to one "done" flag per (habit name, day): a day counts if any check-in that day was positive.
        Map<String, Boolean> doneByHabitDay = habitLogs.stream()
                .collect(Collectors.toMap(
                        log -> log.getTextValue() + "|" + log.getDay(),
                        log -> log.getNumericValue() != null && log.getNumericValue() > 0,
                        (existing, incoming) -> existing || incoming));
        long doneCount = doneByHabitDay.values().stream().filter(Boolean::booleanValue).count();
        return (double) doneCount / doneByHabitDay.size();
    }
}
