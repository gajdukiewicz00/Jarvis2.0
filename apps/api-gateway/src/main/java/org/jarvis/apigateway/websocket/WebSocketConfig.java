package org.jarvis.apigateway.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;
import java.util.stream.Collectors;

/**
 * WebSocket configuration for PC Control and Voice endpoints.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PcControlWebSocketHandler pcControlHandler;
    private final VoiceWebSocketProxyHandler voiceWebSocketProxyHandler;

    @Value("#{'${cors.allowed-origins:}'.split(',')}")
    private List<String> allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> normalizedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());

        var pcControl = registry.addHandler(pcControlHandler, "/ws/pc-control");
        var voice = registry.addHandler(voiceWebSocketProxyHandler, "/ws/voice");

        if (!normalizedOrigins.isEmpty()) {
            pcControl.setAllowedOriginPatterns(normalizedOrigins.toArray(new String[0]));
            voice.setAllowedOriginPatterns(normalizedOrigins.toArray(new String[0]));
        }
    }

    /**
     * Configure WebSocket container with larger buffer sizes for audio streaming.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(1024 * 1024);  // 1MB for audio
        container.setMaxTextMessageBufferSize(64 * 1024);      // 64KB
        container.setMaxSessionIdleTimeout(300000L);           // 5 minutes
        container.setAsyncSendTimeout(30000L);                 // 30 seconds
        return container;
    }
}
