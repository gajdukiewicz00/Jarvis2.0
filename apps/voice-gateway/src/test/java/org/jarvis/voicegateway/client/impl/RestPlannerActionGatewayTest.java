package org.jarvis.voicegateway.client.impl;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.PlannerActionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Planner reads now go through the orchestrator passthrough (/internal/planner/focus) because
 * voice-gateway is NetworkPolicy-blocked from planner-service. A genuine planner outage must
 * surface as a planner-specific reason, never a PC-control error.
 */
class RestPlannerActionGatewayTest {

    private static final String FOCUS_URL = "http://orchestrator:8083/internal/planner/focus";

    private RestPlannerActionGateway gateway;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        ServiceJwtProvider serviceJwtProvider = mock(ServiceJwtProvider.class);
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenReturn("svc-token");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        gateway = new RestPlannerActionGateway(builder, serviceJwtProvider);
        ReflectionTestUtils.setField(gateway, "plannerDispatchUrl", "http://orchestrator:8083");
        ReflectionTestUtils.setField(gateway, "serviceName", "voice-gateway");
    }

    @Test
    void returnsRealSummaryWhenPlannerFocusAvailable() {
        server.expect(requestTo(FOCUS_URL))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andExpect(header("X-User-Id", "2"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"hasFocus\":true,\"title\":\"Закрыть отчёт\",\"openTasks\":4}"));

        PlannerActionGateway.PlannerResult result = gateway.summarizeDay("2", "ru-RU", "PLANNER_TODAY");

        assertTrue(result.success());
        assertTrue(result.spokenSummary().contains("4 задачи"), result.spokenSummary());
        assertTrue(result.spokenSummary().contains("Закрыть отчёт"), result.spokenSummary());
        server.verify();
    }

    @Test
    void returnsPlannerSpecificErrorWhenEndpointReturns503() {
        server.expect(requestTo(FOCUS_URL)).andRespond(withStatus(SERVICE_UNAVAILABLE));

        PlannerActionGateway.PlannerResult result = gateway.summarizeDay("2", "ru-RU", "PLANNER_TODAY");

        assertFalse(result.success());
        assertTrue(result.failureReason().contains("PLANNER_ENDPOINT_HTTP_503"), result.failureReason());
    }
}
