package org.jarvis.apigateway.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @AfterEach
    void tearDown() {
        handler.shutdownHeartbeat();
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

    @Test
    void reconnectingDesktopReplacesOlderSessionForSameClientId() throws Exception {
        WebSocketSession replacementSession = org.mockito.Mockito.mock(WebSocketSession.class);
        when(replacementSession.getId()).thenReturn("session-3");
        when(replacementSession.isOpen()).thenReturn(true);
        when(replacementSession.getPrincipal()).thenReturn((Principal) () -> "user-1");

        handler.afterConnectionEstablished(replacementSession);
        handler.handleTextMessage(replacementSession,
                new TextMessage("""
                        {"type":"IDENTIFY","client":"desktop","clientId":"desktop-user-1","userId":"user-1"}
                        """));
        verify(userOneSession).close(argThat(status ->
                status.getCode() == 4002 && "replaced_by_reconnect".equals(status.getReason())));
        clearInvocations(userOneSession, replacementSession);

        PcControlWebSocketHandler.DispatchResult result = handler.dispatchPcAction(
                "NOTIFY",
                objectMapper.createObjectNode().put("message", "replace"),
                null,
                "user-1",
                "corr-reconnect",
                10L);

        verify(replacementSession).sendMessage(any(TextMessage.class));
        verify(userOneSession, never()).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));
        assertEquals("ack_timeout", result.status());
    }

    @Test
    void disconnectCompletesPendingDispatchEarlyWithExplicitFailure() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            delivered.countDown();
            return null;
        }).when(userOneSession).sendMessage(any(TextMessage.class));

        CompletableFuture<PcControlWebSocketHandler.DispatchResult> future = CompletableFuture.supplyAsync(() ->
                handler.dispatchPcAction(
                        "NOTIFY",
                        objectMapper.createObjectNode().put("message", "bye"),
                        null,
                        "user-1",
                        "corr-disconnect",
                        5_000L));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        handler.afterConnectionClosed(userOneSession, CloseStatus.SERVER_ERROR);

        PcControlWebSocketHandler.DispatchResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals("execution_failed", result.status());
        assertTrue(result.executionFailed());
        assertEquals(1, result.acknowledgedClients());
        assertEquals("Desktop websocket disconnected", result.failureReason());
    }

    @Test
    void heartbeatSweepSendsPingToHealthyDesktopClients() throws Exception {
        ReflectionTestUtils.invokeMethod(handler, "heartbeatSweep");

        verify(userOneSession).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"type\":\"PING\"")));
        verify(userTwoSession).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"type\":\"PING\"")));
    }

    @Test
    void staleDesktopSessionsAreClosedAndRemoved() throws Exception {
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> clientSessions =
                (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(handler, "clientSessions");
        Object state = clientSessions.get("session-1");
        assertNotNull(state);
        ReflectionTestUtils.setField(
                state,
                "lastSeenAt",
                System.currentTimeMillis() - PcControlWebSocketHandler.STALE_SESSION_TIMEOUT_MS - 1_000L);

        ReflectionTestUtils.invokeMethod(handler, "heartbeatSweep");

        verify(userOneSession).close(argThat(status ->
                status.getCode() == 4001 && "stale_session".equals(status.getReason())));
        assertFalse(clientSessions.containsKey("session-1"));
    }
}
