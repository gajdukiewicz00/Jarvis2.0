package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.AnalyticsClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsProxyController {

    private final AnalyticsClient analyticsClient;

    @GetMapping("/overview")
    public ResponseEntity<org.jarvis.apigateway.dto.AnalyticsOverviewDTO> getOverview() {
        log.info("Proxying analytics overview request");
        return analyticsClient.getOverview();
    }

    @GetMapping("/expenses/by-month")
    public ResponseEntity<List<Map<String, Object>>> getExpensesByMonth() {
        log.info("Proxying GET /api/v1/analytics/expenses/by-month");
        return analyticsClient.getExpensesByMonth();
    }

    @GetMapping("/expenses/by-category")
    public ResponseEntity<List<Map<String, Object>>> getExpensesByCategory() {
        log.info("Proxying GET /api/v1/analytics/expenses/by-category");
        return analyticsClient.getExpensesByCategory();
    }

    @GetMapping("/time/summary")
    public ResponseEntity<List<Map<String, Object>>> getTimeStatistics() {
        log.info("Proxying GET /api/v1/analytics/time/summary");
        return analyticsClient.getTimeStatistics();
    }

    @GetMapping("/calendar/summary")
    public ResponseEntity<Map<String, Object>> getCalendarStatistics() {
        log.info("Proxying GET /api/v1/analytics/calendar/summary");
        return analyticsClient.getCalendarStatistics();
    }
}
