package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.LifeTrackerClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/life")
@RequiredArgsConstructor
public class LifeTrackerProxyController {

    private final LifeTrackerClient lifeClient;

    // Finance endpoints
    @PostMapping("/finance/expense")
    public ResponseEntity<Map<String, Object>> addExpense(@RequestBody Map<String, Object> expense) {
        log.info("Proxying POST /api/v1/life/finance/expense: amount={}", expense.get("amount"));
        return lifeClient.addExpense(expense);
    }

    @GetMapping("/finance/expenses")
    public ResponseEntity<List<Map<String, Object>>> getExpenses() {
        log.info("Proxying GET /api/v1/life/finance/expenses");
        return lifeClient.getExpenses();
    }

    // Time tracking endpoints
    @PostMapping("/time/start")
    public ResponseEntity<Map<String, Object>> startTimer(@RequestBody Map<String, String> request) {
        log.info("Proxying POST /api/v1/life/time/start: activity={}", request.get("activity"));
        return lifeClient.startTimer(request);
    }

    @PostMapping("/time/stop")
    public ResponseEntity<Map<String, Object>> stopTimer() {
        log.info("Proxying POST /api/v1/life/time/stop");
        return lifeClient.stopTimer();
    }

    @GetMapping("/time/records")
    public ResponseEntity<List<Map<String, Object>>> getTimeRecords() {
        log.info("Proxying GET /api/v1/life/time/records");
        return lifeClient.getTimeRecords();
    }

    // Calendar endpoints
    @PostMapping("/calendar/event")
    public ResponseEntity<Map<String, Object>> addEvent(@RequestBody Map<String, Object> event) {
        log.info("Proxying POST /api/v1/life/calendar/event: title={}", event.get("title"));
        return lifeClient.addEvent(event);
    }

    @GetMapping("/calendar/events")
    public ResponseEntity<List<Map<String, Object>>> getEvents() {
        log.info("Proxying GET /api/v1/life/calendar/events");
        return lifeClient.getEvents();
    }
}
