package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.LifeTrackerClient;
import org.jarvis.apigateway.client.MemoryServiceClient;
import org.jarvis.apigateway.client.PlannerClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool create_todo");
        String userId = requireUserId();
        return plannerClient.createTodo(userId, idempotencyKey, payload);
    }

    @PostMapping("/todo/update")
    public ResponseEntity<Map<String, Object>> updateTodo(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool update_todo");
        String userId = requireUserId();
        return plannerClient.updateTodo(userId, idempotencyKey, payload);
    }

    @PostMapping("/todo/complete")
    public ResponseEntity<Map<String, Object>> completeTodo(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool complete_todo");
        String userId = requireUserId();
        return plannerClient.completeTodo(userId, idempotencyKey, payload);
    }

    @PostMapping("/todo/list")
    public ResponseEntity<List<Map<String, Object>>> listTodos(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool list_todos");
        String userId = requireUserId();
        return plannerClient.listTodos(userId, payload);
    }

    @PostMapping("/calendar/create")
    public ResponseEntity<Map<String, Object>> createEvent(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool create_event");
        String userId = requireUserId();
        return lifeTrackerClient.toolCreateEvent(userId, idempotencyKey, payload);
    }

    @PostMapping("/calendar/move")
    public ResponseEntity<Map<String, Object>> moveEvent(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool move_event");
        String userId = requireUserId();
        return lifeTrackerClient.toolMoveEvent(userId, idempotencyKey, payload);
    }

    @PostMapping("/calendar/list")
    public ResponseEntity<List<Map<String, Object>>> listEvents(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool list_events");
        String userId = requireUserId();
        return lifeTrackerClient.toolListEvents(userId, payload);
    }

    @PostMapping("/calendar/free-slot")
    public ResponseEntity<Map<String, Object>> findFreeSlot(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool find_free_slot");
        String userId = requireUserId();
        return lifeTrackerClient.toolFindFreeSlot(userId, payload);
    }

    @PostMapping("/finance/transactions")
    public ResponseEntity<List<Map<String, Object>>> listTransactions(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool list_transactions");
        String userId = requireUserId();
        return lifeTrackerClient.toolListTransactions(userId, payload);
    }

    @PostMapping("/finance/summary")
    public ResponseEntity<Map<String, Object>> summarizeMonth(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool summarize_month");
        String userId = requireUserId();
        return lifeTrackerClient.toolSummarizeMonth(userId, payload);
    }

    @PostMapping("/finance/analysis")
    public ResponseEntity<Map<String, Object>> analyzeSpending(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool analyze_spending");
        String userId = requireUserId();
        return lifeTrackerClient.toolAnalyzeSpending(userId, payload);
    }

    @PostMapping("/finance/budget-status")
    public ResponseEntity<Map<String, Object>> budgetStatus(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool budget_status");
        String userId = requireUserId();
        return lifeTrackerClient.toolBudgetStatus(userId, payload);
    }

    @PostMapping("/memory/search")
    public ResponseEntity<Map<String, Object>> searchMemory(
            @RequestBody Map<String, Object> payload) {
        log.info("Proxying tool search_memory");
        String userId = requireUserId();
        return memoryServiceClient.searchMemory(userId, payload);
    }

    private String requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication");
        }
        return authentication.getName();
    }
}
