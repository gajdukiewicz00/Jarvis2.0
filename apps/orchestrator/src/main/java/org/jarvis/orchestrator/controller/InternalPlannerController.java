package org.jarvis.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.ApiGatewayPlannerClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal planner passthrough for voice-gateway. voice-gateway cannot reach planner-service
 * or api-gateway directly (NetworkPolicy); it IS allowed to reach the orchestrator, which
 * forwards to the api-gateway planner proxy → planner-service. Returns the raw planner
 * focus/daily maps so the voice layer builds its spoken summary exactly as before.
 */
@Slf4j
@RestController
@RequestMapping("/internal/planner")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalPlannerController {

    private final ApiGatewayPlannerClient plannerClient;

    @GetMapping("/focus")
    public ResponseEntity<Map<String, Object>> focus(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String scopedUser = userId != null && !userId.isBlank() ? userId : "local-user";
        try {
            Map<String, Object> focus = plannerClient.getFocus(scopedUser);
            return ResponseEntity.ok(focus != null ? focus : Map.of());
        } catch (RuntimeException e) {
            log.warn("🗓️ Planner focus passthrough failed: userId={}, error={}", scopedUser, e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "PLANNER_ENDPOINT_UNAVAILABLE",
                    "message", e.getMessage() != null ? e.getMessage() : "planner endpoint unavailable"));
        }
    }

    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> daily(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String scopedUser = userId != null && !userId.isBlank() ? userId : "local-user";
        try {
            Map<String, Object> daily = plannerClient.getDaily(scopedUser);
            return ResponseEntity.ok(daily != null ? daily : Map.of());
        } catch (RuntimeException e) {
            log.warn("🗓️ Planner daily passthrough failed: userId={}, error={}", scopedUser, e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "PLANNER_ENDPOINT_UNAVAILABLE",
                    "message", e.getMessage() != null ? e.getMessage() : "planner endpoint unavailable"));
        }
    }
}
