package org.jarvis.apigateway.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PcControlWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WebSocketSession userOneSession;

    @Mock
    private WebSocketSession userTwoSession;

    private PcControlWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new PcControlWebSocketHandler();

        when(userOneSession.getId()).thenReturn("session-1");
        when(userOneSession.isOpen()).thenReturn(true);
        when(userOneSession.getPrincipal()).thenReturn((Principal) () -> "user-1");

        when(userTwoSession.getId()).thenReturn("session-2");
        when(userTwoSession.isOpen()).thenReturn(true);
        when(userTwoSession.getPrincipal()).thenReturn((Principal) () -> "user-2");

        handler.afterConnectionEstablished(userOneSession);
        handler.afterConnectionEstablished(userTwoSession);

        handler.handleTextMessage(userOneSession,
                new TextMessage("""
                        {"type":"IDENTIFY","client":"desktop","clientId":"desktop-user-1","userId":"spoofed-user"}
                        """));
        handler.handleTextMessage(userTwoSession,
                new TextMessage("""
                        {"type":"IDENTIFY","client":"desktop","clientId":"desktop-user-2","userId":"user-2"}
                        """));

        clearInvocations(userOneSession, userTwoSession);
    }

    @Test
    void dispatchPcActionTargetsOnlyMatchingUserSessionAndWaitsForAck() throws Exception {
        doAnswer(invocation -> {
            TextMessage outbound = invocation.getArgument(0);
            JsonNode payload = objectMapper.readTree(outbound.getPayload());
            handler.handleTextMessage(userOneSession, new TextMessage("""
                    {"type":"ACK","requestId":"%s","action":"NOTIFY","success":true}
                    """.formatted(payload.path("requestId").asText())));
            return null;
        }).when(userOneSession).sendMessage(any(TextMessage.class));

        PcControlWebSocketHandler.DispatchResult result = handler.dispatchPcAction(
                "NOTIFY",
                objectMapper.createObjectNode().put("message", "hi"),
                null,
                "user-1",
                "corr-1",
                100L);

        assertEquals("executed", result.status());
        assertTrue(result.executorFound());
        assertTrue(result.executionAttempted());
        assertTrue(result.executionSucceeded());
        assertFalse(result.executionFailed());
        assertEquals(1, result.deliveredClients());
        assertEquals(1, result.acknowledgedClients());
        verify(userOneSession).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));
        verify(userTwoSession, never()).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));
    }

    @Test
    void authenticatedPrincipalWinsOverSpoofedIdentifyUserId() {
        PcControlWebSocketHandler.DispatchResult result = handler.dispatchPcAction(
                "NOTIFY",
                objectMapper.createObjectNode().put("message", "hi"),
                null,
                "spoofed-user",
                "corr-2",
                25L);

        assertEquals("user_not_connected", result.status());
        assertFalse(result.executorFound());
        assertFalse(result.executionAttempted());
        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertEquals("No identified desktop executor is connected for this user", result.failureReason());
    }

    @Test
    void dispatchPcActionReturnsAckTimeoutWhenDesktopDoesNotConfirmExecution() throws Exception {
        PcControlWebSocketHandler.DispatchResult result = handler.dispatchPcAction(
                "NOTIFY",
                objectMapper.createObjectNode().put("message", "hi"),
                null,
                "user-1",
                "corr-timeout",
                10L);

        assertEquals("ack_timeout", result.status());
        assertTrue(result.executorFound());
        assertTrue(result.executionAttempted());
        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertEquals("Desktop executor did not acknowledge the command in time", result.failureReason());
        verify(userOneSession).sendMessage(any(TextMessage.class));
    }
}
