package org.jarvis.analytics.controller;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.CalendarStatisticsDTO;
import org.jarvis.analytics.dto.ChartDataDTO;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
import org.jarvis.analytics.dto.TimeStatisticsDTO;
import org.jarvis.analytics.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@link AnalyticsController} covering the endpoints not
 * already exercised by {@link AnalyticsControllerSecurityTest}, plus the
 * {@link org.jarvis.analytics.config.GlobalExceptionHandler} wiring for
 * raw-passthrough endpoints where Feign errors propagate.
 */
@WebMvcTest(controllers = AnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LifeTrackerClient lifeTrackerClient;

    @MockBean
    private AnalyticsService analyticsService;

    @Test
    void overviewReturnsZeroedMetricsWhenUpstreamListsAreEmpty() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of());
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/analytics/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpenses").value(0))
                .andExpect(jsonPath("$.expenseCount").value(0))
                .andExpect(jsonPath("$.totalTimeTrackedSeconds").value(0))
                .andExpect(jsonPath("$.timeRecordCount").value(0));
    }

    @Test
    void overviewFiltersNonExpenseTypesAndNullAmounts() throws Exception {
        ExpenseDTO expense = new ExpenseDTO();
        expense.setAmount(new BigDecimal("25.50"));
        expense.setType("EXPENSE");
        ExpenseDTO income = new ExpenseDTO();
        income.setAmount(new BigDecimal("1000.00"));
        income.setType("INCOME");
        ExpenseDTO nullAmount = new ExpenseDTO();
        nullAmount.setAmount(null);
        nullAmount.setType("EXPENSE");

        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(expense, income, nullAmount));
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/analytics/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpenses").value(25.50))
                .andExpect(jsonPath("$.expenseCount").value(2));
    }

    @Test
    void overviewReportsTimeoutErrorWhenExpensesCallIsRetryable() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenThrow(retryable("http://life-tracker:8085/api/v1/life/finance/expenses"));
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/analytics/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesError").value("Life-tracker service timeout"));
    }

    @Test
    void overviewReportsUpstreamStatusWhenExpensesCallFailsWithFeignException() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenThrow(feignException(500, "expenses down"));
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/analytics/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesError").value("Life-tracker service error: 500"));
    }

    @Test
    void overviewReportsTimeoutErrorWhenTimeRecordsCallIsRetryable() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of());
        when(lifeTrackerClient.getTimeRecords())
                .thenThrow(retryable("http://life-tracker:8085/api/v1/life/time/records"));

        mockMvc.perform(get("/api/v1/analytics/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeError").value("Life-tracker service timeout"));
    }

    @Test
    void overviewReportsUpstreamStatusWhenTimeRecordsCallFailsWithFeignException() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of());
        when(lifeTrackerClient.getTimeRecords()).thenThrow(feignException(502, "time-records down"));

        mockMvc.perform(get("/api/v1/analytics/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeError").value("Life-tracker service error: 502"));
    }

    @Test
    void overviewReportsUnexpectedErrorWhenTimeRecordsCallThrowsRuntimeException() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of());
        when(lifeTrackerClient.getTimeRecords()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/v1/analytics/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeError").value("Unexpected error fetching time records"));
    }

    @Test
    void rawExpensesReturnsListFromClient() throws Exception {
        ExpenseDTO expense = new ExpenseDTO();
        expense.setId(1L);
        expense.setAmount(new BigDecimal("12.00"));
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(expense));

        mockMvc.perform(get("/api/v1/analytics/raw/expenses").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].amount").value("12.00"));
    }

    @Test
    void rawExpensesReturnsEmptyListWhenClientReturnsNull() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenReturn(null);

        mockMvc.perform(get("/api/v1/analytics/raw/expenses").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rawExpensesPropagatesFeignErrorsToGlobalExceptionHandler() throws Exception {
        when(lifeTrackerClient.getExpenses()).thenThrow(feignException(500, "boom"));

        mockMvc.perform(get("/api/v1/analytics/raw/expenses").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("UPSTREAM_ERROR"))
                .andExpect(jsonPath("$.service").value("analytics-service"));
    }

    @Test
    void rawTimeRecordsReturnsListFromClient() throws Exception {
        TimeRecordDTO record = new TimeRecordDTO(1L, "Coding", "Work",
                LocalDateTime.of(2026, 3, 13, 9, 0), LocalDateTime.of(2026, 3, 13, 11, 0), 7200L);
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(record));

        mockMvc.perform(get("/api/v1/analytics/raw/time-records").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activity").value("Coding"));
    }

    @Test
    void rawTimeRecordsReturnsEmptyListWhenClientReturnsNull() throws Exception {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(null);

        mockMvc.perform(get("/api/v1/analytics/raw/time-records").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void expensesByMonthDelegatesToAnalyticsService() throws Exception {
        when(analyticsService.getExpensesByMonth(null, null))
                .thenReturn(List.of(new ExpenseSummaryDTO("2026-03", "All", new BigDecimal("10.00"), "EUR", 1)));

        mockMvc.perform(get("/api/v1/analytics/expenses/by-month").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].period").value("2026-03"));
    }

    @Test
    void expensesByCategoryDelegatesToAnalyticsService() throws Exception {
        when(analyticsService.getExpensesByCategory(null, null))
                .thenReturn(List.of(new ExpenseSummaryDTO("All", "Food", new BigDecimal("42.00"), "EUR", 3)));

        mockMvc.perform(get("/api/v1/analytics/expenses/by-category").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("Food"));
    }

    @Test
    void expenseTrendDelegatesToAnalyticsServiceWithDefaultPeriod() throws Exception {
        when(analyticsService.getExpenseTrend("month", null, null))
                .thenReturn(new ChartDataDTO("line", List.of("2026-03"), List.of(10), "Expense Trend", "Period", "Amount"));

        mockMvc.perform(get("/api/v1/analytics/expenses/trend").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("line"))
                .andExpect(jsonPath("$.title").value("Expense Trend"));
    }

    @Test
    void timeStatisticsDelegatesToAnalyticsService() throws Exception {
        when(analyticsService.getTimeStatistics())
                .thenReturn(List.of(new TimeStatisticsDTO("Work", 5.0, 2, 2.5)));

        mockMvc.perform(get("/api/v1/analytics/time/summary").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("Work"));
    }

    @Test
    void calendarStatisticsDelegatesToAnalyticsService() throws Exception {
        when(analyticsService.getCalendarStatistics())
                .thenReturn(new CalendarStatisticsDTO(5, 2, 3, 1));

        mockMvc.perform(get("/api/v1/analytics/calendar/summary").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(5));
    }

    @Test
    void sleepAverageDelegatesToAnalyticsServiceWithDefaultDays() throws Exception {
        when(analyticsService.getSleepSummary(14)).thenReturn(new SleepSummaryDTO(7.5, 3, 14, 22.5));

        mockMvc.perform(get("/api/v1/analytics/habits/sleep-average").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageHours").value(7.5));
    }

    @Test
    void sleepAverageHonorsCustomDaysParameter() throws Exception {
        when(analyticsService.getSleepSummary(30)).thenReturn(new SleepSummaryDTO(6.0, 10, 30, 60.0));

        mockMvc.perform(get("/api/v1/analytics/habits/sleep-average")
                        .param("days", "30")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailingDays").value(30));
    }

    @Test
    void weeklyOvertimeDelegatesToAnalyticsServiceWithDefaults() throws Exception {
        when(analyticsService.getOvertimeSummary(7, 40)).thenReturn(new OvertimeSummaryDTO(6, 46.0, 40, 7));

        mockMvc.perform(get("/api/v1/analytics/habits/weekly-overtime").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overtimeHours").value(6));
    }

    @Test
    void weeklyOvertimeHonorsCustomParameters() throws Exception {
        when(analyticsService.getOvertimeSummary(14, 80)).thenReturn(new OvertimeSummaryDTO(0, 70.0, 80, 14));

        mockMvc.perform(get("/api/v1/analytics/habits/weekly-overtime")
                        .param("days", "14")
                        .param("baselineHours", "80")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overtimeHours").value(0))
                .andExpect(jsonPath("$.baselineHours").value(80));
    }

    @Test
    void sleepAverageInvalidDaysPropagatesToGlobalExceptionHandlerAsBadRequest() throws Exception {
        when(analyticsService.getSleepSummary(0))
                .thenThrow(new IllegalArgumentException("trailingDays must be greater than zero"));

        mockMvc.perform(get("/api/v1/analytics/habits/sleep-average")
                        .param("days", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("trailingDays must be greater than zero"));
    }

    private RetryableException retryable(String url) {
        return new RetryableException(
                -1,
                "Connection refused executing GET " + url,
                Request.HttpMethod.GET,
                new ConnectException("Connection refused"),
                (Long) null,
                Request.create(Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8, null));
    }

    private FeignException feignException(int status, String message) {
        Request request = Request.create(Request.HttpMethod.GET,
                "http://life-tracker:8085/x", Map.of(), null, StandardCharsets.UTF_8, null);
        return new StubFeignException(status, message, request);
    }

    private static final class StubFeignException extends FeignException {
        private StubFeignException(int status, String message, Request request) {
            super(status, message, request, new byte[0], Map.of());
        }
    }
}
