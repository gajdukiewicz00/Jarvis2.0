package org.jarvis.voicegateway.config;

import org.jarvis.voicegateway.websocket.VoiceWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    private final VoiceWebSocketHandler handler = mock(VoiceWebSocketHandler.class);
    private final WebSocketConfig config = new WebSocketConfig(handler);

    @Test
    void registerWebSocketHandlersAppliesAllowedOriginPatternsWhenConfigured() {
        ReflectionTestUtils.setField(config, "allowedOrigins", List.of(" https://jarvis.local ", "", "https://ops.jarvis.local"));

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(eq(handler), eq("/ws/voice"))).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(org.mockito.ArgumentMatchers.any())).thenReturn(registration);

        config.registerWebSocketHandlers(registry);

        verify(registration).setAllowedOriginPatterns("https://jarvis.local", "https://ops.jarvis.local");
    }

    @Test
    void registerWebSocketHandlersSkipsOriginPatternsWhenNoneConfigured() {
        ReflectionTestUtils.setField(config, "allowedOrigins", List.of(""));

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(eq(handler), eq("/ws/voice"))).thenReturn(registration);

        config.registerWebSocketHandlers(registry);

        verify(registration, never()).setAllowedOriginPatterns(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createWebSocketContainerConfiguresBufferSizesAndTimeouts() {
        ServletServerContainerFactoryBean container = config.createWebSocketContainer();

        assertNotNull(container);
        assertEquals(1024 * 1024, container.getMaxBinaryMessageBufferSize());
        assertEquals(64 * 1024, container.getMaxTextMessageBufferSize());
        assertEquals(30000L, container.getAsyncSendTimeout());
    }
}
