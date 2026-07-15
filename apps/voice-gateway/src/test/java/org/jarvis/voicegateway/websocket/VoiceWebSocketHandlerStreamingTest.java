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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerStreamingTest {

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
        when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true,
                "status", "available",
                "reason", "ready"));
        lenient().when(voiceOutputService.resolveAndGetAudio(any(), any(), any(), any(), any()))
                .thenReturn(new byte[] {1, 2, 3});
        lenient().when(ruleBasedVoiceCommandService.match(any(), any())).thenReturn(Optional.empty());

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        lenient().when(session.getId()).thenReturn("voice-session");
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getHandshakeHeaders()).thenReturn(headers);
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");
        ReflectionTestUtils.setField(handler, "noAudioTimeoutMs", 8000L);
    }

    @Test
    void firstAudioChunkTransitionsToStreamingAndEmitsPartialTranscript() throws Exception {
        start("corr-partial", "ru-RU");
        when(recognitionSession.acceptWaveForm(any(), anyInt())).thenReturn(false);
        when(recognitionSession.getPartialResult()).thenReturn("{\"partial\":\"привет\"}");

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {1, 2, 3, 4}));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"STREAMING\"")),
                "Expected STREAMING state, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"TRANSCRIPT_PARTIAL\"") && p.contains("привет")),
                "Expected partial transcript, got " + payloads);
    }

    @Test
    void silenceFinalizesTranscriptAndDispatchesCommand() throws Exception {
        start("corr-final", "ru-RU");
        when(recognitionSession.acceptWaveForm(any(), anyInt())).thenReturn(true);
        when(recognitionSession.getResult()).thenReturn("{\"text\":\"что нового\"}");
        when(intentService.handle(any())).thenReturn(IntentResult.builder()
                .handled(true)
                .action("OPEN_URL")
                .parameters(Map.of("url", "https://news.google.com"))
                .build());
        when(orchestratorClient.sendIntentDetailed(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrchestratorClient.IntentExecutionResult(
                        "Открываю новости.", true, true, true, false, null));

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {5, 6, 7, 8}));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"PROCESSING\"")),
                "Expected PROCESSING state, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"TRANSCRIPT_FINAL\"") && p.contains("что нового")),
                "Expected final transcript, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"RESPONSE\"") && p.contains("\"action\":\"OPEN_URL\"")),
                "Expected RESPONSE, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"DONE\"")),
                "Expected DONE state, got " + payloads);
        verify(session).sendMessage(any(BinaryMessage.class));
    }

    @Test
    void finalEmptyResultEmitsNoSpeechRecognized() throws Exception {
        start("corr-empty", "ru-RU");
        when(recognitionSession.acceptWaveForm(any(), anyInt())).thenReturn(true);
        when(recognitionSession.getResult()).thenReturn("");

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {9, 9}));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"ERROR\"") && p.contains("\"code\":\"NO_SPEECH_RECOGNIZED\"")),
                "Expected NO_SPEECH_RECOGNIZED, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"DONE\"")),
                "Expected DONE state, got " + payloads);
    }

    @Test
    void finalResultWithoutTextFieldEmitsNoSpeechRecognized() throws Exception {
        start("corr-notext", "ru-RU");
        when(recognitionSession.acceptWaveForm(any(), anyInt())).thenReturn(true);
        when(recognitionSession.getResult()).thenReturn("{}");

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {3, 3}));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"ERROR\"") && p.contains("\"code\":\"NO_SPEECH_RECOGNIZED\"")),
                "Expected NO_SPEECH_RECOGNIZED, got " + payloads);
    }

    @Test
    void invalidRecognitionJsonIsSwallowedWithoutCrashing() throws Exception {
        start("corr-badjson", "ru-RU");
        when(recognitionSession.acceptWaveForm(any(), anyInt())).thenReturn(true);
        when(recognitionSession.getResult()).thenReturn("this is not json");

        // Must not throw despite the malformed recognizer output.
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {4, 4}));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"PROCESSING\"")),
                "Expected PROCESSING state before the malformed JSON was dropped, got " + payloads);
    }

    @Test
    void recognitionSessionLostMidStreamEmitsSttUnavailable() throws Exception {
        start("corr-lost", "ru-RU");
        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");
        ReflectionTestUtils.setField(context, "recognitionSession", null);

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {1, 1, 1}));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"ERROR\"") && p.contains("\"code\":\"STT_UNAVAILABLE\"")),
                "Expected STT_UNAVAILABLE error, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"STT_UNAVAILABLE\"")),
                "Expected STT_UNAVAILABLE state, got " + payloads);
    }

    private void start(String correlationId, String language) throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"START\",\"correlationId\":\"" + correlationId + "\",\"language\":\"" + language + "\"}"));
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
}
