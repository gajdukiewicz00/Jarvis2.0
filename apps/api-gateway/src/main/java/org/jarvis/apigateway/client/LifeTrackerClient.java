package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "life-tracker", url = "${services.life-tracker.url:http://localhost:8085}")
public interface LifeTrackerClient {

    // Expenses
    @PostMapping("/api/v1/life/finance/expenses")
    ResponseEntity<Map<String, Object>> addExpense(@RequestBody Map<String, Object> expense);

    @GetMapping("/api/v1/life/finance/expenses")
    ResponseEntity<List<Map<String, Object>>> getExpenses();

    // Time Tracking
    @PostMapping("/api/v1/life/time/start")
    ResponseEntity<Map<String, Object>> startTimer(@RequestBody Map<String, String> request);

    @PostMapping("/api/v1/life/time/stop")
    ResponseEntity<Map<String, Object>> stopTimer();

    @GetMapping("/api/v1/life/time/records")
    ResponseEntity<List<Map<String, Object>>> getTimeRecords();

    // Calendar
    @PostMapping("/api/v1/life/calendar/event")
    ResponseEntity<Map<String, Object>> addEvent(@RequestBody Map<String, Object> event);

    @GetMapping("/api/v1/life/calendar/events")
    ResponseEntity<List<Map<String, Object>>> getEvents();

    // Tool API - Calendar
    @PostMapping("/api/v1/tools/calendar/create")
    ResponseEntity<Map<String, Object>> toolCreateEvent(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/calendar/move")
    ResponseEntity<Map<String, Object>> toolMoveEvent(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/calendar/list")
    ResponseEntity<List<Map<String, Object>>> toolListEvents(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/calendar/free-slot")
    ResponseEntity<Map<String, Object>> toolFindFreeSlot(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);

    // Tool API - Finance (read-only)
    @PostMapping("/api/v1/tools/finance/transactions")
    ResponseEntity<List<Map<String, Object>>> toolListTransactions(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/finance/summary")
    ResponseEntity<Map<String, Object>> toolSummarizeMonth(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/finance/analysis")
    ResponseEntity<Map<String, Object>> toolAnalyzeSpending(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/finance/budget-status")
    ResponseEntity<Map<String, Object>> toolBudgetStatus(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);
}
