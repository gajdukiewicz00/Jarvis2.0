package org.jarvis.apigateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal controller for triggering PC actions via WebSocket.
 * Called by orchestrator or other services to send commands to desktop clients.
 */
@Slf4j
@RestController
@RequestMapping("/internal/pc-control")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class PcControlInternalController {

    private final PcControlWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Send a PC action command to connected desktop clients and wait for the
     * desktop ACK so callers can distinguish delivery from actual execution.
     */
    @PostMapping("/action")
    public ResponseEntity<?> sendAction(@RequestBody Map<String, Object> body) {
        String action = body.get("action") != null ? String.valueOf(body.get("action")) : null;
        JsonNode params = objectMapper.valueToTree(body.get("params"));
        String sessionId = body.get("sessionId") != null ? String.valueOf(body.get("sessionId")) : null;
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;
        String correlationId = body.get("correlationId") != null ? String.valueOf(body.get("correlationId")) : null;

        if (action == null || action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "action is required"));
        }

        log.info(
                "🎯 Triggering PC action: action={}, sessionId={}, userId={}, correlationId={}, params={}",
                action,
                sessionId,
                userId,
                correlationId,
                params);

        PcControlWebSocketHandler.DispatchResult result = webSocketHandler.dispatchPcAction(
                action,
                params,
                sessionId,
                userId,
                correlationId);

        return ResponseEntity.ok(toResponseBody(result));
    }

    /**
     * Get status of connected desktop clients.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(Map.of(
                "connectedClients", webSocketHandler.getConnectedClientsCount(),
                "hasClients", webSocketHandler.hasConnectedClients()));
    }

    private Map<String, Object> toResponseBody(PcControlWebSocketHandler.DispatchResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.put("action", result.action());
        payload.put("executorFound", result.executorFound());
        payload.put("executionAttempted", result.executionAttempted());
        payload.put("executionSucceeded", result.executionSucceeded());
        payload.put("executionFailed", result.executionFailed());
        payload.put("deliveredClients", result.deliveredClients());
        payload.put("acknowledgedClients", result.acknowledgedClients());
        payload.put("successfulClients", result.successfulClients());
        payload.put("failedClients", result.failedClients());
        if (result.requestId() != null) {
            payload.put("requestId", result.requestId());
        }
        if (result.sessionId() != null && !result.sessionId().isBlank()) {
            payload.put("sessionId", result.sessionId());
        }
        if (result.userId() != null && !result.userId().isBlank()) {
            payload.put("userId", result.userId());
        }
        if (result.failureReason() != null && !result.failureReason().isBlank()) {
            payload.put("failureReason", result.failureReason());
            payload.put("message", result.failureReason());
        }
        return payload;
    }
}
