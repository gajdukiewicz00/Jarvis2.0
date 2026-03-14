package org.jarvis.planner.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnalyticsClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private AnalyticsClient analyticsClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        analyticsClient = new AnalyticsClient(restTemplate, "http://analytics-service:8087");
    }

    @Test
    void getAverageSleepHoursFetchesUserScopedMetricFromAnalyticsService() {
        server.expect(requestTo("http://analytics-service:8087/api/v1/analytics/habits/sleep-average?days=14"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-Id", "user-123"))
                .andRespond(withSuccess("""
                        {"averageHours":7.25,"daysSampled":4,"trailingDays":14,"totalSleepHours":29.0}
                        """, MediaType.APPLICATION_JSON));

        Double averageSleepHours = analyticsClient.getAverageSleepHours("user-123");

        assertEquals(7.25, averageSleepHours);
        server.verify();
    }

    @Test
    void getWeeklyOvertimeHoursFetchesUserScopedMetricFromAnalyticsService() {
        server.expect(requestTo("http://analytics-service:8087/api/v1/analytics/habits/weekly-overtime?days=7&baselineHours=40"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-Id", "user-123"))
                .andRespond(withSuccess("""
                        {"overtimeHours":6,"trackedWorkHours":46.0,"baselineHours":40,"trailingDays":7}
                        """, MediaType.APPLICATION_JSON));

        Integer overtimeHours = analyticsClient.getWeeklyOvertimeHours("user-123");

        assertEquals(6, overtimeHours);
        server.verify();
    }
}
