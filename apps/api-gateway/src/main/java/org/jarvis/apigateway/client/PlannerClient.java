package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

@FeignClient(name = "planner-service", url = "${services.planner.url:http://planner-service:8092}")
public interface PlannerClient {

    @PostMapping("/api/v1/tools/todo/create")
    ResponseEntity<Map<String, Object>> createTodo(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/todo/update")
    ResponseEntity<Map<String, Object>> updateTodo(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/todo/complete")
    ResponseEntity<Map<String, Object>> completeTodo(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload);

    @PostMapping("/api/v1/tools/todo/list")
    ResponseEntity<List<Map<String, Object>>> listTodos(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);
}
