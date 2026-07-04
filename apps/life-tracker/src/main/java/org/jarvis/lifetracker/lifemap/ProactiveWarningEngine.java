package org.jarvis.lifetracker.lifemap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 — evaluates a small library of rules over the daily summary
 * and emits {@link LifeMapDtos.ProactiveWarning}s the desktop panel can
 * show or speak via the voice loop.
 *
 * <p>Pass 1 ships three rules:</p>
 * <ul>
 *   <li><b>TIME_WASTE</b> — REST seconds &gt; configured threshold</li>
 *   <li><b>OVERSPEND</b> — expense / budget &gt; configured ratio</li>
 *   <li><b>LOW_SLEEP</b> — last-night sleep &lt; configured hours</li>
 * </ul>
 *
 * <p>Each warning is registered in an in-memory map so
 * {@code GET /life-map/recommendations/{id}/explanation} can render the
 * same evidence + rule rationale on demand.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveWarningEngine {

    private final LifeMapProperties properties;
    private final Map<String, LifeMapDtos.RecommendationExplanation> explanations =
            new ConcurrentHashMap<>();

    public List<LifeMapDtos.ProactiveWarning> evaluate(LifeMapDtos.DailySummary summary) {
        if (!properties.getWarnings().isEnabled() || summary == null) {
            return List.of();
        }
        List<LifeMapDtos.ProactiveWarning> out = new ArrayList<>(3);
        evaluateTimeWaste(summary).ifPresent(out::add);
        evaluateOverspend(summary).ifPresent(out::add);
        evaluateLowSleep(summary).ifPresent(out::add);
        return out;
    }

    public LifeMapDtos.RecommendationExplanation explanation(String warningId) {
        return explanations.get(warningId);
    }

    // ------- rules -------

    private java.util.Optional<LifeMapDtos.ProactiveWarning> evaluateTimeWaste(
            LifeMapDtos.DailySummary summary) {
        long restSeconds = summary.secondsByCategory().getOrDefault(TimeCategory.REST, 0L);
        long thresholdSeconds = properties.getWarnings().getTimeWasteMinutesPerDay() * 60L;
        if (restSeconds <= thresholdSeconds) return java.util.Optional.empty();

        long restMinutes = restSeconds / 60;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("restMinutes", restMinutes);
        evidence.put("thresholdMinutes", properties.getWarnings().getTimeWasteMinutesPerDay());
        evidence.put("byCategoryMinutes", summary.secondsByCategory().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().name(), e -> e.getValue() / 60,
                        (a, b) -> a, java.util.LinkedHashMap::new)));

        String message = String.format(
                "REST/relaxation reached %d min today (threshold %d min). Consider a focus block.",
                restMinutes, properties.getWarnings().getTimeWasteMinutesPerDay());
        return java.util.Optional.of(register(
                "TIME_WASTE", LifeMapDtos.ProactiveWarning.Severity.WARN, message, evidence,
                "REST > timeWasteMinutesPerDay"));
    }

    private java.util.Optional<LifeMapDtos.ProactiveWarning> evaluateOverspend(
            LifeMapDtos.DailySummary summary) {
        BigDecimal expense = summary.financeExpense() == null ? BigDecimal.ZERO : summary.financeExpense();
        BigDecimal budget = summary.financeBudget();
        if (budget == null || budget.signum() <= 0) return java.util.Optional.empty();
        BigDecimal ratio = expense.divide(budget, 4, RoundingMode.HALF_UP);
        BigDecimal threshold = BigDecimal.valueOf(properties.getWarnings().getOverspendBudgetRatio());
        if (ratio.compareTo(threshold) <= 0) return java.util.Optional.empty();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("expense", expense);
        evidence.put("budget", budget);
        evidence.put("ratio", ratio);
        evidence.put("thresholdRatio", threshold);

        String message = String.format(
                "Today's spend is %.0f%% of the daily budget (threshold %.0f%%).",
                ratio.doubleValue() * 100.0,
                properties.getWarnings().getOverspendBudgetRatio() * 100.0);
        return java.util.Optional.of(register(
                "OVERSPEND",
                ratio.compareTo(BigDecimal.ONE) >= 0
                        ? LifeMapDtos.ProactiveWarning.Severity.CRITICAL
                        : LifeMapDtos.ProactiveWarning.Severity.WARN,
                message, evidence,
                "expense / budget > overspendBudgetRatio"));
    }

    private java.util.Optional<LifeMapDtos.ProactiveWarning> evaluateLowSleep(
            LifeMapDtos.DailySummary summary) {
        Double sleep = summary.sleepHours();
        if (sleep == null) return java.util.Optional.empty();
        if (sleep >= properties.getWarnings().getLowSleepHours()) return java.util.Optional.empty();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sleepHours", sleep);
        evidence.put("thresholdHours", properties.getWarnings().getLowSleepHours());

        String message = String.format(
                "Last night you slept %.1fh (threshold %.1fh). Today's planning will favour lighter tasks.",
                sleep, properties.getWarnings().getLowSleepHours());
        return java.util.Optional.of(register(
                "LOW_SLEEP", LifeMapDtos.ProactiveWarning.Severity.WARN, message, evidence,
                "sleepHours < lowSleepHours"));
    }

    private LifeMapDtos.ProactiveWarning register(String code,
                                                  LifeMapDtos.ProactiveWarning.Severity severity,
                                                  String message,
                                                  Map<String, Object> evidence,
                                                  String rule) {
        String warningId = "warn-" + UUID.randomUUID();
        Instant now = Instant.now();
        LifeMapDtos.ProactiveWarning warning = new LifeMapDtos.ProactiveWarning(
                warningId, code, severity, message, evidence, now);
        LifeMapDtos.RecommendationExplanation explanation = new LifeMapDtos.RecommendationExplanation(
                warningId, code, rule,
                "This recommendation fired because the rule '" + rule
                        + "' evaluated to true on the day's life-map summary.",
                evidence, now);
        explanations.put(warningId, explanation);
        log.info("life-map warning {} ({}) — {}", warningId, code, message);
        return warning;
    }
}
