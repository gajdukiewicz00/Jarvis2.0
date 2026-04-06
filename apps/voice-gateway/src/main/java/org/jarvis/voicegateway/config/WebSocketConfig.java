package org.jarvis.voicegateway.config;

import org.jarvis.voicegateway.websocket.VoiceWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceWebSocketHandler voiceWebSocketHandler;

    @Value("#{'${cors.allowed-origins:}'.split(',')}")
    private List<String> allowedOrigins;

    public WebSocketConfig(VoiceWebSocketHandler voiceWebSocketHandler) {
        this.voiceWebSocketHandler = voiceWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> normalizedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());

        var registration = registry.addHandler(voiceWebSocketHandler, "/ws/voice");
        if (!normalizedOrigins.isEmpty()) {
            registration.setAllowedOriginPatterns(normalizedOrigins.toArray(new String[0]));
        }
    }

    /**
     * Configure WebSocket container with larger buffer sizes for audio streaming.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(1024 * 1024);  // 1MB for audio chunks
        container.setMaxTextMessageBufferSize(64 * 1024);      // 64KB for text messages
        // Keep long-lived desktop voice sessions alive; the client already sends pings.
        container.setMaxSessionIdleTimeout(0L);                // Disable container idle timeout
        container.setAsyncSendTimeout(30000L);                 // 30 seconds async send timeout
        return container;
    }
}
