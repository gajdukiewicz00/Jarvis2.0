package org.jarvis.analytics.controller;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.config.SecurityConfig;
import org.jarvis.analytics.dto.AnalyticsOverviewDTO;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
import org.jarvis.analytics.service.AnalyticsService;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, ServiceJwtFilter.class, ServiceJwtProvider.class})
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456",
        "service.jwt.required=true"
})
class AnalyticsControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private LifeTrackerClient lifeTrackerClient;

    @MockBean
    private AnalyticsService analyticsService;

    @Test
    void serviceTokenWithoutDelegatedUserContextIsRejected() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));

        mockMvc.perform(get("/api/v1/analytics/overview")
                        .header("X-Service-Token", serviceToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Missing X-User-Id delegated user context")));
    }

    @Test
    void serviceTokenInAuthorizationHeaderIsRejected() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));

        mockMvc.perform(get("/api/v1/analytics/overview")
                        .header("Authorization", "Bearer " + serviceToken)
                        .header("X-User-Id", "user-123")
                        .header("X-User-Roles", "ROLE_USER")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        verifyNoInteractions(lifeTrackerClient, analyticsService);
    }

    @Test
    void overviewReturnsPartialPayloadWhenOneUpstreamCallFails() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));
        when(lifeTrackerClient.getExpenses()).thenThrow(new RuntimeException("life-tracker unavailable"));
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                new TimeRecordDTO(1L, "Coding", "Work", LocalDateTime.parse("2026-03-13T09:00:00"),
                        LocalDateTime.parse("2026-03-13T11:00:00"), 7200L)));

        mockMvc.perform(get("/api/v1/analytics/overview")
                        .header("X-Service-Token", serviceToken)
                        .header("X-User-Id", "user-123")
                        .header("X-User-Roles", "ROLE_USER")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesError").value("Unexpected error fetching expenses"))
                .andExpect(jsonPath("$.timeRecordCount").value(1))
                .andExpect(jsonPath("$.totalTimeTrackedSeconds").value(7200));
    }
}
