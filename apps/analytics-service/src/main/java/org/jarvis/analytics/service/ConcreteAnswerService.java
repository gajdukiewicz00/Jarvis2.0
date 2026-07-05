package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.HabitStreakDTO;
import org.jarvis.analytics.dto.InsightDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Concrete, explained rule-based answers for a fixed set of canonical
 * NL-analytics questions ("куда ушли деньги", "почему я устал", "что
 * изменилось за неделю", "какие привычки просели", "что улучшить завтра").
 *
 * <p>These bypass the optional LLM path entirely: the answer is fully
 * deterministic and derived from the same aggregates the rest of the
 * insight engine already computes, so it works identically with or without
 * llm-service (host GPU Qwen3-14B) being reachable — see
 * {@link NlAnalyticsService#ask(String, String)}, which tries this service
 * first and only falls through to the LLM/rule-based-fallback path when
 * nothing here matches.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConcreteAnswerService {

    private static final int SPEND_WINDOW_DAYS = 30;
    private static final int HABIT_WINDOW_DAYS = 30;
    private static final double HABIT_DECLINE_THRESHOLD_PCT = 50.0;
    private static final int TOP_CATEGORIES_LIMIT = 3;
    private static final Set<String> TIREDNESS_INSIGHT_CODES = Set.of("LOW_SLEEP", "OVERTIME", "SLEEP_WORK_CORR");
    private static final String DEGRADED_MESSAGE =
            "Не удалось получить данные для точного ответа прямо сейчас — попробуйте позже.";

    private final AnalyticsService analyticsService;
    private final InsightService insightService;
    private final ChangeAnalysisService changeAnalysisService;
    private final HabitStreakService habitStreakService;
    private final Clock clock;

    /** Attempts to match {@code question} against a canonical pattern; empty if none match. */
    public Optional<String> tryAnswer(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String q = question.toLowerCase(Locale.ROOT);

        if (isMoneyQuestion(q)) {
            return Optional.of(safeAnswer(this::buildMoneyAnswer));
        }
        if (isTirednessQuestion(q)) {
            return Optional.of(safeAnswer(this::buildTirednessAnswer));
        }
        if (isWeeklyChangeQuestion(q)) {
            return Optional.of(safeAnswer(this::buildWeeklyChangeAnswer));
        }
        if (isHabitDeclineQuestion(q)) {
            return Optional.of(safeAnswer(this::buildHabitDeclineAnswer));
        }
        if (isTomorrowImprovementQuestion(q)) {
            return Optional.of(safeAnswer(this::buildTomorrowImprovementAnswer));
        }
        return Optional.empty();
    }

    private boolean isMoneyQuestion(String q) {
        return q.contains("деньги") && (q.contains("куда") || q.contains("ушли") || q.contains("потрат"));
    }

    private boolean isTirednessQuestion(String q) {
        return q.contains("устал");
    }

    private boolean isWeeklyChangeQuestion(String q) {
        return q.contains("недел") && q.contains("измен");
    }

    private boolean isHabitDeclineQuestion(String q) {
        return q.contains("привычк") && (q.contains("просел") || q.contains("ухудш"));
    }

    private boolean isTomorrowImprovementQuestion(String q) {
        return q.contains("улучш") && q.contains("завтра");
    }

    private String safeAnswer(Supplier<String> builder) {
        try {
            return builder.get();
        } catch (RuntimeException e) {
            log.warn("Concrete NL answer builder failed, degrading gracefully: {}", e.getMessage());
            return DEGRADED_MESSAGE;
        }
    }

    /** "куда ушли деньги" — top spend categories over the trailing window, with shares. */
    private String buildMoneyAnswer() {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(SPEND_WINDOW_DAYS - 1L);
        List<ExpenseSummaryDTO> byCategory = analyticsService.getExpensesByCategory(from, today);
        if (byCategory == null || byCategory.isEmpty()) {
            return "За последние " + SPEND_WINDOW_DAYS + " дн. трат не найдено.";
        }
        BigDecimal total = byCategory.stream()
                .map(ExpenseSummaryDTO::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder("За последние ").append(SPEND_WINDOW_DAYS)
                .append(" дн. потрачено ").append(total).append(". Основные категории: ");
        byCategory.stream().limit(TOP_CATEGORIES_LIMIT).forEach(c -> {
            double share = total.signum() > 0 && c.getTotalAmount() != null
                    ? c.getTotalAmount().doubleValue() / total.doubleValue() * 100.0 : 0.0;
            sb.append(c.getCategory()).append(" — ").append(c.getTotalAmount())
                    .append(" (").append(Math.round(share)).append("%), ");
        });
        sb.setLength(sb.length() - 2);
        sb.append('.');
        return sb.toString();
    }

    /** "почему я устал" — sleep/overtime-related insights, explained. */
    private String buildTirednessAnswer() {
        List<InsightDTO> causes = insightService.autoInsights().stream()
                .filter(i -> TIREDNESS_INSIGHT_CODES.contains(i.code()))
                .toList();
        if (causes.isEmpty()) {
            return "По данным трекера явных причин усталости не видно: сон и рабочая нагрузка в норме.";
        }
        StringBuilder sb = new StringBuilder("Похоже, вот из-за чего вы устали: ");
        causes.forEach(c -> sb.append(c.detail()).append(' '));
        return sb.toString().trim();
    }

    /** "что изменилось за неделю" — delegates to {@link ChangeAnalysisService#whatChanged()}. */
    private String buildWeeklyChangeAnswer() {
        Map<String, Object> whatChanged = changeAnalysisService.whatChanged();
        Object summary = whatChanged.get("summary");
        return summary == null ? "Данных для сравнения недель пока недостаточно." : summary.toString();
    }

    /** "какие привычки просели" — habits with a broken streak or low consistency. */
    private String buildHabitDeclineAnswer() {
        List<HabitStreakDTO> streaks = habitStreakService.habitStreaks(HABIT_WINDOW_DAYS);
        if (streaks == null || streaks.isEmpty()) {
            return "Данных о привычках за последние " + HABIT_WINDOW_DAYS + " дн. нет.";
        }
        List<HabitStreakDTO> declined = streaks.stream()
                .filter(h -> h.currentStreakDays() == 0 || h.consistencyPct() < HABIT_DECLINE_THRESHOLD_PCT)
                .toList();
        if (declined.isEmpty()) {
            return "Все отслеживаемые привычки держатся стабильно за последние " + HABIT_WINDOW_DAYS + " дн.";
        }
        StringBuilder sb = new StringBuilder("Просели привычки: ");
        declined.forEach(h -> sb.append(h.habit()).append(" (").append(h.consistencyPct()).append("%), "));
        sb.setLength(sb.length() - 2);
        sb.append('.');
        return sb.toString();
    }

    /** "что улучшить завтра" — regressions + WARN-severity insights, as actionable focus points. */
    @SuppressWarnings("unchecked")
    private String buildTomorrowImprovementAnswer() {
        Map<String, Object> whyBad = changeAnalysisService.whyWeekWentBad();
        List<Map<String, Object>> regressions =
                (List<Map<String, Object>>) whyBad.getOrDefault("regressions", List.of());
        List<InsightDTO> warnings = insightService.autoInsights().stream()
                .filter(i -> "WARN".equals(i.severity()))
                .toList();
        if (regressions.isEmpty() && warnings.isEmpty()) {
            return "Существенных проблем не найдено — можно держать текущий темп.";
        }
        StringBuilder sb = new StringBuilder("На завтра стоит обратить внимание: ");
        regressions.forEach(r -> sb.append(r.get("explanation")).append(' '));
        warnings.forEach(w -> sb.append(w.detail()).append(' '));
        return sb.toString().trim();
    }
}
