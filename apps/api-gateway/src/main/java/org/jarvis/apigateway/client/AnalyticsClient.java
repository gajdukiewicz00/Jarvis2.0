package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@FeignClient(name = "analytics", url = "${services.analytics.url:http://localhost:8087}")
public interface AnalyticsClient {

    @GetMapping("/api/v1/analytics/overview")
    ResponseEntity<org.jarvis.apigateway.dto.AnalyticsOverviewDTO> getOverview();

    @GetMapping("/api/v1/analytics/expenses/by-month")
    ResponseEntity<List<Map<String, Object>>> getExpensesByMonth();

    @GetMapping("/api/v1/analytics/expenses/by-category")
    ResponseEntity<List<Map<String, Object>>> getExpensesByCategory();

    @GetMapping("/api/v1/analytics/time/summary")
    ResponseEntity<List<Map<String, Object>>> getTimeStatistics();

    @GetMapping("/api/v1/analytics/calendar/summary")
    ResponseEntity<Map<String, Object>> getCalendarStatistics();
}
