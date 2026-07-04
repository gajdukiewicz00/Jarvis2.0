package org.jarvis.analytics.controller;

import org.jarvis.analytics.config.SecurityConfig;
import org.jarvis.analytics.dto.InsightDTO;
import org.jarvis.analytics.service.InsightService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InsightController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, ServiceJwtFilter.class, ServiceJwtProvider.class})
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456",
        "service.jwt.required=true"
})
class InsightControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private InsightService insightService;

    @Test
    void serviceTokenWithoutDelegatedUserContextIsRejected() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));

        mockMvc.perform(get("/api/v1/analytics/insights")
                        .header("X-Service-Token", serviceToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Missing X-User-Id delegated user context")));

        verifyNoInteractions(insightService);
    }

    @Test
    void serviceTokenInAuthorizationHeaderIsRejected() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));

        mockMvc.perform(get("/api/v1/analytics/insights")
                        .header("Authorization", "Bearer " + serviceToken)
                        .header("X-User-Id", "user-123")
                        .header("X-User-Roles", "ROLE_USER")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        verifyNoInteractions(insightService);
    }

    @Test
    void insightsReturnsOkWhenDelegatedUserContextIsPresent() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));
        when(insightService.autoInsights()).thenReturn(List.of(
                new InsightDTO("ALL_GOOD", "Всё спокойно", "Заметных аномалий не обнаружено.", "INFO")));

        mockMvc.perform(get("/api/v1/analytics/insights")
                        .header("X-Service-Token", serviceToken)
                        .header("X-User-Id", "user-123")
                        .header("X-User-Roles", "ROLE_USER")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code", is("ALL_GOOD")));
    }
}
