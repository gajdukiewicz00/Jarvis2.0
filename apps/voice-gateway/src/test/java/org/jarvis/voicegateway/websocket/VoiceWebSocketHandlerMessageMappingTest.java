package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.confirmation.PendingConfirmationStore;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused coverage of the spoken-message mapping: OPEN_APP success phrasing plus the coded
 * failure reasons (APP_CLARIFY / APP_NOT_FOUND / VISION_*) the desktop and vision service return.
 */
@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerMessageMappingTest {

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
    void setUp() {
        handler = new VoiceWebSocketHandler(
                voiceOutputService, sttService, ttsService, ruleBasedVoiceCommandService,
                voiceCommandActionDispatcher, wavResponseRegistry, intentService,
                localIntentExecutionService, orchestratorClient, new ObjectMapper(),
                new PendingConfirmationStore());

        lenient().when(sttService.createSession(any())).thenReturn(recognitionSession);
        lenient().when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true, "status", "available", "reason", "ready"));
        lenient().when(session.getId()).thenReturn("voice-session");
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(voiceOutputService.resolveRuleResponseAudio(any(), anyString(), any(), any(), any()))
                .thenReturn(new byte[]{1});
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");
    }

    @Test
    void openAppSuccessSpeaksOpeningCapitalizedApp() throws Exception {
        withUser();
        VoiceCommandCatalog.Match match = openAppMatch("telegram");
        when(ruleBasedVoiceCommandService.match("открой телеграм", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-open"))
                .thenReturn(success("OPEN_APP", Map.of("app", "telegram")));

        invokeHandleCommand("corr-open", "открой телеграм");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("Открываю Telegram, сэр.") && p.contains("\"status\":\"SUCCESS\"")));
    }

    @Test
    void appClarifyMapsToClarificationQuestion() throws Exception {
        withUser();
        VoiceCommandCatalog.Match match = openAppMatch("telegram");
        when(ruleBasedVoiceCommandService.match("открой телеграм", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-clar"))
                .thenReturn(failure("OPEN_APP", "APP_CLARIFY|Telegram"));

        invokeHandleCommand("corr-clar", "открой телеграм");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("Сэр, вы имеете в виду Telegram?")
                        && p.contains("\"status\":\"CLARIFICATION_NEEDED\"")));
    }

    @Test
    void appNotFoundWithSuggestionsMapsToClarification() throws Exception {
        withUser();
        VoiceCommandCatalog.Match match = openAppMatch("телеграм");
        when(ruleBasedVoiceCommandService.match("открой телеграм", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-nf"))
                .thenReturn(failure("OPEN_APP", "APP_NOT_FOUND|телеграм|Telegram,Telegraph,Signal"));

        invokeHandleCommand("corr-nf", "открой телеграм");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("Сэр, не нашёл приложение «телеграм». Возможно, вы имели в виду: Telegram, Telegraph.")
                        && p.contains("\"status\":\"CLARIFICATION_NEEDED\"")));
    }

    @Test
    void appNotFoundWithoutSuggestionsSpeaksNotFound() throws Exception {
        withUser();
        VoiceCommandCatalog.Match match = openAppMatch("телеграм");
        when(ruleBasedVoiceCommandService.match("открой телеграм", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-nf2"))
                .thenReturn(failure("OPEN_APP", "APP_NOT_FOUND|телеграм"));

        invokeHandleCommand("corr-nf2", "открой телеграм");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("Сэр, не нашёл приложение «телеграм».")));
    }

    @Test
    void visionUnavailableMapsToVisionServiceMessage() throws Exception {
        withUser();
        VoiceCommandCatalog.Match match = visionMatch();
        when(ruleBasedVoiceCommandService.match("что на экране", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-vu"))
                .thenReturn(failure("VISION_SCREEN_ANALYZE", "VISION_UNAVAILABLE"));

        invokeHandleCommand("corr-vu", "что на экране");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("Не удалось проанализировать экран: vision-service недоступен, сэр.")));
    }

    @Test
    void visionEmptyMapsToNoRecognizableTextMessage() throws Exception {
        withUser();
        VoiceCommandCatalog.Match match = visionMatch();
        when(ruleBasedVoiceCommandService.match("что на экране", "ru")).thenReturn(Optional.of(match));
        when(voiceCommandActionDispatcher.dispatch(match, "user-1", "corr-ve"))
                .thenReturn(failure("VISION_SCREEN_ANALYZE", "VISION_EMPTY"));

        invokeHandleCommand("corr-ve", "что на экране");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("Готово, сэр. На экране нет распознаваемого текста.")));
    }

    private VoiceCommandActionDispatcher.DispatchResult success(String action, Map<String, Object> params) {
        return new VoiceCommandActionDispatcher.DispatchResult(
                true, true, true, true, false, null, action, params, null);
    }

    private VoiceCommandActionDispatcher.DispatchResult failure(String action, String reason) {
        return new VoiceCommandActionDispatcher.DispatchResult(
                true, false, false, false, true, reason, action, Map.of(), null);
    }

    private VoiceCommandCatalog.Match openAppMatch(String app) {
        VoiceCommandCatalog.Action action = new VoiceCommandCatalog.Action(
                VoiceCommandCatalog.ActionTarget.PC_CONTROL, "OPEN_APP", null, null, Map.of("app", app));
        return matchFor(action, "открой телеграм");
    }

    private VoiceCommandCatalog.Match visionMatch() {
        VoiceCommandCatalog.Action action = new VoiceCommandCatalog.Action(
                VoiceCommandCatalog.ActionTarget.VISION, "VISION_SCREEN_ANALYZE", null, null,
                Map.of("question", "Что на экране?"));
        return matchFor(action, "что на экране");
    }

    private VoiceCommandCatalog.Match matchFor(VoiceCommandCatalog.Action action, String phrase) {
        VoiceCommandCatalog.Command command = new VoiceCommandCatalog.Command(
                "rule-" + action.name(), action.name(), true, 10,
                List.of(new VoiceCommandCatalog.Matcher(
                        VoiceCommandCatalog.MatcherType.EXACT, List.of(phrase))),
                action, new VoiceCommandCatalog.Response("loading_sir", Map.of()));
        return new VoiceCommandCatalog.Match(
                command, VoiceCommandCatalog.MatcherType.EXACT, phrase, action.params());
    }

    private void withUser() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-1");
        when(session.getHandshakeHeaders()).thenReturn(headers);
    }

    private List<String> captureTextPayloads() throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, atLeastOnce()).sendMessage(messages.capture());
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

        Method handleCommand = VoiceWebSocketHandler.class
                .getDeclaredMethod("handleCommand", context.getClass(), String.class);
        handleCommand.setAccessible(true);
        handleCommand.invoke(handler, context, text);
    }
}
