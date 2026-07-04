package org.jarvis.lifetracker.lifemap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LifeMapController.class)
@AutoConfigureMockMvc(addFilters = false)
class LifeMapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InMemoryActivityStore activityStore;

    @MockBean
    private TimeClassifier classifier;

    @MockBean
    private DailySummaryService summaryService;

    @MockBean
    private ProactiveWarningEngine warningEngine;

    private LifeMapDtos.DailySummary summary(LocalDate date, long totalSeconds, BigDecimal income,
            BigDecimal expense, Double sleep, List<LifeMapDtos.ProactiveWarning> warnings) {
        return new LifeMapDtos.DailySummary(date, totalSeconds, Map.of(), income, expense, null,
                0, 0, sleep, 0, 0, warnings);
    }

    @Test
    void recordUsesCategoryHintWhenProvided() throws Exception {
        LifeMapDtos.ActivityEntry entry = new LifeMapDtos.ActivityEntry(
                "act-1", Instant.now(), Instant.now(), 60, TimeCategory.SPORT, "App", "Title", "agent");
        when(activityStore.record(eq("user-1"), any(), any(), any(), any(), any(), eq(TimeCategory.SPORT), eq("agent")))
                .thenReturn(entry);

        mockMvc.perform(post("/api/v1/life-map/time-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "user-1", "appName": "App", "windowTitle": "Title",
                                 "durationSeconds": 60, "categoryHint": "SPORT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("SPORT"));
    }

    @Test
    void recordClassifiesWhenNoCategoryHintProvided() throws Exception {
        when(classifier.classify("IntelliJ", "Project")).thenReturn(TimeCategory.WORK);
        LifeMapDtos.ActivityEntry entry = new LifeMapDtos.ActivityEntry(
                "act-2", Instant.now(), Instant.now(), 60, TimeCategory.WORK, "IntelliJ", "Project", "agent");
        when(activityStore.record(eq("user-1"), any(), any(), any(), any(), any(), eq(TimeCategory.WORK), eq("agent")))
                .thenReturn(entry);

        mockMvc.perform(post("/api/v1/life-map/time-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "user-1", "appName": "IntelliJ", "windowTitle": "Project",
                                 "durationSeconds": 60}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("WORK"));
    }

    @Test
    void recordWithBlankUserIdReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/life-map/time-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appName": "App"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activityReturnsEntriesForRequestedDay() throws Exception {
        when(activityStore.entriesForDay("user-1", LocalDate.of(2026, 3, 10)))
                .thenReturn(List.of(new LifeMapDtos.ActivityEntry(
                        "act-3", Instant.now(), Instant.now(), 10, TimeCategory.REST, "A", "B", "agent")));

        mockMvc.perform(get("/api/v1/life-map/activity")
                        .param("userId", "user-1")
                        .param("date", "2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void activityDefaultsToTodayWhenDateMissing() throws Exception {
        when(activityStore.entriesForDay(eq("user-1"), eq(LocalDate.now()))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/life-map/activity").param("userId", "user-1"))
                .andExpect(status().isOk());
    }

    @Test
    void summaryDelegatesToSummaryService() throws Exception {
        when(summaryService.summarise(eq("user-1"), eq(LocalDate.of(2026, 3, 10))))
                .thenReturn(summary(LocalDate.of(2026, 3, 10), 100L, BigDecimal.TEN, BigDecimal.ONE, 7.0, List.of()));

        mockMvc.perform(get("/api/v1/life-map/summary")
                        .param("userId", "user-1")
                        .param("date", "2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTrackedSeconds").value(100));
    }

    @Test
    void weekSummaryAggregatesSevenDaysOfData() throws Exception {
        LocalDate end = LocalDate.of(2026, 3, 10);
        for (int i = 0; i <= 6; i++) {
            LocalDate day = end.minusDays(i);
            when(summaryService.summarise("user-1", day)).thenReturn(
                    summary(day, 3600L, new BigDecimal("10.00"), new BigDecimal("5.00"), 7.0, List.of()));
        }

        mockMvc.perform(get("/api/v1/life-map/summary/week")
                        .param("userId", "user-1")
                        .param("to", "2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-03-04"))
                .andExpect(jsonPath("$.to").value("2026-03-10"))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.income").value(70.00))
                .andExpect(jsonPath("$.expense").value(35.00))
                .andExpect(jsonPath("$.avgSleepHours").value(7.0));
    }

    @Test
    void weekSummaryHandlesMissingFinanceAndSleepData() throws Exception {
        LocalDate end = LocalDate.of(2026, 3, 10);
        for (int i = 0; i <= 6; i++) {
            LocalDate day = end.minusDays(i);
            when(summaryService.summarise("user-1", day)).thenReturn(
                    summary(day, 0L, null, null, null, List.of()));
        }

        mockMvc.perform(get("/api/v1/life-map/summary/week")
                        .param("userId", "user-1")
                        .param("to", "2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(0))
                .andExpect(jsonPath("$.expense").value(0))
                .andExpect(jsonPath("$.avgSleepHours").doesNotExist());
    }

    @Test
    void weekSummaryDefaultsToTodayWhenToMissing() throws Exception {
        when(summaryService.summarise(eq("user-1"), any(LocalDate.class))).thenReturn(
                summary(LocalDate.now(), 0L, null, null, null, List.of()));

        mockMvc.perform(get("/api/v1/life-map/summary/week").param("userId", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(LocalDate.now().toString()));
    }

    @Test
    void warningsDelegatesToSummaryServiceWarnings() throws Exception {
        LifeMapDtos.ProactiveWarning warning = new LifeMapDtos.ProactiveWarning(
                "warn-1", "TIME_WASTE", LifeMapDtos.ProactiveWarning.Severity.WARN, "msg",
                Map.of(), Instant.now());
        when(summaryService.summarise(eq("user-1"), eq(LocalDate.of(2026, 3, 10))))
                .thenReturn(summary(LocalDate.of(2026, 3, 10), 0L, null, null, null, List.of(warning)));

        mockMvc.perform(get("/api/v1/life-map/warnings")
                        .param("userId", "user-1")
                        .param("date", "2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TIME_WASTE"));
    }

    @Test
    void explanationReturnsNotFoundWhenUnknownWarningId() throws Exception {
        when(warningEngine.explanation("missing")).thenReturn(null);

        mockMvc.perform(get("/api/v1/life-map/recommendations/missing/explanation"))
                .andExpect(status().isNotFound());
    }

    @Test
    void explanationReturnsRecommendationWhenFound() throws Exception {
        LifeMapDtos.RecommendationExplanation explanation = new LifeMapDtos.RecommendationExplanation(
                "warn-1", "TIME_WASTE", "REST > threshold", "narrative", Map.of(), Instant.now());
        when(warningEngine.explanation("warn-1")).thenReturn(explanation);

        mockMvc.perform(get("/api/v1/life-map/recommendations/warn-1/explanation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TIME_WASTE"));
    }
}
