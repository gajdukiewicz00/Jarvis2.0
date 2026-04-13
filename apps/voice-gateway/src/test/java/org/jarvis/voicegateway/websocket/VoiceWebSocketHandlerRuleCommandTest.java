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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerRuleCommandTest {

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
        when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true,
                "status", "available",
                "reason", "ready"));
        when(session.getId()).thenReturn("voice-session");
        when(session.isOpen()).thenReturn(true);
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");
    }

    @Test
    void matchedRuleCommandDispatchesDirectlyAndSkipsOrchestrator() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        VoiceCommandCatalog.Match match = buildMatch(
                new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL,
                        "OPEN_APP",
                        null,
                        null,
                        Map.of("app", "browser")),
                new VoiceCommandCatalog.Response("loading_sir", Map.of()));

        when(ruleBasedVoiceCommandService.match("открой браузер", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-1"))
                .thenReturn(new VoiceCommandActionDispatcher.DispatchResult(
                        true,
                        true,
                        true,
                        true,
                        false,
                        null,
                        "OPEN_APP",
                        Map.of("app", "browser")));
        when(wavResponseRegistry.lookupText("loading_sir", "ru")).thenReturn("Загружаю, сэр.");
        when(voiceOutputService.resolveRuleResponseAudio("loading_sir", "Загружаю, сэр.", "ru", "ru-RU", "ru-RU-Wavenet-A"))
                .thenReturn(new byte[]{1, 2, 3});

        invokeHandleCommand("corr-1", "открой браузер");

        verify(voiceCommandActionDispatcher).dispatch(match, "user-1", "corr-1");
        verify(orchestratorClient, never()).sendIntentDetailed(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        List<String> payloads = messages.getAllValues().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();
        assertTrue(payloads.stream().anyMatch(payload ->
                payload.contains("\"action\":\"OPEN_APP\"")
                        && payload.contains("\"handled\":true")
                        && payload.contains("Загружаю, сэр.")));
        verify(session).sendMessage(any(BinaryMessage.class));
    }

    @Test
    void noRuleMatchFallsBackToLegacyIntentAndOrchestratorPath() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        when(ruleBasedVoiceCommandService.match("что нового", "ru")).thenReturn(Optional.empty());
        when(intentService.handle(any())).thenReturn(IntentResult.builder()
                .handled(true)
                .action("OPEN_URL")
                .parameters(Map.of("url", "https://news.google.com"))
                .build());
        when(orchestratorClient.sendIntentDetailed("OPEN_URL", Map.of("url", "https://news.google.com"),
                "ru", "corr-2", "что нового", "user-1"))
                .thenReturn(new OrchestratorClient.IntentExecutionResult(
                        "Открываю новости.",
                        true,
                        true,
                        true,
                        false,
                        null));
        when(voiceOutputService.resolveAndGetAudio("open_url", "Открываю новости.", "ru", "ru-RU", "ru-RU-Wavenet-A"))
                .thenReturn(new byte[]{4, 5, 6});

        invokeHandleCommand("corr-2", "что нового");

        verify(orchestratorClient).sendIntentDetailed(
                eq("OPEN_URL"),
                eq(Map.of("url", "https://news.google.com")),
                eq("ru"),
                eq("corr-2"),
                eq("что нового"),
                eq("user-1"));
        verify(voiceCommandActionDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void ruleCommandReturnsFailureResponseWhenDesktopExecutionFails() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        VoiceCommandCatalog.Match match = buildMatch(
                new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL,
                        "OPEN_APP",
                        null,
                        null,
                        Map.of("app", "browser")),
                new VoiceCommandCatalog.Response("loading_sir", Map.of()));

        when(ruleBasedVoiceCommandService.match("открой браузер", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-fail"))
                .thenReturn(new VoiceCommandActionDispatcher.DispatchResult(
                        true,
                        false,
                        false,
                        false,
                        true,
                        "No desktop executor is connected",
                        "OPEN_APP",
                        Map.of("app", "browser")));
        when(voiceOutputService.resolveRuleResponseAudio("loading_sir", "Не удалось выполнить команду.", "ru", "ru-RU", "ru-RU-Wavenet-A"))
                .thenReturn(new byte[]{7, 8, 9});

        invokeHandleCommand("corr-fail", "открой браузер");

        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        List<String> payloads = messages.getAllValues().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();

        assertTrue(payloads.stream().anyMatch(payload ->
                payload.contains("\"action\":\"OPEN_APP\"")
                        && payload.contains("\"handled\":false")
                        && payload.contains("\"executionFailed\":true")
                        && payload.contains("\"failureReason\":\"No desktop executor is connected\"")
                        && payload.contains("Не удалось выполнить команду.")));
        assertFalse(payloads.stream().anyMatch(payload -> payload.contains("Загружаю, сэр.")));
    }

    private void invokeHandleCommand(String correlationId, String text) throws Exception {
        handler.afterConnectionEstablished(session);
        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessions");
        Object context = sessions.get("voice-session");
        ReflectionTestUtils.setField(context, "correlationId", correlationId);

        Method handleCommand = VoiceWebSocketHandler.class.getDeclaredMethod("handleCommand", context.getClass(), String.class);
        handleCommand.setAccessible(true);
        handleCommand.invoke(handler, context, text);
    }

    private VoiceCommandCatalog.Match buildMatch(VoiceCommandCatalog.Action action, VoiceCommandCatalog.Response response) {
        VoiceCommandCatalog.Command command = new VoiceCommandCatalog.Command(
                "rule-open-browser",
                "open browser",
                true,
                10,
                List.of(new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.EXACT, List.of("открой браузер"))),
                action,
                response);
        return new VoiceCommandCatalog.Match(command, VoiceCommandCatalog.MatcherType.EXACT, "открой браузер", action.params());
    }
}
