package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@FeignClient(name = "analytics", url = "${services.analytics.url:http://localhost:8087}")
public interface AnalyticsClient {

    @GetMapping("/api/v1/analytics/overview")
    ResponseEntity<org.jarvis.apigateway.dto.AnalyticsOverviewDTO> getOverview();

    @GetMapping("/api/v1/analytics/expenses/by-month")
    ResponseEntity<List<Map<String, Object>>> getExpensesByMonth(
            @RequestParam(value = "from", required = false) LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to);

    @GetMapping("/api/v1/analytics/expenses/by-category")
    ResponseEntity<List<Map<String, Object>>> getExpensesByCategory(
            @RequestParam(value = "from", required = false) LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to);

    @GetMapping("/api/v1/analytics/time/summary")
    ResponseEntity<List<Map<String, Object>>> getTimeStatistics();

    @GetMapping("/api/v1/analytics/calendar/summary")
    ResponseEntity<Map<String, Object>> getCalendarStatistics();
}
