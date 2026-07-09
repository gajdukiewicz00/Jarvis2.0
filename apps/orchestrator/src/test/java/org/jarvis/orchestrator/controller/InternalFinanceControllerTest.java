package org.jarvis.orchestrator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.jarvis.orchestrator.client.ApiGatewayFinanceClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InternalFinanceControllerTest {

    private final ApiGatewayFinanceClient client = mock(ApiGatewayFinanceClient.class);
    private final InternalFinanceController controller = new InternalFinanceController(client);

    @Test
    void summaryReturnsMonthDataAndBestEffortToday() {
        when(client.getMonthSummary(eq("2"), any()))
                .thenReturn(Map.of("totalExpense", 12000, "currency", "RUB",
                        "byCategory", Map.of("продукты", 5000)));
        when(client.getSpending(eq("2"), any(), any(), any()))
                .thenReturn(Map.of("buckets", java.util.List.of(Map.of("key", "продукты", "total", 800))));

        ResponseEntity<Map<String, Object>> response = controller.summary("2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(12000, response.getBody().get("monthExpense"));
        assertEquals("RUB", response.getBody().get("currency"));
        assertEquals(800.0, response.getBody().get("todayExpense"));
    }

    @Test
    void summaryStillSucceedsWhenTodayBestEffortFails() {
        when(client.getMonthSummary(eq("2"), any()))
                .thenReturn(Map.of("totalExpense", 5000, "currency", "RUB"));
        when(client.getSpending(eq("2"), any(), any(), any()))
                .thenThrow(new RuntimeException("bad date format"));

        ResponseEntity<Map<String, Object>> response = controller.summary("2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(5000, response.getBody().get("monthExpense"));
    }

    @Test
    void summaryReturns503WhenFinanceUnavailable() {
        when(client.getMonthSummary(eq("2"), any())).thenThrow(new RuntimeException("connection refused"));

        ResponseEntity<Map<String, Object>> response = controller.summary("2");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("FINANCE_ENDPOINT_UNAVAILABLE"));
    }
}
