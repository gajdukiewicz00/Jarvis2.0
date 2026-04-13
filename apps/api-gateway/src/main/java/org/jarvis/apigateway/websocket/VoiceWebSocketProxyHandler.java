package org.jarvis.apigateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VoiceWebSocketProxyHandler extends AbstractWebSocketHandler {

    private static final int MAX_PENDING_MESSAGES = 8;
    private static final CloseStatus UPSTREAM_UNAVAILABLE_STATUS = new CloseStatus(4503, "voice_gateway_unavailable");
    private static final CloseStatus UPSTREAM_TIMEOUT_STATUS = new CloseStatus(4504, "voice_gateway_timeout");
    private static final CloseStatus BUFFER_OVERFLOW_STATUS = new CloseStatus(4508, "voice_gateway_buffer_overflow");

    @Value("${services.voice-gateway.url:http://voice-gateway:8081}")
    private String voiceGatewayUrl;

    private final StandardWebSocketClient backendClient;
    private final ServiceJwtProvider serviceJwtProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ProxySession> proxySessions = new ConcurrentHashMap<>();

    @Autowired
    public VoiceWebSocketProxyHandler(ServiceJwtProvider serviceJwtProvider) {
        this(createDefaultBackendClient(), serviceJwtProvider);
    }

    VoiceWebSocketProxyHandler(StandardWebSocketClient backendClient, ServiceJwtProvider serviceJwtProvider) {
        this.backendClient = backendClient;
        this.serviceJwtProvider = serviceJwtProvider;
    }

    private static StandardWebSocketClient createDefaultBackendClient() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024);
        container.setDefaultMaxTextMessageBufferSize(64 * 1024);
        return new StandardWebSocketClient(container);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        String clientSessionId = clientSession.getId();
        String targetUrl = toWsUrl(voiceGatewayUrl) + "/ws/voice";
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.putAll(clientSession.getHandshakeHeaders());
        headers.remove(HttpHeaders.AUTHORIZATION);
        headers.remove(ServiceJwtFilter.SERVICE_TOKEN_HEADER);
        applyUserHeaders(clientSession, headers);
        applyServiceHeaders(headers);

        ProxySession proxySession = new ProxySession();
        proxySessions.put(clientSessionId, proxySession);

        log.info("Voice WS proxy open: client={}, userId={}, username={}, roles={}, target={}",
                clientSessionId,
                headers.getFirst("X-User-Id"),
                headers.getFirst("X-Username"),
                headers.getFirst("X-User-Roles"),
                targetUrl);
        try {
            CompletableFuture<WebSocketSession> future = backendClient.execute(
                    new BackendHandler(clientSession), headers, URI.create(targetUrl));

            WebSocketSession backendSession = future.get(5, TimeUnit.SECONDS);
            if (!clientSession.isOpen() || proxySessions.get(clientSessionId) != proxySession) {
                if (backendSession.isOpen()) {
                    backendSession.close(CloseStatus.NORMAL);
                }
                log.warn("Voice WS backend connected after client {} already closed; dropping backend session", clientSessionId);
                return;
            }

            int flushedMessages = proxySession.attachBackend(backendSession);
            log.info("Voice WS backend connected for client {}, bufferedMessagesFlushed={}",
                    clientSessionId,
                    flushedMessages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proxySessions.remove(clientSessionId, proxySession);
            sendErrorAndClose(clientSession, "UPSTREAM_INTERRUPTED", "Voice gateway handshake was interrupted",
                    CloseStatus.SERVER_ERROR);
        } catch (TimeoutException e) {
            proxySessions.remove(clientSessionId, proxySession);
            sendErrorAndClose(clientSession, "UPSTREAM_TIMEOUT",
                    "Voice gateway did not accept the websocket connection in time",
                    UPSTREAM_TIMEOUT_STATUS);
        } catch (ExecutionException | RuntimeException e) {
            proxySessions.remove(clientSessionId, proxySession);
            ProxyFailure failure = classifyFailure(e);
            sendErrorAndClose(clientSession, failure.errorCode(), failure.message(), failure.closeStatus());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy == null) {
            sendErrorAndClose(session, "UPSTREAM_UNAVAILABLE", "Voice gateway session is not available",
                    UPSTREAM_UNAVAILABLE_STATUS);
            return;
        }
        handleSendOutcome(session, proxy.sendOrBuffer(copyTextMessage(message)));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy == null) {
            sendErrorAndClose(session, "UPSTREAM_UNAVAILABLE", "Voice gateway session is not available",
                    UPSTREAM_UNAVAILABLE_STATUS);
            return;
        }
        handleSendOutcome(session, proxy.sendOrBuffer(copyBinaryMessage(message)));
    }

    private void handleSendOutcome(WebSocketSession session, ProxySession.SendOutcome sendOutcome) throws IOException {
        switch (sendOutcome) {
            case FORWARDED -> {
                // No-op.
            }
            case BUFFERED -> log.debug("Buffering voice websocket frame while backend connects: client={}", session.getId());
            case BACKEND_CLOSED -> {
                proxySessions.remove(session.getId());
                sendErrorAndClose(session, "UPSTREAM_UNAVAILABLE", "Voice gateway connection is no longer active",
                        UPSTREAM_UNAVAILABLE_STATUS);
            }
            case BUFFER_OVERFLOW -> {
                proxySessions.remove(session.getId());
                sendErrorAndClose(session, "BUFFER_OVERFLOW",
                        "Voice gateway connection did not become ready before the proxy buffer filled up",
                        BUFFER_OVERFLOW_STATUS);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        ProxySession proxy = proxySessions.remove(session.getId());
        WebSocketSession backendSession = proxy != null ? proxy.backend() : null;
        if (backendSession != null && backendSession.isOpen()) {
            backendSession.close(status);
        }
        log.info("Voice WS proxy closed: client={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Transport error on voice proxy {}: {}", session.getId(), exception.getMessage());
        ProxySession proxy = proxySessions.remove(session.getId());
        WebSocketSession backendSession = proxy != null ? proxy.backend() : null;
        if (backendSession != null && backendSession.isOpen()) {
            backendSession.close(CloseStatus.SERVER_ERROR);
        }
        sendErrorAndClose(session, "TRANSPORT_ERROR", "Voice websocket transport error", CloseStatus.SERVER_ERROR);
    }

    private TextMessage copyTextMessage(TextMessage message) {
        return new TextMessage(message.getPayload());
    }

    private BinaryMessage copyBinaryMessage(BinaryMessage message) {
        byte[] payload = new byte[message.getPayloadLength()];
        message.getPayload().asReadOnlyBuffer().get(payload);
        return new BinaryMessage(payload);
    }

    private String toWsUrl(String httpUrl) {
        if (httpUrl == null) {
            return "ws://voice-gateway:8081";
        }
        return httpUrl.replaceFirst("^http", "ws").replaceAll("/$", "");
    }

    private void applyUserHeaders(WebSocketSession clientSession, WebSocketHttpHeaders headers) {
        if (clientSession.getPrincipal() != null && clientSession.getPrincipal().getName() != null
                && !clientSession.getPrincipal().getName().isBlank()) {
            headers.set("X-User-Id", clientSession.getPrincipal().getName());
        }

        String username = clientSession.getHandshakeHeaders() != null
                ? clientSession.getHandshakeHeaders().getFirst("X-Username")
                : null;
        if (username != null && !username.isBlank()) {
            headers.set("X-Username", username);
        }

        String roles = clientSession.getHandshakeHeaders() != null
                ? clientSession.getHandshakeHeaders().getFirst("X-User-Roles")
                : null;
        if (roles != null && !roles.isBlank()) {
            headers.set("X-User-Roles", roles);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        if (!headers.containsKey("X-User-Id")) {
            headers.set("X-User-Id", authentication.getName());
        }
        String delegatedRoles = authentication.getAuthorities().stream()
                .map(auth -> auth.getAuthority())
                .collect(Collectors.joining(","));
        if (!delegatedRoles.isBlank()) {
            headers.set("X-User-Roles", delegatedRoles);
        }
    }

    private void applyServiceHeaders(WebSocketHttpHeaders headers) {
        if (!serviceJwtProvider.isEnabled()) {
            return;
        }
        headers.set("X-Service-Token", serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL")));
    }

    private void sendErrorAndClose(WebSocketSession session, String errorCode, String message, CloseStatus closeStatus) {
        if (session == null) {
            return;
        }
        if (session.isOpen()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "ERROR");
                payload.put("code", errorCode);
                payload.put("message", message);
                payload.put("upstreamService", "voice-gateway");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (IOException e) {
                log.debug("Failed to send voice proxy error frame to {}: {}", session.getId(), e.getMessage());
            }
        }
        if (session.isOpen()) {
            try {
                session.close(closeStatus);
            } catch (IOException e) {
                log.debug("Failed to close voice proxy session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    private ProxyFailure classifyFailure(Throwable throwable) {
        Throwable cause = throwable instanceof ExecutionException executionException
                ? executionException.getCause()
                : throwable;
        if (cause instanceof ConnectException) {
            return new ProxyFailure(
                    "UPSTREAM_CONNECTION_REFUSED",
                    "Voice gateway is unreachable",
                    UPSTREAM_UNAVAILABLE_STATUS);
        }
        if (cause instanceof UnknownHostException) {
            return new ProxyFailure(
                    "UPSTREAM_HOST_NOT_FOUND",
                    "Voice gateway host could not be resolved",
                    UPSTREAM_UNAVAILABLE_STATUS);
        }
        return new ProxyFailure(
                "UPSTREAM_UNAVAILABLE",
                "Voice gateway websocket handshake failed",
                UPSTREAM_UNAVAILABLE_STATUS);
    }

    private record ProxyFailure(String errorCode, String message, CloseStatus closeStatus) {
    }

    private static final class ProxySession {
        private final Deque<WebSocketMessage<?>> pendingMessages = new ArrayDeque<>();
        private WebSocketSession backend;

        private synchronized SendOutcome sendOrBuffer(WebSocketMessage<?> message) throws IOException {
            if (backend != null) {
                if (!backend.isOpen()) {
                    return SendOutcome.BACKEND_CLOSED;
                }
                backend.sendMessage(message);
                return SendOutcome.FORWARDED;
            }
            if (pendingMessages.size() >= MAX_PENDING_MESSAGES) {
                return SendOutcome.BUFFER_OVERFLOW;
            }
            pendingMessages.addLast(message);
            return SendOutcome.BUFFERED;
        }

        private synchronized int attachBackend(WebSocketSession backend) throws IOException {
            this.backend = backend;
            int flushed = 0;
            while (!pendingMessages.isEmpty() && backend.isOpen()) {
                backend.sendMessage(pendingMessages.removeFirst());
                flushed++;
            }
            pendingMessages.clear();
            return flushed;
        }

        private synchronized WebSocketSession backend() {
            return backend;
        }

        private enum SendOutcome {
            FORWARDED,
            BUFFERED,
            BACKEND_CLOSED,
            BUFFER_OVERFLOW
        }
    }

    private class BackendHandler extends AbstractWebSocketHandler {
        private final WebSocketSession clientSession;

        BackendHandler(WebSocketSession clientSession) {
            this.clientSession = clientSession;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            if (clientSession.isOpen()) {
                clientSession.sendMessage(message);
            }
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
            if (clientSession.isOpen()) {
                clientSession.sendMessage(message);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
            log.info("Voice WS backend closed for client {}: {}", clientSession.getId(), status);
            proxySessions.remove(clientSession.getId());
            if (clientSession.isOpen()) {
                if (!CloseStatus.NORMAL.equals(status)) {
                    sendErrorAndClose(clientSession, "UPSTREAM_UNAVAILABLE",
                            "Voice gateway connection closed unexpectedly",
                            status);
                } else {
                    clientSession.close(status);
                }
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            log.warn("Backend transport error for client {}: {}", clientSession.getId(), exception.getMessage());
            proxySessions.remove(clientSession.getId());
            sendErrorAndClose(clientSession, "UPSTREAM_UNAVAILABLE",
                    "Voice gateway transport error", UPSTREAM_UNAVAILABLE_STATUS);
        }
    }
}
