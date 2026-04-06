package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.client.AnalyticsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsProxyControllerTest {

    @Mock
    private AnalyticsClient analyticsClient;

    @InjectMocks
    private AnalyticsProxyController controller;

    @Test
    void getExpensesByCategoryForwardsDateRange() {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        List<Map<String, Object>> payload = List.of(Map.of("category", "Food", "amount", 42.0));
        when(analyticsClient.getExpensesByCategory(from, to)).thenReturn(ResponseEntity.ok(payload));

        ResponseEntity<List<Map<String, Object>>> response = controller.getExpensesByCategory(from, to);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(payload, response.getBody());
        verify(analyticsClient).getExpensesByCategory(from, to);
    }

    @Test
    void getExpensesByMonthForwardsDateRange() {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        List<Map<String, Object>> payload = List.of(Map.of("month", "2026-03", "amount", 42.0));
        when(analyticsClient.getExpensesByMonth(from, to)).thenReturn(ResponseEntity.ok(payload));

        ResponseEntity<List<Map<String, Object>>> response = controller.getExpensesByMonth(from, to);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(payload, response.getBody());
        verify(analyticsClient).getExpensesByMonth(from, to);
    }
}
