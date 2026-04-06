package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher;
import org.jarvis.voicegateway.service.intent.IntentRequest;
import org.jarvis.voicegateway.service.intent.IntentResult;
import org.jarvis.voicegateway.service.intent.IntentService;
import org.jarvis.voicegateway.voice.VoiceOutputService;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerUserContextTest {

    @Mock
    private VoiceOutputService voiceOutputService;
    @Mock
    private SttService sttService;
    @Mock
    private TtsService ttsService;
    @Mock
    private RuleBasedVoiceCommandService ruleBasedVoiceCommandService;
    @Mock
    private VoiceCommandActionDispatcher voiceCommandActionDispatcher;
    @Mock
    private WavResponseRegistry wavResponseRegistry;
    @Mock
    private IntentService intentService;
    @Mock
    private OrchestratorClient orchestratorClient;
    @Mock
    private WebSocketSession session;
    @Mock
    private StreamingRecognitionSession recognitionSession;

    private VoiceWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new VoiceWebSocketHandler(
                voiceOutputService,
                sttService,
                ttsService,
                ruleBasedVoiceCommandService,
                voiceCommandActionDispatcher,
                wavResponseRegistry,
                intentService,
                orchestratorClient,
                new ObjectMapper());

        when(sttService.createSession(any())).thenReturn(recognitionSession);
        when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true,
                "status", "available",
                "reason", "ready"));
        lenient().when(ruleBasedVoiceCommandService.match(any(), any())).thenReturn(Optional.empty());
        when(session.getId()).thenReturn("voice-session");
        when(session.isOpen()).thenReturn(true);
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");
    }

    @Test
    void afterConnectionEstablishedStoresAuthenticatedUserIdFromHandshakeHeaders() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        handler.afterConnectionEstablished(session);

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");

        assertEquals("user-1", ReflectionTestUtils.getField(context, "userId"));
    }

    @Test
    void afterConnectionEstablishedFallsBackToPrincipalUserId() throws Exception {
        when(session.getPrincipal()).thenReturn((Principal) () -> "principal-user");

        handler.afterConnectionEstablished(session);

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");

        assertEquals("principal-user", ReflectionTestUtils.getField(context, "userId"));
    }

    @Test
    void handleCommandPassesSessionUserIdToOrchestrator() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-7");
        when(session.getHandshakeHeaders()).thenReturn(headers);
        when(intentService.handle(any())).thenReturn(IntentResult.builder()
                .handled(true)
                .action("VOLUME_UP")
                .response("ok")
                .parameters(Map.of("delta", 10))
                .build());
        when(orchestratorClient.sendIntent(any(), any(), any(), any(), any(), any())).thenReturn("done");

        handler.afterConnectionEstablished(session);
        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");
        ReflectionTestUtils.setField(context, "correlationId", "corr-1");

        Method handleCommand = VoiceWebSocketHandler.class.getDeclaredMethod("handleCommand", context.getClass(), String.class);
        handleCommand.setAccessible(true);
        handleCommand.invoke(handler, context, "сделай громче");

        verify(orchestratorClient).sendIntent(
                eq("VOLUME_UP"),
                eq(Map.of("delta", 10)),
                eq("ru"),
                eq("corr-1"),
                eq("сделай громче"),
                eq("user-7"));
    }

    @Test
    void afterConnectionEstablishedKeepsSessionAliveWhenSttIsUnavailable() throws Exception {
        when(sttService.createSession(any())).thenThrow(new IllegalStateException("model missing"));

        handler.afterConnectionEstablished(session);

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");

        assertNull(ReflectionTestUtils.getField(context, "recognitionSession"));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(messageCaptor.capture());

        boolean connectedStateSent = messageCaptor.getAllValues().stream()
                .map(TextMessage::getPayload)
                .anyMatch(payload -> payload.contains("\"state\":\"CONNECTED\"")
                        && payload.contains("\"sttAvailable\":false")
                        && payload.contains("\"ttsAvailable\":true"));
        boolean sttUnavailableSent = messageCaptor.getAllValues().stream()
                .map(TextMessage::getPayload)
                .anyMatch(payload -> payload.contains("\"action\":\"STT_UNAVAILABLE\""));

        assertEquals(true, connectedStateSent);
        assertEquals(true, sttUnavailableSent);
    }

    @Test
    void handleTextMessageAcceptsConfigAndRecreatesRecognitionSession() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"CONFIG","config":{"language":"en-US"}}
                """.trim()));

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");

        assertEquals("en-US", ReflectionTestUtils.getField(context, "language"));
        verify(recognitionSession).close();
        verify(sttService).createSession("ru-RU");
        verify(sttService).createSession("en-US");
        verify(sttService, times(2)).createSession(any());
    }

    @Test
    void handleTextMessageStartCarriesExplicitRecognitionLanguageIntoSession() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"START","correlationId":"corr-1","language":"en-US"}
                """.trim()));

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");

        assertEquals("en-US", ReflectionTestUtils.getField(context, "language"));
        assertEquals("corr-1", ReflectionTestUtils.getField(context, "correlationId"));
        verify(recognitionSession).close();
        verify(sttService).createSession("ru-RU");
        verify(sttService).createSession("en-US");
        verify(sttService, times(2)).createSession(any());
    }

    @Test
    void handleCommandPrefersConfiguredSessionLanguageOverTranscriptAutoDetection() throws Exception {
        when(intentService.handle(any())).thenReturn(IntentResult.builder()
                .handled(true)
                .action("OPEN_URL")
                .response("ok")
                .parameters(Map.of("url", "https://youtube.com"))
                .build());
        when(orchestratorClient.sendIntent(any(), any(), any(), any(), any(), any())).thenReturn("done");

        handler.afterConnectionEstablished(session);
        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");
        ReflectionTestUtils.setField(context, "correlationId", "corr-ru");
        ReflectionTestUtils.setField(context, "language", "ru-RU");

        Method handleCommand = VoiceWebSocketHandler.class.getDeclaredMethod("handleCommand", context.getClass(), String.class);
        handleCommand.setAccessible(true);
        handleCommand.invoke(handler, context, "turn on youtube");

        ArgumentCaptor<IntentRequest> requestCaptor = ArgumentCaptor.forClass(IntentRequest.class);
        verify(intentService).handle(requestCaptor.capture());
        assertEquals("ru", requestCaptor.getValue().getLanguage());
    }
}
