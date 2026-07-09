package org.jarvis.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.ApiGatewayFinanceClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal finance passthrough for voice-gateway. voice-gateway cannot reach life-tracker or
 * api-gateway directly (NetworkPolicy); it IS allowed to reach the orchestrator, which forwards
 * to the api-gateway life-tracker proxy → life-tracker. Assembles a small, ready-to-speak
 * finance summary (month expense + top categories, plus today's spend best-effort).
 */
@Slf4j
@RestController
@RequestMapping("/internal/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalFinanceController {

    private final ApiGatewayFinanceClient financeClient;

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String scopedUser = userId != null && !userId.isBlank() ? userId : "local-user";
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            // Month total + top categories (the reliable, well-defined endpoint).
            String month = YearMonth.now().toString();
            Map<String, Object> monthSummary = financeClient.getMonthSummary(scopedUser, month);
            if (monthSummary != null) {
                out.put("month", month);
                out.put("monthExpense", monthSummary.get("totalExpense"));
                out.put("currency", monthSummary.get("currency"));
                out.put("topCategories", monthSummary.get("byCategory"));
            }
        } catch (RuntimeException e) {
            log.warn("💰 Finance month summary failed: userId={}, error={}", scopedUser, e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "FINANCE_ENDPOINT_UNAVAILABLE",
                    "message", e.getMessage() != null ? e.getMessage() : "finance endpoint unavailable"));
        }

        // Today's spend — best-effort; a format/endpoint hiccup must not fail the whole summary.
        try {
            ZoneId zone = ZoneId.systemDefault();
            String from = LocalDate.now(zone).atStartOfDay(zone).toInstant().toString();
            String to = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toString();
            Map<String, Object> spending = financeClient.getSpending(scopedUser, from, to, "category");
            Object buckets = spending != null ? spending.get("buckets") : null;
            if (buckets instanceof java.util.List<?> list) {
                double todayTotal = 0.0;
                for (Object b : list) {
                    if (b instanceof Map<?, ?> bucket) {
                        todayTotal += toDouble(bucket.get("total"));
                    }
                }
                out.put("todayExpense", todayTotal);
            }
        } catch (RuntimeException e) {
            log.debug("💰 Finance today spend best-effort failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(out);
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }
}
