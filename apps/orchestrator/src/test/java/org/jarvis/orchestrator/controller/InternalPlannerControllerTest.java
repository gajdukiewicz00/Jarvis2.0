package org.jarvis.orchestrator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.jarvis.orchestrator.client.ApiGatewayPlannerClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InternalPlannerControllerTest {

    private final ApiGatewayPlannerClient client = mock(ApiGatewayPlannerClient.class);
    private final InternalPlannerController controller = new InternalPlannerController(client);

    @Test
    void focusForwardsUserAndReturnsPlannerData() {
        when(client.getFocus(eq("2"))).thenReturn(Map.of("hasFocus", true, "openTasks", 4, "title", "Отчёт"));

        ResponseEntity<Map<String, Object>> response = controller.focus("2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(4, response.getBody().get("openTasks"));
    }

    @Test
    void focusReturns503WhenPlannerUnavailable() {
        when(client.getFocus(eq("2"))).thenThrow(new RuntimeException("connection refused"));

        ResponseEntity<Map<String, Object>> response = controller.focus("2");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("PLANNER_ENDPOINT_UNAVAILABLE"));
    }
}
