package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.service.intent.IntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerNotificationTest {

    @Mock
    private TtsService ttsService;
    @Mock
    private SttService sttService;
    @Mock
    private IntentService intentService;
    @Mock
    private OrchestratorClient orchestratorClient;
    @Mock
    private StreamingRecognitionSession recognitionSession;
    @Mock
    private WebSocketSession userOneSession;
    @Mock
    private WebSocketSession userTwoSession;

    private VoiceWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new VoiceWebSocketHandler(
                ttsService,
                sttService,
                intentService,
                orchestratorClient,
                new ObjectMapper());

        when(sttService.createSession(any())).thenReturn(recognitionSession);
        when(ttsService.synthesize(any(), any(), any(), any(), any())).thenReturn(new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");

        connectSession(userOneSession, "voice-user-1", "user-1");
        connectSession(userTwoSession, "voice-user-2", "user-2");
    }

    @Test
    void sendNotificationToUserTargetsOnlyMatchingVoiceSessions() throws Exception {
        int delivered = handler.sendNotificationToUser("user-1", "Время размяться", "ru-RU");

        assertEquals(1, delivered);
        verify(userOneSession).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")
                        && textMessage.getPayload().contains("Время размяться")));
        verify(userOneSession).sendMessage(any(BinaryMessage.class));
        verify(userTwoSession, never()).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("\"action\":\"NOTIFY\"")));
    }

    private void connectSession(WebSocketSession session, String sessionId, String userId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", userId);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        when(session.getHandshakeHeaders()).thenReturn(headers);
        handler.afterConnectionEstablished(session);
    }
}
