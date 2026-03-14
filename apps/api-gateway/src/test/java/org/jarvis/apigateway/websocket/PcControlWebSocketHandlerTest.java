package org.jarvis.apigateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
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
    void sendPcActionToUserTargetsOnlyMatchingSession() throws Exception {
        handler.sendPcActionToUser("user-1", "NOTIFY", objectMapper.createObjectNode().put("message", "hi"));

        verify(userOneSession).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));
        verify(userTwoSession, never()).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));
    }

    @Test
    void authenticatedPrincipalWinsOverSpoofedIdentifyUserId() throws Exception {
        handler.sendPcActionToUser("spoofed-user", "NOTIFY", objectMapper.createObjectNode().put("message", "hi"));

        verify(userOneSession, never()).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));

        handler.sendPcActionToUser("user-1", "NOTIFY", objectMapper.createObjectNode().put("message", "hi"));

        verify(userOneSession).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));
    }
}
