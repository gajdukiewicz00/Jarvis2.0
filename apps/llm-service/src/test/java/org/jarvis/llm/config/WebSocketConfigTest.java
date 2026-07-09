package org.jarvis.llm.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebSocketConfig}. Spring's registry types are mocked so
 * the broker/endpoint registration wiring can be verified without a live
 * WebSocket container.
 */
class WebSocketConfigTest {

    private WebSocketConfig config;

    @BeforeEach
    void setUp() {
        config = new WebSocketConfig();
    }

    @Test
    void configureMessageBrokerEnablesSimpleBrokerAndAppPrefix() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        when(registry.enableSimpleBroker("/topic")).thenReturn(mock(SimpleBrokerRegistration.class));

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    void registerStompEndpointsAppliesAllowedOriginsWhenConfigured() {
        ReflectionTestUtils.setField(config, "allowedOrigins",
                List.of("https://app.example.com", "  https://spaced.example.com  ", "   ", ""));

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws/jarvis-llm")).thenReturn(endpoint);
        when(endpoint.setAllowedOriginPatterns(any(String[].class))).thenReturn(endpoint);
        when(endpoint.withSockJS()).thenReturn(mock(SockJsServiceRegistration.class));

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws/jarvis-llm");
        verify(endpoint).setAllowedOriginPatterns(
                "https://app.example.com", "https://spaced.example.com");
        verify(endpoint).withSockJS();
    }

    @Test
    void registerStompEndpointsSkipsAllowedOriginsWhenAllBlank() {
        ReflectionTestUtils.setField(config, "allowedOrigins", List.of("", "   "));

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws/jarvis-llm")).thenReturn(endpoint);
        when(endpoint.withSockJS()).thenReturn(mock(SockJsServiceRegistration.class));

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws/jarvis-llm");
        verify(endpoint, never()).setAllowedOriginPatterns(any(String[].class));
        verify(endpoint).withSockJS();
    }
}
