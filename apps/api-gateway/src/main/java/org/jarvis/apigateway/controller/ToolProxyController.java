package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.LifeTrackerClient;
import org.jarvis.apigateway.client.MemoryServiceClient;
import org.jarvis.apigateway.client.PlannerClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolProxyController {

    private final PlannerClient plannerClient;
    private final LifeTrackerClient lifeTrackerClient;
    private final MemoryServiceClient memoryServiceClient;

    @PostMapping("/todo/create")
    public ResponseEntity<Map<String, Object>> createTodo(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool create_todo");
        return plannerClient.createTodo(userId, idempotencyKey, payload);
    }

    @PostMapping("/todo/update")
    public ResponseEntity<Map<String, Object>> updateTodo(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool update_todo");
        return plannerClient.updateTodo(userId, idempotencyKey, payload);
    }

    @PostMapping("/todo/complete")
    public ResponseEntity<Map<String, Object>> completeTodo(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool complete_todo");
        return plannerClient.completeTodo(userId, idempotencyKey, payload);
    }

    @PostMapping("/todo/list")
    public ResponseEntity<List<Map<String, Object>>> listTodos(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool list_todos");
        return plannerClient.listTodos(userId, payload);
    }

    @PostMapping("/calendar/create")
    public ResponseEntity<Map<String, Object>> createEvent(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool create_event");
        return lifeTrackerClient.toolCreateEvent(userId, idempotencyKey, payload);
    }

    @PostMapping("/calendar/move")
    public ResponseEntity<Map<String, Object>> moveEvent(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool move_event");
        return lifeTrackerClient.toolMoveEvent(userId, idempotencyKey, payload);
    }

    @PostMapping("/calendar/list")
    public ResponseEntity<List<Map<String, Object>>> listEvents(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool list_events");
        return lifeTrackerClient.toolListEvents(userId, payload);
    }

    @PostMapping("/calendar/free-slot")
    public ResponseEntity<Map<String, Object>> findFreeSlot(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool find_free_slot");
        return lifeTrackerClient.toolFindFreeSlot(userId, payload);
    }

    @PostMapping("/finance/transactions")
    public ResponseEntity<List<Map<String, Object>>> listTransactions(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool list_transactions");
        return lifeTrackerClient.toolListTransactions(userId, payload);
    }

    @PostMapping("/finance/summary")
    public ResponseEntity<Map<String, Object>> summarizeMonth(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool summarize_month");
        return lifeTrackerClient.toolSummarizeMonth(userId, payload);
    }

    @PostMapping("/finance/analysis")
    public ResponseEntity<Map<String, Object>> analyzeSpending(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool analyze_spending");
        return lifeTrackerClient.toolAnalyzeSpending(userId, payload);
    }

    @PostMapping("/finance/budget-status")
    public ResponseEntity<Map<String, Object>> budgetStatus(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool budget_status");
        return lifeTrackerClient.toolBudgetStatus(userId, payload);
    }

    @PostMapping("/memory/search")
    public ResponseEntity<Map<String, Object>> searchMemory(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool search_memory");
        return memoryServiceClient.searchMemory(userId, payload);
    }
}
