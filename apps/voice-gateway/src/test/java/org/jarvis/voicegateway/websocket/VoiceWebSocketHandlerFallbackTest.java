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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceWebSocketHandlerFallbackTest {

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
        when(session.getId()).thenReturn("voice-session");
        when(session.isOpen()).thenReturn(true);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-7");
        when(session.getHandshakeHeaders()).thenReturn(headers);
        when(voiceOutputService.resolveAndGetAudio(any(), any(), any(), any(), any())).thenReturn(new byte[] {1, 2, 3});
        lenient().when(ruleBasedVoiceCommandService.match(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(handler, "defaultLanguage", "ru-RU");
    }

    @Test
    void orchestratorFailureFallsBackToLocalExecutionForSupportedActions() throws Exception {
        when(intentService.handle(any())).thenReturn(IntentResult.builder()
                .handled(true)
                .action("VOLUME_UP")
                .response("remote")
                .parameters(Map.of("delta", 10))
                .build());
        when(orchestratorClient.sendIntentDetailed(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("orchestrator down"));
        when(localIntentExecutionService.execute(eq("VOLUME_UP"), eq(Map.of("delta", 10)), eq("ru"), eq("corr-fallback"), eq("user-7")))
                .thenReturn(new LocalIntentExecutionService.ExecutionResult(
                        "VOLUME_UP",
                        "Локальное выполнение прошло успешно.",
                        true,
                        true,
                        true,
                        true,
                        false,
                        null));

        invokeHandleCommand("corr-fallback", "сделай громче");

        assertPayloadContains(
                "\"type\":\"RESPONSE\"",
                "\"action\":\"VOLUME_UP\"",
                "\"handled\":true",
                "\"executionSucceeded\":true",
                "Локальное выполнение прошло успешно.");
    }

    @Test
    void unsupportedLocalFallbackReturnsExplicitCapabilityUnavailableOutcome() throws Exception {
        when(intentService.handle(any())).thenReturn(IntentResult.builder()
                .handled(true)
                .action("OPEN_GARAGE")
                .response("remote")
                .parameters(Map.of())
                .build());
        when(orchestratorClient.sendIntentDetailed(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("orchestrator down"));
        when(localIntentExecutionService.execute(eq("OPEN_GARAGE"), eq(Map.of()), eq("ru"), eq("corr-unsupported"), eq("user-7")))
                .thenReturn(new LocalIntentExecutionService.ExecutionResult(
                        "OPEN_GARAGE",
                        "Эта возможность сейчас недоступна.",
                        false,
                        false,
                        false,
                        false,
                        true,
                        "LOCAL_FALLBACK_UNSUPPORTED"));

        invokeHandleCommand("corr-unsupported", "открой гараж");

        assertPayloadContains(
                "\"type\":\"RESPONSE\"",
                "\"handled\":false",
                "\"failureCode\":\"LOCAL_FALLBACK_UNSUPPORTED\"",
                "Эта возможность сейчас недоступна.");
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

    private void assertPayloadContains(String... fragments) throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> messageCaptor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, atLeastOnce()).sendMessage(messageCaptor.capture());
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
