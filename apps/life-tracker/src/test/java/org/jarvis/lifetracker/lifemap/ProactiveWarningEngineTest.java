package org.jarvis.lifetracker.lifemap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveWarningEngineTest {

    private LifeMapProperties properties;
    private ProactiveWarningEngine engine;

    @BeforeEach
    void setUp() {
        properties = new LifeMapProperties();
        engine = new ProactiveWarningEngine(properties);
    }

    private LifeMapDtos.DailySummary build(Map<TimeCategory, Long> seconds,
                                            BigDecimal expense, BigDecimal budget,
                                            Double sleepHours) {
        long total = seconds.values().stream().mapToLong(Long::longValue).sum();
        return new LifeMapDtos.DailySummary(
                LocalDate.of(2026, 5, 1),
                total, seconds,
                BigDecimal.ZERO, expense == null ? BigDecimal.ZERO : expense, budget,
                0, 0, sleepHours, 0, 0,
                List.of());
    }

    @Test
    void timeWasteFiresAboveThreshold() {
        Map<TimeCategory, Long> sec = new EnumMap<>(TimeCategory.class);
        sec.put(TimeCategory.REST, 3 * 3600L); // 180 min, threshold 120
        var warnings = engine.evaluate(build(sec, null, null, null));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).code()).isEqualTo("TIME_WASTE");
        assertThat(warnings.get(0).severity()).isEqualTo(LifeMapDtos.ProactiveWarning.Severity.WARN);
        assertThat(warnings.get(0).evidence()).containsKey("restMinutes");
    }

    @Test
    void timeWasteSilentBelowThreshold() {
        Map<TimeCategory, Long> sec = new EnumMap<>(TimeCategory.class);
        sec.put(TimeCategory.REST, 60 * 60L);  // 60 min
        assertThat(engine.evaluate(build(sec, null, null, null))).isEmpty();
    }

    @Test
    void overspendFiresAboveRatio() {
        var warnings = engine.evaluate(build(
                new EnumMap<>(TimeCategory.class),
                new BigDecimal("190"), new BigDecimal("200"), null));   // 95% > 90% threshold
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).code()).isEqualTo("OVERSPEND");
    }

    @Test
    void overspendCriticalAtOrAbove100Percent() {
        var warnings = engine.evaluate(build(
                new EnumMap<>(TimeCategory.class),
                new BigDecimal("210"), new BigDecimal("200"), null));
        assertThat(warnings).extracting(LifeMapDtos.ProactiveWarning::severity)
                .contains(LifeMapDtos.ProactiveWarning.Severity.CRITICAL);
    }

    @Test
    void overspendIgnoresMissingBudget() {
        var warnings = engine.evaluate(build(
                new EnumMap<>(TimeCategory.class),
                new BigDecimal("999"), null, null));
        assertThat(warnings).isEmpty();
    }

    @Test
    void lowSleepFiresBelowThreshold() {
        var warnings = engine.evaluate(build(new EnumMap<>(TimeCategory.class),
                null, null, 5.0));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).code()).isEqualTo("LOW_SLEEP");
    }

    @Test
    void lowSleepSilentWhenSleepDataMissing() {
        assertThat(engine.evaluate(build(new EnumMap<>(TimeCategory.class), null, null, null)))
                .isEmpty();
    }

    @Test
    void disabledEngineEmitsNothing() {
        properties.getWarnings().setEnabled(false);
        Map<TimeCategory, Long> sec = new EnumMap<>(TimeCategory.class);
        sec.put(TimeCategory.REST, 10_000L);
        assertThat(engine.evaluate(build(sec, null, null, null))).isEmpty();
    }

    @Test
    void explanationLookupReturnsNarrativeForRegisteredWarning() {
        Map<TimeCategory, Long> sec = new EnumMap<>(TimeCategory.class);
        sec.put(TimeCategory.REST, 4 * 3600L);
        var warning = engine.evaluate(build(sec, null, null, null)).get(0);
        var explanation = engine.explanation(warning.warningId());
        assertThat(explanation).isNotNull();
        assertThat(explanation.code()).isEqualTo("TIME_WASTE");
        assertThat(explanation.rule()).contains("REST");
        assertThat(explanation.evidence()).containsKey("restMinutes");
    }
}
