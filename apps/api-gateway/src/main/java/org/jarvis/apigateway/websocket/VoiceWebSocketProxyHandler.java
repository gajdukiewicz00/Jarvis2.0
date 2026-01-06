package org.jarvis.apigateway.websocket;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    public VoiceWebSocketProxyHandler() {
        // Configure WebSocket container with larger buffer sizes for audio streaming
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024); // 1MB
        container.setDefaultMaxTextMessageBufferSize(64 * 1024);     // 64KB
        this.backendClient = new StandardWebSocketClient(container);
    }

    private final Map<String, ProxySession> proxySessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        String targetUrl = toWsUrl(voiceGatewayUrl) + "/ws/voice";
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.putAll(clientSession.getHandshakeHeaders());

        log.info("🔀 Voice WS proxy: {} -> {}", clientSession.getId(), targetUrl);
        try {
            CompletableFuture<WebSocketSession> future = backendClient.execute(
                    new BackendHandler(clientSession), headers, URI.create(targetUrl));

            WebSocketSession backendSession = future.get(5, TimeUnit.SECONDS);
            proxySessions.put(clientSession.getId(), new ProxySession(clientSession, backendSession));
            log.info("✅ Voice WS backend connected for client {}", clientSession.getId());
        } catch (Exception e) {
            log.error("❌ Voice WS proxy handshake failed for client {} -> {}: {}", clientSession.getId(), targetUrl, e.getMessage(), e);
            clientSession.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy == null || !proxy.backend.isOpen()) {
            log.warn("No backend session for client {}", session.getId());
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        proxy.backend.sendMessage(message);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy == null || !proxy.backend.isOpen()) {
            log.warn("No backend session for client {}", session.getId());
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        proxy.backend.sendMessage(message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        ProxySession proxy = proxySessions.remove(session.getId());
        if (proxy != null && proxy.backend.isOpen()) {
            proxy.backend.close(status);
        }
        log.info("Voice WS proxy closed: {}", session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Transport error on voice proxy {}: {}", session.getId(), exception.getMessage());
        ProxySession proxy = proxySessions.remove(session.getId());
        if (proxy != null && proxy.backend.isOpen()) {
            proxy.backend.close(CloseStatus.SERVER_ERROR);
        }
        session.close(CloseStatus.SERVER_ERROR);
    }

    private String toWsUrl(String httpUrl) {
        if (httpUrl == null) return "ws://voice-gateway:8081";
        return httpUrl.replaceFirst("^http", "ws").replaceAll("/$", "");
    }

    private record ProxySession(WebSocketSession client, WebSocketSession backend) {
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
            if (clientSession.isOpen()) {
                clientSession.close(status);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            log.error("Backend transport error for client {}: {}", clientSession.getId(), exception.getMessage());
            if (clientSession.isOpen()) {
                clientSession.close(CloseStatus.SERVER_ERROR);
            }
        }
    }
}

