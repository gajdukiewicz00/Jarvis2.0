package org.jarvis.apigateway.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for desktop-side PC control sessions.
 *
 * Sessions are tracked by authenticated user ID so internal services can route
 * actions to the correct desktop instead of broadcasting blindly.
 */
@Slf4j
@Component
public class PcControlWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ClientSession> clientSessions = new ConcurrentHashMap<>();

    private record ClientSession(
            WebSocketSession session,
            String clientType,
            String clientId,
            String userId,
            String username) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = principalName(session.getPrincipal());
        log.info("🔌 PC Control WebSocket connected: session={}, userId={}", session.getId(), userId);
        clientSessions.put(session.getId(), new ClientSession(session, "UNKNOWN", session.getId(), userId, null));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("Received from {}: {}", session.getId(), payload);

        try {
            JsonNode json = objectMapper.readTree(payload);
            String type = json.has("type") ? json.get("type").asText() : "";

            switch (type) {
                case "IDENTIFY" -> handleIdentify(session, json);
                case "ACK" -> handleAck(session, json);
                case "PONG" -> log.debug("Received PONG from {}", session.getId());
                default -> log.debug("Unknown message type: {}", type);
            }
        } catch (IOException | RuntimeException e) {
            log.error("Error processing message: {}", payload, e);
        }
    }

    private void handleIdentify(WebSocketSession session, JsonNode json) {
        String client = readText(json, "client", "unknown");
        String clientId = readText(json, "clientId", session.getId());
        String username = readText(json, "username", null);
        String requestedUserId = readText(json, "userId", null);
        String normalizedClient = normalizeClientType(client);
        String resolvedUserId = principalName(session.getPrincipal());
        if (resolvedUserId == null || resolvedUserId.isBlank()) {
            resolvedUserId = requestedUserId;
        }

        clientSessions.put(
                session.getId(),
                new ClientSession(session, normalizedClient, clientId, resolvedUserId, username));

        log.info(
                "✅ Client identified: type={}, session={}, clientId={}, userId={}, username={}",
                normalizedClient,
                session.getId(),
                clientId,
                resolvedUserId,
                username);

        sendMessage(session, createMessage("ACK", "message", "Connected to PC Control"));
    }

    private void handleAck(WebSocketSession session, JsonNode json) {
        String action = readText(json, "action", "");
        boolean success = json.has("success") && json.get("success").asBoolean();
        log.info("Action {} completed: {}", action, success ? "✓" : "✗");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("🔌 PC Control WebSocket disconnected: {} ({})", session.getId(), status);
        clientSessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error for {}: {}", session.getId(), exception.getMessage());
        clientSessions.remove(session.getId());
    }

    public void sendPcAction(String action, JsonNode params) {
        String messageStr = pcActionMessage(action, params);
        long targetCount = clientSessions.values().stream()
                .filter(this::isPcControlClient)
                .filter(cs -> cs.session().isOpen())
                .peek(cs -> sendMessage(cs.session(), messageStr))
                .count();

        log.info("📤 Sending PC action '{}' to {} PC_CONTROL clients (connected: {})",
                action, targetCount, clientSessions.size());
    }

    public boolean sendPcActionToSession(String sessionId, String action, JsonNode params) {
        ClientSession clientSession = clientSessions.get(sessionId);
        if (clientSession != null && clientSession.session().isOpen()) {
            sendMessage(clientSession.session(), pcActionMessage(action, params));
            return true;
        }
        log.warn("Session {} not found or closed for action {}", sessionId, action);
        return false;
    }

    public int sendPcActionToUser(String userId, String action, JsonNode params) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }

        String messageStr = pcActionMessage(action, params);
        int targetCount = (int) clientSessions.values().stream()
                .filter(this::isPcControlClient)
                .filter(cs -> userId.equals(cs.userId()))
                .filter(cs -> cs.session().isOpen())
                .peek(cs -> sendMessage(cs.session(), messageStr))
                .count();

        log.info("📤 Sending PC action '{}' to {} PC_CONTROL clients for user {}",
                action, targetCount, userId);
        return targetCount;
    }

    public int getConnectedClientsCount() {
        return (int) clientSessions.values().stream()
                .filter(this::isPcControlClient)
                .filter(cs -> cs.session().isOpen())
                .count();
    }

    public boolean hasConnectedClients() {
        return getConnectedClientsCount() > 0;
    }

    private String pcActionMessage(String action, JsonNode params) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "PC_ACTION");
        message.put("action", action);
        message.set("params", params != null ? params : objectMapper.createObjectNode());
        return message.toString();
    }

    private String createMessage(String type, String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put(key, value);
        return node.toString();
    }

    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (IOException e) {
            log.error("Failed to send message to {}: {}", session.getId(), e.getMessage());
        }
    }

    private boolean isPcControlClient(ClientSession clientSession) {
        return "PC_CONTROL_CLIENT".equalsIgnoreCase(clientSession.clientType())
                || "DESKTOP".equalsIgnoreCase(clientSession.clientType());
    }

    private String normalizeClientType(String client) {
        if (client == null) {
            return "UNKNOWN";
        }
        String upper = client.trim().toUpperCase();
        return switch (upper) {
            case "DESKTOP", "PC_CONTROL", "PC_CONTROL_CLIENT" -> "PC_CONTROL_CLIENT";
            default -> upper;
        };
    }

    private String principalName(Principal principal) {
        return principal != null ? principal.getName() : null;
    }

    private String readText(JsonNode json, String field, String defaultValue) {
        return json.has(field) ? json.get(field).asText() : defaultValue;
    }
}
