package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.voicegateway.client.FinanceActionGateway;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.PlannerActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.jarvis.voicegateway.client.VisionActionGateway;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end confirmation flow through the WS handler: a guarded vision rule creates a pending
 * confirmation and speaks a prompt; a later "подтверждаю" executes the captured VISION action
 * (through a REAL {@link VoiceCommandActionDispatcher} wired to a mock {@link VisionActionGateway})
 * and speaks the answer; "отмена" clears it; and the no-pending / expired edges are voiced clearly.
 */
@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerConfirmationFlowTest {

    @Mock
    private VoiceOutputService voiceOutputService;
    @Mock
    private SttService sttService;
    @Mock
    private TtsService ttsService;
    @Mock
    private RuleBasedVoiceCommandService ruleBasedVoiceCommandService;
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

    @Mock
    private PcControlActionGateway pcControlActionGateway;
    @Mock
    private SmartHomeActionGateway smartHomeActionGateway;
    @Mock
    private PlannerActionGateway plannerActionGateway;
    @Mock
    private FinanceActionGateway financeActionGateway;
    @Mock
    private VisionActionGateway visionActionGateway;

    private PendingConfirmationStore store;
    private VoiceWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        store = new PendingConfirmationStore();
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(
                pcControlActionGateway, smartHomeActionGateway, plannerActionGateway,
                financeActionGateway, visionActionGateway);
        handler = new VoiceWebSocketHandler(
                voiceOutputService,
                sttService,
                ttsService,
                ruleBasedVoiceCommandService,
                dispatcher,
                wavResponseRegistry,
                intentService,
                localIntentExecutionService,
                orchestratorClient,
                new ObjectMapper(),
                store);

        lenient().when(sttService.createSession(any())).thenReturn(recognitionSession);
        lenient().when(ttsService.describeRuntime()).thenReturn(Map.of(
                "available", true, "status", "available", "reason", "ready"));
        lenient().when(session.getId()).thenReturn("voice-session");
        lenient().when(session.isOpen()).thenReturn(true);
        // The confirm/cancel interceptor normalizes via the rule service — echo a lowercased phrase.
        lenient().when(ruleBasedVoiceCommandService.normalizedForDiagnostics(anyString()))
                .thenAnswer(inv -> inv.getArgument(0, String.class).toLowerCase(Locale.ROOT).trim());
        lenient().when(voiceOutputService.resolveRuleResponseAudio(any(), anyString(), any(), any(), any()))
                .thenReturn(new byte[]{1});
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");
    }

    @Test
    void visionConfirmRuleCreatesPendingThenConfirmDispatchesVisionAndSpeaksAnswer() throws Exception {
        withUser("user-1");
        when(ruleBasedVoiceCommandService.match("что на экране", "ru"))
                .thenReturn(Optional.of(visionConfirmMatch()));
        when(visionActionGateway.askScreen(eq("user-1"), anyString(), anyString()))
                .thenReturn(new VoiceCommandActionDispatcher.DispatchResult(
                        true, true, true, true, false, null, "VISION_SCREEN_ANALYZE",
                        Map.of(), "На экране браузер и редактор кода."));

        invokeHandleCommand("corr-vision", "что на экране");
        assertTrue(store.hasPending("user-1"));
        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("показать, что на экране")
                        && p.contains("\"status\":\"REQUIRES_CONFIRMATION\"")));

        invokeHandleCommand("corr-confirm", "подтверждаю");

        verify(visionActionGateway).askScreen("user-1", "Что на экране?", "corr-confirm");
        assertFalse(store.hasPending("user-1"));
        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("На экране браузер и редактор кода.")
                        && p.contains("\"action\":\"VISION_SCREEN_ANALYZE\"")));
    }

    @Test
    void cancelClearsPendingAndDoesNotDispatch() throws Exception {
        withUser("user-1");
        when(ruleBasedVoiceCommandService.match("что на экране", "ru"))
                .thenReturn(Optional.of(visionConfirmMatch()));

        invokeHandleCommand("corr-vision", "что на экране");
        assertTrue(store.hasPending("user-1"));

        invokeHandleCommand("corr-cancel", "отмена");

        assertFalse(store.hasPending("user-1"));
        assertTrue(captureTextPayloads().stream().anyMatch(p -> p.contains("Отменено, сэр.")));
        verifyNoInteractions(visionActionGateway);
    }

    @Test
    void confirmWithNoPendingSaysNothingToConfirm() throws Exception {
        withUser("user-1");

        invokeHandleCommand("corr-confirm", "подтверждаю");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("Сэр, нечего подтверждать.")
                        && p.contains("\"status\":\"CLARIFICATION_NEEDED\"")));
        verifyNoInteractions(visionActionGateway);
    }

    @Test
    void expiredPendingSaysExpired() throws Exception {
        withUser("user-1");
        ReflectionTestUtils.setField(store, "ttlMs", 20L);
        when(ruleBasedVoiceCommandService.match("что на экране", "ru"))
                .thenReturn(Optional.of(visionConfirmMatch()));

        invokeHandleCommand("corr-vision", "что на экране");
        Thread.sleep(60L);
        invokeHandleCommand("corr-confirm", "подтверждаю");

        assertTrue(captureTextPayloads().stream().anyMatch(p ->
                p.contains("подтверждение истекло")
                        && p.contains("\"status\":\"CLARIFICATION_NEEDED\"")));
        verifyNoInteractions(visionActionGateway);
    }

    private void withUser(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", userId);
        when(session.getHandshakeHeaders()).thenReturn(headers);
    }

    private VoiceCommandCatalog.Match visionConfirmMatch() {
        VoiceCommandCatalog.Action action = new VoiceCommandCatalog.Action(
                VoiceCommandCatalog.ActionTarget.INTERNAL,
                "VISION_SCREEN_CONFIRM",
                null,
                null,
                Map.of(
                        "confirmAction", "VISION_SCREEN_ANALYZE",
                        "confirmTarget", "vision",
                        "question", "Что на экране?"));
        VoiceCommandCatalog.Response response = new VoiceCommandCatalog.Response(
                "vision_confirm", Map.of("ru", "Сэр, показать, что на экране?"));
        VoiceCommandCatalog.Command command = new VoiceCommandCatalog.Command(
                "rule-vision-screen", "vision screen", true, 50,
                List.of(new VoiceCommandCatalog.Matcher(
                        VoiceCommandCatalog.MatcherType.CONTAINS, List.of("что на экране"))),
                action, response);
        return new VoiceCommandCatalog.Match(
                command, VoiceCommandCatalog.MatcherType.CONTAINS, "что на экране", action.params());
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

        Method handleCommand = VoiceWebSocketHandler.class
                .getDeclaredMethod("handleCommand", context.getClass(), String.class);
        handleCommand.setAccessible(true);
        handleCommand.invoke(handler, context, text);
    }
}
