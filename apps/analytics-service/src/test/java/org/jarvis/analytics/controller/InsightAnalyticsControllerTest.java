package org.jarvis.analytics.controller;

import org.jarvis.analytics.dto.CorrelationResultDTO;
import org.jarvis.analytics.dto.NlAnalyticsResponseDTO;
import org.jarvis.analytics.service.AnomalyDetectionService;
import org.jarvis.analytics.service.ChangeAnalysisService;
import org.jarvis.analytics.service.ConsistencyService;
import org.jarvis.analytics.service.CorrelationService;
import org.jarvis.analytics.service.HabitStreakService;
import org.jarvis.analytics.service.MonthlyReportService;
import org.jarvis.analytics.service.NlAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@link InsightAnalyticsController}, mirroring
 * {@code AnalyticsControllerTest}'s {@code addFilters = false} pattern since
 * security filtering is already covered by {@link InsightControllerSecurityTest}.
 */
@WebMvcTest(controllers = InsightAnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
class InsightAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MonthlyReportService monthlyReportService;

    @MockBean
    private CorrelationService correlationService;

    @MockBean
    private ConsistencyService consistencyService;

    @MockBean
    private HabitStreakService habitStreakService;

    @MockBean
    private AnomalyDetectionService anomalyDetectionService;

    @MockBean
    private ChangeAnalysisService changeAnalysisService;

    @MockBean
    private NlAnalyticsService nlAnalyticsService;

    @Test
    void monthlyReportDelegatesToService() throws Exception {
        when(monthlyReportService.monthlyReport()).thenReturn(Map.of("month", "2026-03"));

        mockMvc.perform(get("/api/v1/analytics/insights/monthly-report").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month", is("2026-03")));
    }

    @Test
    void refinedForecastDelegatesToService() throws Exception {
        when(monthlyReportService.refinedOverspendForecast()).thenReturn(Map.of("adjustedDailyRate", 10.0));

        mockMvc.perform(get("/api/v1/analytics/insights/forecast/refined").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustedDailyRate", is(10.0)));
    }

    @Test
    void correlationEndpointReturnsCoefficientPayload() throws Exception {
        when(correlationService.sleepProductivityCorrelation(7)).thenReturn(
                new CorrelationResultDTO("sleepHours", "workHours", 0.8, 7, "strong", "positive", "explanation"));

        mockMvc.perform(get("/api/v1/analytics/insights/correlation/sleep-productivity")
                        .param("days", "7").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strength", is("strong")));
    }

    @Test
    void whatChangedDelegatesToChangeAnalysisService() throws Exception {
        when(changeAnalysisService.whatChanged()).thenReturn(Map.of("summary", "test summary"));

        mockMvc.perform(get("/api/v1/analytics/insights/what-changed").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary", is("test summary")));
    }

    @Test
    void whyWeekBadDelegatesToChangeAnalysisService() throws Exception {
        when(changeAnalysisService.whyWeekWentBad()).thenReturn(Map.of("narrative", "all good"));

        mockMvc.perform(get("/api/v1/analytics/insights/why-week-bad").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.narrative", is("all good")));
    }

    @Test
    void askDelegatesQuestionToNlAnalyticsService() throws Exception {
        when(nlAnalyticsService.ask(isNull(), eq("Почему я устал?")))
                .thenReturn(new NlAnalyticsResponseDTO("Почему я устал?", "answer", false, "LLM_DISABLED"));

        mockMvc.perform(post("/api/v1/analytics/insights/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Почему я устал?\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("LLM_DISABLED")));
    }
}
