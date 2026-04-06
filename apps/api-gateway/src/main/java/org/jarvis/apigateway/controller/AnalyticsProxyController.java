package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.AnalyticsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsProxyController {

    private final AnalyticsClient analyticsClient;
    @Value("${services.analytics.url}")
    private String analyticsServiceUrl;

    @GetMapping("/overview")
    public ResponseEntity<org.jarvis.apigateway.dto.AnalyticsOverviewDTO> getOverview(
            @RequestHeader(value = "X-Smoke-Run-Id", required = false) String smokeRunId) {
        log.info("Proxying analytics overview request to {} (smokeRunId={})",
                analyticsServiceUrl, smokeRunId != null ? smokeRunId : "none");
        return analyticsClient.getOverview();
    }

    @GetMapping("/expenses/by-month")
    public ResponseEntity<List<Map<String, Object>>> getExpensesByMonth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        log.info("Proxying GET /api/v1/analytics/expenses/by-month (from={}, to={})", from, to);
        return analyticsClient.getExpensesByMonth(from, to);
    }

    @GetMapping("/expenses/by-category")
    public ResponseEntity<List<Map<String, Object>>> getExpensesByCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        log.info("Proxying GET /api/v1/analytics/expenses/by-category (from={}, to={})", from, to);
        return analyticsClient.getExpensesByCategory(from, to);
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
