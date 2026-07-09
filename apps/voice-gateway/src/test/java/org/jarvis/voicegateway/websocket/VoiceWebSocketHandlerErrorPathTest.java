package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Method;
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
class VoiceWebSocketHandlerErrorPathTest {

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
                new ObjectMapper());

        lenient().when(sttService.createSession(any())).thenReturn(recognitionSession);
        lenient().when(sttService.describeRuntime()).thenReturn(Map.of(
                "configuredProvider", "vosk",
                "status", "available",
                "available", true));
        lenient().when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true,
                "status", "available",
                "reason", "ready"));
        lenient().when(wavResponseRegistry.lookupText(any(), any())).thenReturn(null);
        lenient().when(voiceOutputService.resolveRuleResponseAudio(any(), any(), any(), any(), any()))
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
    void ruleCommandDispatcherExceptionEmitsFailureResponseAndReachesDoneState() throws Exception {
        VoiceCommandCatalog.Match match = buildMatch();
        when(ruleBasedVoiceCommandService.match("открой браузер", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-boom"))
                .thenThrow(new RuntimeException("gateway exploded"));

        invokeHandleCommand("corr-boom", "открой браузер");

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"RESPONSE\"")
                        && p.contains("\"handled\":false")
                        && p.contains("\"executionFailed\":true")),
                "Expected failure RESPONSE, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"DONE\"")),
                "Expected DONE state, got " + payloads);
    }

    @Test
    void configLanguageWhileStartedRecreatesRecognitionSessionAndConfirms() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"START\",\"correlationId\":\"corr-cfg\",\"language\":\"ru-RU\"}"));

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"CONFIG\",\"config\":{\"language\":\"en-US\"}}"));

        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");
        org.junit.jupiter.api.Assertions.assertEquals("en-US", ReflectionTestUtils.getField(context, "language"));
        // Old recognizer closed, a new one created for the updated language.
        verify(recognitionSession).close();
        verify(sttService).createSession("en-US");
        assertTrue(textPayloads().stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"CONFIGURED\"")),
                "Expected CONFIGURED state");
    }

    @Test
    void configLanguageWhileStartedEmitsSttUnavailableWhenRecreateFails() throws Exception {
        when(sttService.createSession(any()))
                .thenReturn(recognitionSession)
                .thenThrow(new IllegalStateException("model gone"));

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"START\",\"correlationId\":\"corr-cfg2\",\"language\":\"ru-RU\"}"));

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"CONFIG\",\"config\":{\"language\":\"en-US\"}}"));

        assertTrue(textPayloads().stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"STT_UNAVAILABLE\"")),
                "Expected STT_UNAVAILABLE state after failed recreate");
    }

    @Test
    void endWhileStreamingWithoutRecognizerEmitsRecognizerUnavailable() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"START\",\"correlationId\":\"corr-rec\",\"language\":\"ru-RU\"}"));
        lenient().when(recognitionSession.acceptWaveForm(any(), anyInt())).thenReturn(false);
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {1, 2, 3, 4}));

        // Recognizer disappears mid-stream (e.g. closed by another thread).
        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");
        ReflectionTestUtils.setField(context, "recognitionSession", null);

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"END\",\"correlationId\":\"corr-rec\"}"));

        List<String> payloads = textPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"ERROR\"") && p.contains("\"code\":\"RECOGNIZER_UNAVAILABLE\"")),
                "Expected RECOGNIZER_UNAVAILABLE error, got " + payloads);
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"type\":\"STATE\"") && p.contains("\"state\":\"DONE\"")),
                "Expected DONE state, got " + payloads);
    }

    private VoiceCommandCatalog.Match buildMatch() {
        VoiceCommandCatalog.Action action = new VoiceCommandCatalog.Action(
                VoiceCommandCatalog.ActionTarget.PC_CONTROL,
                "OPEN_APP",
                null,
                null,
                Map.of("app", "browser"));
        VoiceCommandCatalog.Response response = new VoiceCommandCatalog.Response("loading_sir", Map.of());
        VoiceCommandCatalog.Command command = new VoiceCommandCatalog.Command(
                "rule-open-browser",
                "open browser",
                true,
                10,
                List.of(new VoiceCommandCatalog.Matcher(
                        VoiceCommandCatalog.MatcherType.EXACT, List.of("открой браузер"))),
                action,
                response);
        return new VoiceCommandCatalog.Match(
                command, VoiceCommandCatalog.MatcherType.EXACT, "открой браузер", action.params());
    }

    private void invokeHandleCommand(String correlationId, String text) throws Exception {
        handler.afterConnectionEstablished(session);
        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");
        ReflectionTestUtils.setField(context, "correlationId", correlationId);

        Method handleCommand = VoiceWebSocketHandler.class.getDeclaredMethod(
                "handleCommand", context.getClass(), String.class);
        handleCommand.setAccessible(true);
        handleCommand.invoke(handler, context, text);
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
