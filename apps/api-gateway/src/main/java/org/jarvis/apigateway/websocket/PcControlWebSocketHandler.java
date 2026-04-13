package org.jarvis.apigateway.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class PcControlWebSocketHandler extends TextWebSocketHandler {

    static final long DEFAULT_ACK_TIMEOUT_MS = 4_000L;
    static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    static final long STALE_SESSION_TIMEOUT_MS = 90_000L;

    private static final CloseStatus STALE_SESSION_CLOSE_STATUS = new CloseStatus(4001, "stale_session");
    private static final CloseStatus REPLACED_SESSION_CLOSE_STATUS = new CloseStatus(4002, "replaced_by_reconnect");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ClientSessionState> clientSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingAction> pendingActions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pc-control-ws-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public PcControlWebSocketHandler() {
        heartbeatExecutor.scheduleAtFixedRate(
                this::heartbeatSweep,
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private static final class ClientSessionState {
        private final WebSocketSession session;
        private volatile String clientType;
        private volatile String clientId;
        private volatile String userId;
        private volatile String username;
        private volatile long lastSeenAt;

        private ClientSessionState(WebSocketSession session,
                                   String clientType,
                                   String clientId,
                                   String userId,
                                   String username) {
            this.session = session;
            this.clientType = clientType;
            this.clientId = clientId;
            this.userId = userId;
            this.username = username;
            touch();
        }

        private void update(String clientType, String clientId, String userId, String username) {
            this.clientType = clientType;
            this.clientId = clientId;
            this.userId = userId;
            this.username = username;
            touch();
        }

        private void touch() {
            this.lastSeenAt = System.currentTimeMillis();
        }
    }

    public record DispatchResult(
            String requestId,
            String action,
            String status,
            boolean executorFound,
            boolean executionAttempted,
            boolean executionSucceeded,
            boolean executionFailed,
            String failureReason,
            int deliveredClients,
            int acknowledgedClients,
            int successfulClients,
            int failedClients,
            String sessionId,
            String userId) {
    }

    private record ActionAck(
            String requestId,
            String action,
            boolean success,
            String error,
            String sessionId,
            String userId) {
    }

    private record DispatchAcknowledgements(
            int expectedTargets,
            Map<String, ActionAck> acknowledgements) {
    }

    private static final class PendingAction {
        private final String requestId;
        private final String action;
        private final Set<String> expectedTargets = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, ActionAck> acknowledgements = new ConcurrentHashMap<>();
        private final CompletableFuture<DispatchAcknowledgements> completion = new CompletableFuture<>();

        private PendingAction(String requestId, String action) {
            this.requestId = requestId;
            this.action = action;
        }

        private void setExpectedTargets(Collection<String> targets) {
            expectedTargets.clear();
            expectedTargets.addAll(targets);
            completeIfSatisfied();
        }

        private void acknowledge(String sessionId, ActionAck ack) {
            String key = sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
            acknowledgements.put(key, ack);
            completeIfSatisfied();
        }

        private void markTargetUnavailable(String sessionId, String userId, String reason) {
            if (sessionId == null || !expectedTargets.contains(sessionId) || acknowledgements.containsKey(sessionId)) {
                return;
            }
            acknowledgements.put(sessionId, new ActionAck(requestId, action, false, reason, sessionId, userId));
            completeIfSatisfied();
        }

        private DispatchAcknowledgements await(long timeoutMs) throws InterruptedException, TimeoutException {
            try {
                return completion.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(
                        "ACK wait failed for requestId=" + requestId + ", action=" + action,
                        e);
            }
        }

        private DispatchAcknowledgements snapshot() {
            return new DispatchAcknowledgements(expectedTargets.size(), Map.copyOf(acknowledgements));
        }

        private void completeIfSatisfied() {
            if (!completion.isDone() && acknowledgements.keySet().containsAll(expectedTargets)) {
                completion.complete(snapshot());
            }
        }
    }

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = principalName(session.getPrincipal());
        ClientSessionState state = new ClientSessionState(session, "UNKNOWN", session.getId(), userId, null);
        clientSessions.put(session.getId(), state);
        log.info("PC Control WebSocket connected: session={}, userId={}", session.getId(), userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientSessionState state = clientSessions.get(session.getId());
        if (state != null) {
            state.touch();
        }

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
        String principalUserId = principalName(session.getPrincipal());
        String resolvedUserId = (principalUserId == null || principalUserId.isBlank())
                ? requestedUserId
                : principalUserId;

        evictReplacedSessions(session.getId(), clientId, resolvedUserId);

        ClientSessionState state = clientSessions.computeIfAbsent(
                session.getId(),
                ignored -> new ClientSessionState(session, normalizedClient, clientId, resolvedUserId, username));
        state.update(normalizedClient, clientId, resolvedUserId, username);

        log.info(
                "Client identified: type={}, session={}, clientId={}, userId={}, username={}",
                normalizedClient,
                session.getId(),
                clientId,
                resolvedUserId,
                username);

        sendMessage(session, createMessage("ACK", "message", "Connected to PC Control"));
    }

    private void evictReplacedSessions(String currentSessionId, String clientId, String userId) {
        if (clientId == null || clientId.isBlank()) {
            return;
        }
        List<ClientSessionState> replacements = clientSessions.values().stream()
                .filter(state -> !currentSessionId.equals(state.session.getId()))
                .filter(state -> clientId.equals(state.clientId))
                .filter(state -> userId == null || userId.isBlank() || userId.equals(state.userId))
                .toList();

        for (ClientSessionState replacement : replacements) {
            log.info("Replacing stale/reconnected desktop session: oldSession={}, clientId={}, userId={}",
                    replacement.session.getId(), replacement.clientId, replacement.userId);
            closeQuietly(replacement.session, REPLACED_SESSION_CLOSE_STATUS);
            removeSession(replacement.session, "Desktop session replaced by reconnect");
        }
    }

    private void handleAck(WebSocketSession session, JsonNode json) {
        String requestId = readText(json, "requestId", null);
        String action = readText(json, "action", "");
        boolean success = json.has("success") && json.get("success").asBoolean();
        String error = readText(json, "error", null);
        ClientSessionState state = clientSessions.get(session.getId());
        String userId = state != null ? state.userId : principalName(session.getPrincipal());

        log.info(
                "Action ACK received: requestId={}, action={}, success={}, sessionId={}, userId={}, error={}",
                requestId,
                action,
                success,
                session.getId(),
                userId,
                error);

        if (requestId == null || requestId.isBlank()) {
            log.warn("Received ACK without requestId: sessionId={}, action={}", session.getId(), action);
            return;
        }

        PendingAction pendingAction = pendingActions.get(requestId);
        if (pendingAction == null) {
            log.warn("Received ACK for unknown or expired requestId={}, action={}, sessionId={}",
                    requestId, action, session.getId());
            return;
        }

        pendingAction.acknowledge(session.getId(), new ActionAck(requestId, action, success, error, session.getId(), userId));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("PC Control WebSocket disconnected: {} ({})", session.getId(), status);
        removeSession(session, "Desktop websocket disconnected");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error for {}: {}", session.getId(), exception.getMessage());
        removeSession(session, "Desktop websocket transport error");
    }

    public DispatchResult dispatchPcAction(
            String action,
            JsonNode params,
            String sessionId,
            String userId,
            String correlationId) {
        return dispatchPcAction(action, params, sessionId, userId, correlationId, DEFAULT_ACK_TIMEOUT_MS);
    }

    public DispatchResult dispatchPcAction(
            String action,
            JsonNode params,
            String sessionId,
            String userId,
            String correlationId,
            long ackTimeoutMs) {
        List<ClientSessionState> targets = resolveTargets(sessionId, userId);
        if (targets.isEmpty()) {
            String status = sessionId != null && !sessionId.isBlank()
                    ? "session_not_found"
                    : (userId != null && !userId.isBlank() ? "user_not_connected" : "no_clients");
            String failureReason = switch (status) {
                case "session_not_found" -> "Requested desktop session is not connected";
                case "user_not_connected" -> "No identified desktop executor is connected for this user";
                default -> "No identified desktop executors are connected";
            };
            log.warn(
                    "No desktop executors available for action {}: status={}, sessionId={}, userId={}, rawConnections={}, eligibleExecutors={}",
                    action,
                    status,
                    sessionId,
                    userId,
                    clientSessions.size(),
                    getConnectedClientsCount());
            return new DispatchResult(
                    null,
                    action,
                    status,
                    false,
                    false,
                    false,
                    true,
                    failureReason,
                    0,
                    0,
                    0,
                    0,
                    sessionId,
                    userId);
        }

        String requestId = buildRequestId(correlationId);
        PendingAction pendingAction = new PendingAction(requestId, action);
        pendingActions.put(requestId, pendingAction);

        int deliveredClients = 0;
        List<String> deliveredSessionIds = new ArrayList<>();
        try {
            String message = pcActionMessage(action, params, requestId, correlationId);
            for (ClientSessionState clientSession : targets) {
                if (sendMessage(clientSession.session, message)) {
                    deliveredClients++;
                    deliveredSessionIds.add(clientSession.session.getId());
                }
            }

            pendingAction.setExpectedTargets(deliveredSessionIds);
            if (deliveredClients == 0) {
                log.warn("Eligible desktop executors found but message delivery failed: action={}, requestId={}",
                        action, requestId);
                return new DispatchResult(
                        requestId,
                        action,
                        "delivery_failed",
                        true,
                        true,
                        false,
                        true,
                        "Eligible desktop executors were found but message delivery failed",
                        0,
                        0,
                        0,
                        0,
                        sessionId,
                        userId);
            }

            DispatchAcknowledgements acknowledgements;
            try {
                acknowledgements = pendingAction.await(ackTimeoutMs);
            } catch (TimeoutException e) {
                acknowledgements = pendingAction.snapshot();
                log.warn(
                        "PC action ACK timeout: requestId={}, action={}, deliveredClients={}, acknowledgedClients={}, timeoutMs={}",
                        requestId,
                        action,
                        deliveredClients,
                        acknowledgements.acknowledgements().size(),
                        ackTimeoutMs);
            }

            return buildDispatchResult(requestId, action, sessionId, userId, deliveredClients, acknowledgements);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for PC action ACK: requestId={}, action={}", requestId, action);
            return buildDispatchResult(requestId, action, sessionId, userId, deliveredClients, pendingAction.snapshot());
        } finally {
            pendingActions.remove(requestId);
        }
    }

    public int getConnectedClientsCount() {
        return (int) clientSessions.values().stream()
                .filter(this::isPcControlClient)
                .filter(state -> state.session.isOpen())
                .count();
    }

    public boolean hasConnectedClients() {
        return getConnectedClientsCount() > 0;
    }

    private List<ClientSessionState> resolveTargets(String sessionId, String userId) {
        List<ClientSessionState> targets = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            ClientSessionState clientSession = clientSessions.get(sessionId);
            if (clientSession != null && isPcControlClient(clientSession) && clientSession.session.isOpen()) {
                targets.add(clientSession);
            }
            return targets;
        }

        if (userId != null && !userId.isBlank()) {
            clientSessions.values().stream()
                    .filter(this::isPcControlClient)
                    .filter(state -> userId.equals(state.userId))
                    .filter(state -> state.session.isOpen())
                    .forEach(targets::add);
            return targets;
        }

        clientSessions.values().stream()
                .filter(this::isPcControlClient)
                .filter(state -> state.session.isOpen())
                .forEach(targets::add);
        return targets;
    }

    private DispatchResult buildDispatchResult(
            String requestId,
            String action,
            String sessionId,
            String userId,
            int deliveredClients,
            DispatchAcknowledgements acknowledgements) {
        int acknowledgedClients = acknowledgements.acknowledgements().size();
        int successfulClients = (int) acknowledgements.acknowledgements().values().stream()
                .filter(ActionAck::success)
                .count();
        int failedClients = acknowledgedClients - successfulClients;
        boolean executionSucceeded = successfulClients > 0;
        boolean allDeliveredAcknowledged = acknowledgedClients >= deliveredClients;

        String failureReason = null;
        if (!executionSucceeded) {
            failureReason = acknowledgements.acknowledgements().values().stream()
                    .map(ActionAck::error)
                    .filter(error -> error != null && !error.isBlank())
                    .findFirst()
                    .orElse(allDeliveredAcknowledged
                            ? "Desktop executor reported failure"
                            : "Desktop executor did not acknowledge the command in time");
        } else if (!allDeliveredAcknowledged) {
            failureReason = "At least one desktop executor did not acknowledge the command in time";
        }

        String status;
        if (!executionSucceeded && !allDeliveredAcknowledged) {
            status = "ack_timeout";
        } else if (!executionSucceeded) {
            status = "execution_failed";
        } else if (failedClients > 0 || acknowledgedClients < deliveredClients) {
            status = "partial_success";
        } else {
            status = "executed";
        }
        boolean executionFailed = switch (status) {
            case "executed", "partial_success" -> false;
            default -> true;
        };

        log.info(
                "PC action dispatch completed: requestId={}, action={}, status={}, deliveredClients={}, acknowledgedClients={}, successfulClients={}, failedClients={}, failureReason={}",
                requestId,
                action,
                status,
                deliveredClients,
                acknowledgedClients,
                successfulClients,
                failedClients,
                failureReason);

        return new DispatchResult(
                requestId,
                action,
                status,
                deliveredClients > 0,
                deliveredClients > 0,
                executionSucceeded,
                executionFailed,
                failureReason,
                deliveredClients,
                acknowledgedClients,
                successfulClients,
                failedClients,
                sessionId,
                userId);
    }

    private void heartbeatSweep() {
        long now = System.currentTimeMillis();
        for (ClientSessionState state : clientSessions.values()) {
            if (!state.session.isOpen()) {
                removeSession(state.session, "Desktop websocket session is no longer open");
                continue;
            }
            if (!isPcControlClient(state)) {
                continue;
            }
            if (now - state.lastSeenAt > STALE_SESSION_TIMEOUT_MS) {
                log.warn("Closing stale desktop websocket session: session={}, clientId={}, userId={}, lastSeenAgoMs={}",
                        state.session.getId(), state.clientId, state.userId, now - state.lastSeenAt);
                closeQuietly(state.session, STALE_SESSION_CLOSE_STATUS);
                removeSession(state.session, "Desktop websocket session became stale");
                continue;
            }
            sendMessage(state.session, createMessage("PING", "timestamp", String.valueOf(now)));
        }
    }

    private void removeSession(WebSocketSession session, String reason) {
        ClientSessionState removed = clientSessions.remove(session.getId());
        if (removed == null) {
            return;
        }
        pendingActions.values().forEach(pendingAction ->
                pendingAction.markTargetUnavailable(session.getId(), removed.userId, reason));
    }

    private String buildRequestId(String correlationId) {
        String token = UUID.randomUUID().toString();
        if (correlationId == null || correlationId.isBlank()) {
            return token;
        }
        return correlationId + ":" + token;
    }

    private String pcActionMessage(String action, JsonNode params, String requestId, String correlationId) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "PC_ACTION");
        message.put("action", action);
        message.put("requestId", requestId);
        if (correlationId != null && !correlationId.isBlank()) {
            message.put("correlationId", correlationId);
        }
        message.set("params", params != null ? params : objectMapper.createObjectNode());
        return message.toString();
    }

    private String createMessage(String type, String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put(key, value);
        return node.toString();
    }

    private boolean sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                return true;
            }
        } catch (IOException e) {
            log.warn("Failed to send message to {}: {}", session.getId(), e.getMessage());
            removeSession(session, "Failed to deliver websocket message");
        }
        return false;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Failed to close websocket session {} cleanly: {}", session.getId(), e.getMessage());
        }
    }

    private boolean isPcControlClient(ClientSessionState state) {
        return "PC_CONTROL_CLIENT".equalsIgnoreCase(state.clientType)
                || "DESKTOP".equalsIgnoreCase(state.clientType);
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
        return json.has(field) && !json.get(field).isNull() ? json.get(field).asText() : defaultValue;
    }
}
