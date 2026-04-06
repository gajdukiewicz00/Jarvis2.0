package org.jarvis.apigateway.websocket;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Simple reverse proxy WebSocket handler that forwards /ws/voice from API Gateway
 * to the internal voice-gateway service.
 *
 * It keeps a pair of sessions (client <-> backend) and forwards text/binary frames both ways.
 */
@Slf4j
@Component
public class VoiceWebSocketProxyHandler extends AbstractWebSocketHandler {

    @Value("${services.voice-gateway.url:http://voice-gateway:8081}")
    private String voiceGatewayUrl;

    private final StandardWebSocketClient backendClient;
    private final ServiceJwtProvider serviceJwtProvider;

    @Autowired
    public VoiceWebSocketProxyHandler(ServiceJwtProvider serviceJwtProvider) {
        this(createDefaultBackendClient(), serviceJwtProvider);
    }

    VoiceWebSocketProxyHandler(StandardWebSocketClient backendClient, ServiceJwtProvider serviceJwtProvider) {
        this.backendClient = backendClient;
        this.serviceJwtProvider = serviceJwtProvider;
    }

    private static StandardWebSocketClient createDefaultBackendClient() {
        // Configure WebSocket container with larger buffer sizes for audio streaming
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024); // 1MB
        container.setDefaultMaxTextMessageBufferSize(64 * 1024);     // 64KB
        return new StandardWebSocketClient(container);
    }

    private final Map<String, ProxySession> proxySessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        String clientSessionId = clientSession.getId();
        String targetUrl = toWsUrl(voiceGatewayUrl) + "/ws/voice";
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.putAll(clientSession.getHandshakeHeaders());
        applyUserHeaders(clientSession, headers);
        applyServiceHeaders(headers);
        ProxySession proxySession = new ProxySession();
        proxySessions.put(clientSessionId, proxySession);

        log.info("🔀 Voice WS proxy open: client={}, userId={}, username={}, roles={}, target={}",
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
            log.info("✅ Voice WS backend connected for client {}, bufferedMessagesFlushed={}",
                    clientSessionId,
                    flushedMessages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proxySessions.remove(clientSessionId, proxySession);
            log.error("❌ Voice WS proxy handshake interrupted for client {} -> {}: {}", clientSessionId, targetUrl,
                    e.getMessage(), e);
            clientSession.close(CloseStatus.SERVER_ERROR);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            proxySessions.remove(clientSessionId, proxySession);
            log.error("❌ Voice WS proxy handshake failed for client {} -> {}: {}", clientSessionId, targetUrl, e.getMessage(), e);
            clientSession.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy == null) {
            log.warn("No backend session for client {}", session.getId());
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        boolean buffered = proxy.sendOrBuffer(copyTextMessage(message));
        if (buffered) {
            log.info("⏳ Buffering voice WS text frame while backend connects: client={}, pendingMessages={}",
                    session.getId(),
                    proxy.pendingCount());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy == null) {
            log.warn("No backend session for client {}", session.getId());
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        boolean buffered = proxy.sendOrBuffer(copyBinaryMessage(message));
        if (buffered) {
            log.debug("Buffering voice WS binary frame while backend connects: client={}, pendingMessages={}",
                    session.getId(),
                    proxy.pendingCount());
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
        log.error("Transport error on voice proxy {}: {}", session.getId(), exception.getMessage(), exception);
        ProxySession proxy = proxySessions.remove(session.getId());
        WebSocketSession backendSession = proxy != null ? proxy.backend() : null;
        if (backendSession != null && backendSession.isOpen()) {
            backendSession.close(CloseStatus.SERVER_ERROR);
        }
        session.close(CloseStatus.SERVER_ERROR);
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
        if (httpUrl == null) return "ws://voice-gateway:8081";
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

    private static final class ProxySession {
        private final Deque<WebSocketMessage<?>> pendingMessages = new ArrayDeque<>();
        private WebSocketSession backend;

        private ProxySession() {
        }

        private synchronized boolean sendOrBuffer(WebSocketMessage<?> message) throws Exception {
            if (backend != null && backend.isOpen()) {
                backend.sendMessage(message);
                return false;
            }
            pendingMessages.addLast(message);
            return true;
        }

        private synchronized int attachBackend(WebSocketSession backend) throws Exception {
            this.backend = backend;
            int flushed = 0;
            while (!pendingMessages.isEmpty() && backend.isOpen()) {
                backend.sendMessage(pendingMessages.removeFirst());
                flushed++;
            }
            return flushed;
        }

        private synchronized int pendingCount() {
            return pendingMessages.size();
        }

        private synchronized WebSocketSession backend() {
            return backend;
        }
    }

    /**
     * Handler for messages coming from the backend (voice-gateway) to the client.
     */
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
            if (clientSession.isOpen()) {
                clientSession.close(status);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            log.error("Backend transport error for client {}: {}", clientSession.getId(), exception.getMessage(), exception);
            if (clientSession.isOpen()) {
                clientSession.close(CloseStatus.SERVER_ERROR);
            }
        }
    }
}
