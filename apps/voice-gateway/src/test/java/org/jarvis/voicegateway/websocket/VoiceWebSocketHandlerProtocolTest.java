package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher;
import org.jarvis.voicegateway.service.LocalIntentExecutionService;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
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
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerProtocolTest {

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
    private LocalIntentExecutionService localIntentExecutionService;
    @Mock
    private OrchestratorClient orchestratorClient;
    @Mock
    private WebSocketSession session;
    @Mock
    private WebSocketSession replacementSession;
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
                localIntentExecutionService,
                orchestratorClient,
                new ObjectMapper());

        lenient().when(sttService.createSession(any())).thenReturn(recognitionSession);
        lenient().when(sttService.describeRuntime()).thenReturn(Map.of(
                "configuredProvider", "vosk",
                "status", "available",
                "available", true));
        when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true,
                "status", "available",
                "reason", "ready"));
        lenient().when(voiceOutputService.resolveAndGetAudio(any(), any(), any(), any(), any()))
                .thenReturn(new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");

        prepareSession(session, "voice-session-1");
        prepareSession(replacementSession, "voice-session-2");
    }

    @Test
    void duplicateStartEmitsExplicitProtocolError() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"START","correlationId":"corr-1","language":"en-US"}
                """.trim()));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"START","correlationId":"corr-2","language":"en-US"}
                """.trim()));

        assertPayloadContains(
                session,
                "\"type\":\"ERROR\"",
                "\"code\":\"DUPLICATE_START\"");
    }

    @Test
    void configWithoutLanguageEmitsConfigInvalidError() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"CONFIG","config":{}}
                """.trim()));

        assertPayloadContains(
                session,
                "\"type\":\"ERROR\"",
                "\"code\":\"CONFIG_INVALID\"");
    }

    @Test
    void audioBeforeStartEmitsAudioBeforeStartError() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {1, 2, 3, 4}));

        assertPayloadContains(
                session,
                "\"type\":\"ERROR\"",
                "\"code\":\"AUDIO_BEFORE_START\"");
    }

    @Test
    void endWithoutAudioEmitsNoAudioReceivedAndDoneState() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"START","correlationId":"corr-end","language":"en-US"}
                """.trim()));

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"END","correlationId":"corr-end"}
                """.trim()));

        assertPayloadContains(
                session,
                "\"type\":\"ERROR\"",
                "\"code\":\"NO_AUDIO_RECEIVED\"");
        assertPayloadContains(
                session,
                "\"type\":\"STATE\"",
                "\"state\":\"DONE\"");
    }

    @Test
    void timeoutEmitsErrorResponseAndTimeoutState() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"START","correlationId":"corr-timeout","language":"en-US"}
                """.trim()));

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"TIMEOUT","correlationId":"corr-timeout"}
                """.trim()));

        assertPayloadContains(
                session,
                "\"type\":\"ERROR\"",
                "\"code\":\"TIMEOUT\"");
        assertPayloadContains(
                session,
                "\"type\":\"RESPONSE\"",
                "\"action\":\"STT_TIMEOUT\"");
        assertPayloadContains(
                session,
                "\"type\":\"STATE\"",
                "\"state\":\"TIMEOUT\"");
    }

    @Test
    void freshSessionCanStartAfterFailureRecovery() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"START","correlationId":"corr-fail","language":"en-US"}
                """.trim()));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"END","correlationId":"corr-fail"}
                """.trim()));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        handler.afterConnectionEstablished(replacementSession);
        handler.handleTextMessage(replacementSession, new TextMessage("""
                {"type":"START","correlationId":"corr-recover","language":"en-US"}
                """.trim()));

        assertPayloadContains(
                replacementSession,
                "\"type\":\"STATE\"",
                "\"state\":\"STARTED\"");
    }

    private void prepareSession(WebSocketSession webSocketSession, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        lenient().when(webSocketSession.getId()).thenReturn(sessionId);
        lenient().when(webSocketSession.isOpen()).thenReturn(true);
        lenient().when(webSocketSession.getHandshakeHeaders()).thenReturn(headers);
    }

    private void assertPayloadContains(WebSocketSession webSocketSession, String... fragments) throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> messageCaptor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(webSocketSession, atLeastOnce()).sendMessage(messageCaptor.capture());
        List<String> payloads = messageCaptor.getAllValues().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();

        for (String fragment : fragments) {
            assertTrue(
                    payloads.stream().anyMatch(payload -> payload.contains(fragment)),
                    "Expected fragment not found: " + fragment + " in " + payloads);
        }
    }
}
