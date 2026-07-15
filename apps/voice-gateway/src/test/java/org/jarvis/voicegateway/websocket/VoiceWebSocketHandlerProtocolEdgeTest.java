package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher;
import org.jarvis.voicegateway.service.LocalIntentExecutionService;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
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
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerProtocolEdgeTest {

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
                new ObjectMapper(),
                new org.jarvis.voicegateway.confirmation.PendingConfirmationStore());

        lenient().when(sttService.createSession(any())).thenReturn(recognitionSession);
        lenient().when(sttService.describeRuntime()).thenReturn(Map.of(
                "configuredProvider", "vosk",
                "status", "available",
                "available", true));
        lenient().when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true,
                "status", "available",
                "reason", "ready"));
        lenient().when(ruleBasedVoiceCommandService.match(any(), any())).thenReturn(Optional.empty());
        lenient().when(voiceOutputService.resolveAndGetAudio(any(), any(), any(), any(), any()))
                .thenReturn(new byte[] {1, 2, 3});

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        lenient().when(session.getId()).thenReturn("voice-session");
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getHandshakeHeaders()).thenReturn(headers);
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");
        ReflectionTestUtils.setField(handler, "noAudioTimeoutMs", 8000L);
    }

    @Test
    void unknownMessageTypeEmitsUnknownMessageTypeError() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"FOO\"}"));

        assertPayloadContains("\"type\":\"ERROR\"", "\"code\":\"UNKNOWN_MESSAGE_TYPE\"");
    }

    @Test
    void malformedJsonPayloadEmitsInvalidPayloadError() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{not valid json"));

        assertPayloadContains("\"type\":\"ERROR\"", "\"code\":\"INVALID_PAYLOAD\"");
    }

    @Test
    void endBeforeStreamingIsIgnoredWithoutProtocolError() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"END\",\"correlationId\":\"corr-end\"}"));

        // Idempotent END: an END on a session that never streamed (phase IDLE) is a benign
        // no-op, NOT an END_NOT_ALLOWED protocol error. Emitting that ERROR is exactly what
        // surfaced "END is only valid while audio is streaming" as a bogus assistant response.
        assertNoPayloadContains("\"type\":\"ERROR\"");
        assertNoPayloadContains("END_NOT_ALLOWED");
    }

    @Test
    void timeoutWithoutActiveSessionEmitsTimeoutNotAllowedError() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"TIMEOUT\",\"correlationId\":\"corr-t\"}"));

        assertPayloadContains("\"type\":\"ERROR\"", "\"code\":\"TIMEOUT_NOT_ALLOWED\"");
    }

    @Test
    void configLanguageWhileStreamingEmitsConfigNotAllowedError() throws Exception {
        startStreaming("corr-cfg");

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"CONFIG\",\"config\":{\"language\":\"en-US\"}}"));

        assertPayloadContains("\"type\":\"ERROR\"", "\"code\":\"CONFIG_NOT_ALLOWED\"");
    }

    @Test
    void endWhileStreamingFinalizesRecognitionAndDispatchesCommand() throws Exception {
        startStreaming("corr-fin");
        when(recognitionSession.getResult()).thenReturn("{\"text\":\"открой почту\"}");
        when(intentService.handle(any())).thenReturn(IntentResult.builder()
                .handled(true)
                .action("OPEN_URL")
                .parameters(Map.of("url", "https://mail.google.com"))
                .build());
        when(orchestratorClient.sendIntentDetailed(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrchestratorClient.IntentExecutionResult(
                        "Открываю почту.", true, true, true, false, null));

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"END\",\"correlationId\":\"corr-fin\"}"));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"TRANSCRIPT_FINAL\"") && p.contains("открой почту")),
                "Expected final transcript from END finalization, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"RESPONSE\"") && p.contains("\"action\":\"OPEN_URL\"")),
                "Expected RESPONSE, got " + payloads);
    }

    @Test
    void transportErrorIsHandledWithoutThrowing() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleTransportError(session, new RuntimeException("socket blew up"));

        // resolveUserId reads the handshake headers while logging the error.
        verify(session, atLeastOnce()).getHandshakeHeaders();
    }

    @Test
    void afterConnectionClosedMidCommandRemovesSessionAndClosesRecognizer() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"START\",\"correlationId\":\"corr-close\",\"language\":\"ru-RU\"}"));

        handler.afterConnectionClosed(session, CloseStatus.PROTOCOL_ERROR);

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        assertFalse(sessions.containsKey("voice-session"));
        verify(recognitionSession).close();
    }

    @Test
    void notificationWithBlankUserIdReturnsZeroAndSendsNothing() {
        int delivered = handler.sendNotificationToUser("   ", "hello", "en-US");

        assertEquals(0, delivered);
    }

    @Test
    void notificationWithBlankMessageReturnsZero() {
        int delivered = handler.sendNotificationToUser("user-1", "", "en-US");

        assertEquals(0, delivered);
    }

    private void startStreaming(String correlationId) throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"START\",\"correlationId\":\"" + correlationId + "\",\"language\":\"ru-RU\"}"));
        // First chunk moves the session STARTED -> STREAMING; acceptWaveForm
        // stays false and no partial is produced, so no transcript is emitted yet.
        lenient().when(recognitionSession.acceptWaveForm(any(), anyInt())).thenReturn(false);
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {1, 2, 3, 4}));
    }

    private List<String> textPayloads() throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();
    }

    private void assertPayloadContains(String... fragments) throws Exception {
        List<String> payloads = textPayloads();
        for (String fragment : fragments) {
            assertTrue(payloads.stream().anyMatch(p -> p.contains(fragment)),
                    "Expected fragment not found: " + fragment + " in " + payloads);
        }
    }

    private void assertNoPayloadContains(String fragment) throws Exception {
        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().noneMatch(p -> p.contains(fragment)),
                "Unexpected fragment found: " + fragment + " in " + payloads);
    }
}
