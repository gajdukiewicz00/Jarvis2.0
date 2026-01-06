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
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for PC Control commands.
 * Desktop clients connect here to receive PC control commands from
 * orchestrator/voice.
 */
@Slf4j
@Component
public class PcControlWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ClientSession> clientSessions = new ConcurrentHashMap<>();

    private record ClientSession(WebSocketSession session, String clientType, String clientId) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("🔌 PC Control WebSocket connected: {}", session.getId());
        // Store session - will be identified properly when IDENTIFY message arrives
        clientSessions.put(session.getId(), new ClientSession(session, "UNKNOWN", session.getId()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
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
        } catch (Exception e) {
            log.error("Error processing message: {}", payload, e);
        }
    }

    private void handleIdentify(WebSocketSession session, JsonNode json) {
        String client = json.has("client") ? json.get("client").asText() : "unknown";
        String clientId = json.has("clientId") ? json.get("clientId").asText() : session.getId();
        String normalizedClient = normalizeClientType(client);

        clientSessions.put(session.getId(), new ClientSession(session, normalizedClient, clientId));

        log.info("✅ Client identified: {} (session: {}, clientId: {})", normalizedClient, session.getId(), clientId);

        // Send acknowledgement
        sendMessage(session, createMessage("ACK", "message", "Connected to PC Control"));
    }

    private void handleAck(WebSocketSession session, JsonNode json) {
        String action = json.has("action") ? json.get("action").asText() : "";
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

    /**
     * Send a PC action command to all connected desktop clients.
     * Called by orchestrator or other services.
     */
    public void sendPcAction(String action, JsonNode params) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "PC_ACTION");
        message.put("action", action);
        message.set("params", params != null ? params : objectMapper.createObjectNode());

        String messageStr = message.toString();
        long targetCount = clientSessions.values().stream()
                .filter(this::isPcControlClient)
                .filter(cs -> cs.session().isOpen())
                .peek(cs -> sendMessage(cs.session(), messageStr))
                .count();

        log.info("📤 Sending PC action '{}' to {} PC_CONTROL clients (connected: {})",
                action, targetCount, clientSessions.size());
    }

    /**
     * Send a PC action to a specific session.
     */
    public boolean sendPcActionToSession(String sessionId, String action, JsonNode params) {
        ClientSession clientSession = clientSessions.get(sessionId);
        if (clientSession != null && clientSession.session().isOpen()) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "PC_ACTION");
            message.put("action", action);
            message.set("params", params != null ? params : objectMapper.createObjectNode());
            sendMessage(clientSession.session(), message.toString());
            return true;
        }
        log.warn("Session {} not found or closed for action {}", sessionId, action);
        return false;
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
}
