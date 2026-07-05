package org.jarvis.analytics.service;

import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.HabitStreakDTO;
import org.jarvis.analytics.dto.InsightDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConcreteAnswerServiceTest {

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private InsightService insightService;

    @Mock
    private ChangeAnalysisService changeAnalysisService;

    @Mock
    private HabitStreakService habitStreakService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private ConcreteAnswerService service;

    @BeforeEach
    void setUp() {
        service = new ConcreteAnswerService(analyticsService, insightService, changeAnalysisService,
                habitStreakService, clock);
    }

    @Test
    void tryAnswerReturnsEmptyForNonCanonicalQuestion() {
        Optional<String> answer = service.tryAnswer("Как погода завтра?");

        assertThat(answer).isEmpty();
        verifyNoInteractions(analyticsService, insightService, changeAnalysisService, habitStreakService);
    }

    @Test
    void tryAnswerReturnsEmptyForNullOrBlankQuestion() {
        assertThat(service.tryAnswer(null)).isEmpty();
        assertThat(service.tryAnswer("   ")).isEmpty();
    }

    @Test
    void tryAnswerBuildsMoneyAnswerForKudaUshliDengi() {
        LocalDate today = LocalDate.of(2026, 3, 13);
        LocalDate from = today.minusDays(29);
        when(analyticsService.getExpensesByCategory(from, today)).thenReturn(List.of(
                new ExpenseSummaryDTO("All", "Rent", new BigDecimal("500"), "EUR", 1),
                new ExpenseSummaryDTO("All", "Food", new BigDecimal("200"), "EUR", 5)));

        Optional<String> answer = service.tryAnswer("куда ушли деньги?");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("700").contains("Rent").contains("Food");
    }

    @Test
    void tryAnswerReportsNoSpendWhenNoExpensesFound() {
        when(analyticsService.getExpensesByCategory(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());

        Optional<String> answer = service.tryAnswer("Куда ушли деньги в этом месяце?");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("трат не найдено");
    }

    @Test
    void tryAnswerBuildsTirednessAnswerFromSleepAndOvertimeInsights() {
        when(insightService.autoInsights()).thenReturn(List.of(
                new InsightDTO("LOW_SLEEP", "Недосып", "Средний сон 5.5 ч.", "WARN"),
                new InsightDTO("ALL_GOOD", "Всё спокойно", "Заметных аномалий не обнаружено.", "INFO")));

        Optional<String> answer = service.tryAnswer("Почему я устал?");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("Средний сон 5.5 ч.");
        assertThat(answer.get()).doesNotContain("Заметных аномалий");
    }

    @Test
    void tryAnswerReportsNoTirednessCauseWhenNothingIsOff() {
        when(insightService.autoInsights()).thenReturn(List.of(
                new InsightDTO("ALL_GOOD", "Всё спокойно", "Заметных аномалий не обнаружено.", "INFO")));

        Optional<String> answer = service.tryAnswer("почему я так устал сегодня");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("не видно");
    }

    @Test
    void tryAnswerDelegatesWeeklyChangeQuestionToChangeAnalysisService() {
        when(changeAnalysisService.whatChanged()).thenReturn(Map.of("summary", "Сон упал, траты выросли."));

        Optional<String> answer = service.tryAnswer("Что изменилось за неделю?");

        assertThat(answer).contains("Сон упал, траты выросли.");
    }

    @Test
    void tryAnswerBuildsHabitDeclineAnswerForBrokenStreaks() {
        when(habitStreakService.habitStreaks(30)).thenReturn(List.of(
                new HabitStreakDTO("Meditation", 0, 5, 3, 30, 10.0, "explanation"),
                new HabitStreakDTO("Reading", 10, 10, 28, 30, 93.3, "explanation")));

        Optional<String> answer = service.tryAnswer("Какие привычки просели?");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("Meditation");
        assertThat(answer.get()).doesNotContain("Reading");
    }

    @Test
    void tryAnswerReportsHabitsStableWhenNoneDeclined() {
        when(habitStreakService.habitStreaks(30)).thenReturn(List.of(
                new HabitStreakDTO("Reading", 10, 10, 28, 30, 93.3, "explanation")));

        Optional<String> answer = service.tryAnswer("какие привычки просели в последнее время");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("стабильно");
    }

    @Test
    void tryAnswerBuildsTomorrowImprovementAnswerFromRegressionsAndWarnings() {
        Map<String, Object> regression = Map.of("metric", "sleepAvgHours", "explanation", "Сон упал на 1.5 ч.");
        Map<String, Object> whyBad = Map.of("regressions", List.of(regression));
        when(changeAnalysisService.whyWeekWentBad()).thenReturn(whyBad);
        when(insightService.autoInsights()).thenReturn(List.of(
                new InsightDTO("OVERTIME", "Переработки", "Переработка +10 ч.", "WARN")));

        Optional<String> answer = service.tryAnswer("Что улучшить завтра?");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("Сон упал на 1.5 ч.");
        assertThat(answer.get()).contains("Переработка +10 ч.");
    }

    @Test
    void tryAnswerReportsNoIssuesForTomorrowWhenNothingRegressed() {
        Map<String, Object> whyBad = Map.of("regressions", List.of());
        when(changeAnalysisService.whyWeekWentBad()).thenReturn(whyBad);
        when(insightService.autoInsights()).thenReturn(List.of(
                new InsightDTO("ALL_GOOD", "Всё спокойно", "Заметных аномалий не обнаружено.", "INFO")));

        Optional<String> answer = service.tryAnswer("что улучшить завтра");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("Существенных проблем не найдено");
    }

    @Test
    void tryAnswerDegradesGracefullyWhenUpstreamCallThrows() {
        when(analyticsService.getExpensesByCategory(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new RuntimeException("life-tracker unavailable"));

        Optional<String> answer = service.tryAnswer("куда ушли деньги");

        assertThat(answer).isPresent();
        assertThat(answer.get()).contains("Не удалось получить данные");
    }
}
