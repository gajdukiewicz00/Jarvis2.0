package org.jarvis.apigateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal controller for triggering PC actions via WebSocket.
 * Called by orchestrator or other services to send commands to desktop clients.
 */
@Slf4j
@RestController
@RequestMapping("/internal/pc-control")
@RequiredArgsConstructor
public class PcControlInternalController {

    private final PcControlWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Send a PC action command to connected desktop clients.
     * 
     * Example body:
     * {
     * "action": "VOLUME_UP",
     * "params": {"delta": 10}
     * }
     */
    @PostMapping("/action")
    public ResponseEntity<?> sendAction(@RequestBody Map<String, Object> body) {
        String action = (String) body.get("action");
        JsonNode params = objectMapper.valueToTree(body.get("params"));
        String sessionId = body.get("sessionId") != null ? String.valueOf(body.get("sessionId")) : null;

        if (action == null || action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "action is required"));
        }

        if (!webSocketHandler.hasConnectedClients()) {
            log.warn("No desktop clients connected for PC action: {}", action);
            return ResponseEntity.ok(Map.of(
                    "status", "no_clients",
                    "message", "No desktop clients connected"));
        }

        if (sessionId != null && !sessionId.isBlank()) {
            log.info("🎯 Triggering PC action for session {}: {} with params: {}", sessionId, action, params);
            boolean sent = webSocketHandler.sendPcActionToSession(sessionId, action, params);
            if (!sent) {
                return ResponseEntity.status(404).body(Map.of(
                        "status", "session_not_found",
                        "sessionId", sessionId,
                        "action", action));
            }
            return ResponseEntity.ok(Map.of(
                    "status", "sent",
                    "action", action,
                    "sessionId", sessionId));
        }

        log.info("🎯 Triggering PC action (broadcast): {} with params: {}", action, params);
        webSocketHandler.sendPcAction(action, params);

        return ResponseEntity.ok(Map.of(
                "status", "sent",
                "action", action,
                "clients", webSocketHandler.getConnectedClientsCount()));
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
}
