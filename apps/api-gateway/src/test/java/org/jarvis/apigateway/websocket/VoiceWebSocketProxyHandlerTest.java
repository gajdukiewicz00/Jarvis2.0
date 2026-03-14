package org.jarvis.apigateway.websocket;

import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketProxyHandlerTest {

    @Mock
    private StandardWebSocketClient backendClient;

    @Mock
    private ServiceJwtProvider serviceJwtProvider;

    @Mock
    private WebSocketSession clientSession;

    @Mock
    private WebSocketSession backendSession;

    private VoiceWebSocketProxyHandler handler;

    @BeforeEach
    void setUp() {
        handler = new VoiceWebSocketProxyHandler(backendClient, serviceJwtProvider);
        ReflectionTestUtils.setField(handler, "voiceGatewayUrl", "http://127.0.0.1:8081");
        when(clientSession.getId()).thenReturn("client-1");
        when(clientSession.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(serviceJwtProvider.isEnabled()).thenReturn(true);
        when(serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"))).thenReturn("svc-token");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "42",
                        null,
                        List.of(() -> "USER")
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void afterConnectionEstablishedPropagatesServiceAndUserHeadersToBackend() throws Exception {
        when(backendClient.execute(any(), any(WebSocketHttpHeaders.class), eq(URI.create("ws://127.0.0.1:8081/ws/voice"))))
                .thenAnswer(invocation -> {
                    WebSocketHttpHeaders headers = invocation.getArgument(1);
                    org.junit.jupiter.api.Assertions.assertEquals("svc-token", headers.getFirst("X-Service-Token"));
                    org.junit.jupiter.api.Assertions.assertEquals("42", headers.getFirst("X-User-Id"));
                    org.junit.jupiter.api.Assertions.assertEquals("USER", headers.getFirst("X-User-Roles"));
                    return CompletableFuture.completedFuture(backendSession);
                });

        handler.afterConnectionEstablished(clientSession);

        verify(serviceJwtProvider).createToken("api-gateway", List.of("SVC_INTERNAL"));
    }

    @Test
    void afterConnectionEstablishedUsesPrincipalAndHandshakeHeadersWhenSecurityContextIsEmpty() throws Exception {
        HttpHeaders handshakeHeaders = new HttpHeaders();
        handshakeHeaders.add("X-Username", "runtime-smoke");
        handshakeHeaders.add("X-User-Roles", "USER");
        when(clientSession.getHandshakeHeaders()).thenReturn(handshakeHeaders);
        when(clientSession.getPrincipal()).thenReturn((Principal) () -> "42");
        SecurityContextHolder.clearContext();

        when(backendClient.execute(any(), any(WebSocketHttpHeaders.class), eq(URI.create("ws://127.0.0.1:8081/ws/voice"))))
                .thenAnswer(invocation -> {
                    WebSocketHttpHeaders headers = invocation.getArgument(1);
                    org.junit.jupiter.api.Assertions.assertEquals("42", headers.getFirst("X-User-Id"));
                    org.junit.jupiter.api.Assertions.assertEquals("runtime-smoke", headers.getFirst("X-Username"));
                    org.junit.jupiter.api.Assertions.assertEquals("USER", headers.getFirst("X-User-Roles"));
                    return CompletableFuture.completedFuture(backendSession);
                });

        handler.afterConnectionEstablished(clientSession);
    }
}
