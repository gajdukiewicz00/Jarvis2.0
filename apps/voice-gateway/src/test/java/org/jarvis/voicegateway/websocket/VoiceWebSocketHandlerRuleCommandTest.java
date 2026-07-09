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
import static org.mockito.ArgumentMatchers.anyString;
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
                        Map.of("app", "browser"),
                        null));
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
                        "user_not_connected: No identified desktop executor is connected for this user",
                        "OPEN_APP",
                        Map.of("app", "browser"),
                        null));
        when(voiceOutputService.resolveRuleResponseAudio(
                eq("loading_sir"), anyString(), eq("ru"), eq("ru-RU"), eq("ru-RU-Wavenet-A")))
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
                        && payload.contains("Не удалось открыть браузер")
                        && payload.contains("приложение на компьютере не подключено")));
        assertFalse(payloads.stream().anyMatch(payload -> payload.contains("Загружаю, сэр.")));
        assertFalse(payloads.stream().anyMatch(payload -> payload.contains("Не удалось выполнить команду.")));
    }

    @Test
    void confirmationRequiredIsReportedAsRequiresConfirmationNotGenericFailure() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        VoiceCommandCatalog.Match match = buildMatch(
                new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL, "OPEN_APP", null, null,
                        Map.of("app", "browser")),
                new VoiceCommandCatalog.Response("loading_sir", Map.of()));
        when(ruleBasedVoiceCommandService.match("открой браузер", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-confirm"))
                .thenReturn(new VoiceCommandActionDispatcher.DispatchResult(
                        true, true, true, false, true,
                        "REQUIRES_CONFIRMATION: guarded action requires confirm=true",
                        "OPEN_APP", Map.of("app", "browser"), null));
        when(voiceOutputService.resolveRuleResponseAudio(
                "loading_sir", "Сэр, это действие требует подтверждения.", "ru", "ru-RU", "ru-RU-Wavenet-A"))
                .thenReturn(new byte[]{1});

        invokeHandleCommand("corr-confirm", "открой браузер");

        List<String> payloads = captureTextPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("\"status\":\"REQUIRES_CONFIRMATION\"")
                        && p.contains("требует подтверждения")));
        assertFalse(payloads.stream().anyMatch(p -> p.contains("Не удалось выполнить команду.")));
    }

    @Test
    void http401IsReportedAsClearAccessDeniedNotGenericFailure() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        VoiceCommandCatalog.Match match = buildMatch(
                new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL, "VOLUME_UP", null, null,
                        Map.of("delta", 10)),
                new VoiceCommandCatalog.Response("loading_sir", Map.of()));
        when(ruleBasedVoiceCommandService.match("открой браузер", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-401"))
                .thenReturn(new VoiceCommandActionDispatcher.DispatchResult(
                        true, false, false, false, true,
                        "HTTP_401: authentication rejected by pc-control dispatch",
                        "VOLUME_UP", Map.of("delta", 10), null));
        when(voiceOutputService.resolveRuleResponseAudio(
                eq("loading_sir"), anyString(), eq("ru"), eq("ru-RU"), eq("ru-RU-Wavenet-A")))
                .thenReturn(new byte[]{1});

        invokeHandleCommand("corr-401", "открой браузер");

        List<String> payloads = captureTextPayloads();
        assertTrue(payloads.stream().anyMatch(p -> p.contains("отказано в доступе")));
        assertFalse(payloads.stream().anyMatch(p -> p.contains("Не удалось выполнить команду.")));
    }

    @Test
    void endpointUnreachableIsReportedWithSpecificReasonNotGenericFailure() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        VoiceCommandCatalog.Match match = buildMatch(
                new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL, "VOLUME_UP", null, null,
                        Map.of("delta", 10)),
                new VoiceCommandCatalog.Response("loading_sir", Map.of()));
        when(ruleBasedVoiceCommandService.match("открой браузер", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-net"))
                .thenReturn(new VoiceCommandActionDispatcher.DispatchResult(
                        true, false, false, false, true,
                        "ENDPOINT_UNREACHABLE: Connection refused",
                        "VOLUME_UP", Map.of("delta", 10), null));
        when(voiceOutputService.resolveRuleResponseAudio(
                eq("loading_sir"), anyString(), eq("ru"), eq("ru-RU"), eq("ru-RU-Wavenet-A")))
                .thenReturn(new byte[]{1});

        invokeHandleCommand("corr-net", "открой браузер");

        List<String> payloads = captureTextPayloads();
        assertTrue(payloads.stream().anyMatch(p ->
                p.contains("PC-control недоступен") && p.contains("\"status\":\"FAILED\"")));
        assertFalse(payloads.stream().anyMatch(p -> p.contains("Не удалось выполнить команду.")));
    }

    @Test
    void duplicateCommandForSameCorrelationIdExecutesOnlyOnce() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);

        VoiceCommandCatalog.Match match = buildMatch(
                new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL, "PAUSE", null, null, Map.of()),
                new VoiceCommandCatalog.Response(null, Map.of("ru", "Пауза, сэр.")));
        when(ruleBasedVoiceCommandService.match("пауза", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-dup"))
                .thenReturn(new VoiceCommandActionDispatcher.DispatchResult(
                        true, true, true, true, false, null, "PAUSE", Map.of(), null));
        when(voiceOutputService.resolveRuleResponseAudio(any(), anyString(), any(), any(), any()))
                .thenReturn(new byte[]{1});

        invokeHandleCommand("corr-dup", "пауза");
        invokeHandleCommand("corr-dup", "пауза");

        // The second identical frame must be ignored — the action dispatches exactly once.
        verify(voiceCommandActionDispatcher, org.mockito.Mockito.times(1))
                .dispatch(match, "user-1", "corr-dup");
    }

    private List<String> captureTextPayloads() throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();
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
