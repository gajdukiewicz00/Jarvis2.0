package org.jarvis.orchestrator.service.impl;

import org.jarvis.orchestrator.client.ApiGatewayPcClient;
import org.jarvis.orchestrator.client.LlmServiceClient;
import org.jarvis.orchestrator.client.NlpClient;
import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.client.SmartHomeClient;
import org.jarvis.orchestrator.config.OrchestratorExecutorProperties;
import org.jarvis.orchestrator.dto.IntentExecutionResult;
import org.jarvis.orchestrator.phrases.JarvisPhraseProvider;
import org.jarvis.orchestrator.phrases.Language;
import org.jarvis.orchestrator.phrases.PhraseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorUserRoutingTest {

    @Mock
    private NlpClient nlpClient;
    @Mock
    private PcControlClient pcControlClient;
    @Mock
    private ApiGatewayPcClient apiGatewayPcClient;
    @Mock
    private JarvisPhraseProvider phraseProvider;
    @Mock
    private LlmServiceClient llmClient;
    @Mock
    private SmartHomeClient smartHomeClient;

    private OrchestratorServiceImpl service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownLlmExecutor();
        }
    }

    @Test
    void executeIntentPassesUserScopedPcActionRequestToGateway() {
        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                smartHomeClient,
                new OrchestratorExecutorProperties());

        when(phraseProvider.getPhrase(eq(PhraseContext.VOLUME_UP), eq(Language.RU))).thenReturn("ok");
        when(apiGatewayPcClient.sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "VOLUME_UP",
                Map.of("delta", 15),
                "user-42",
                "corr-1")))
                .thenReturn(Map.of(
                        "status", "executed",
                        "executorFound", true,
                        "executionAttempted", true,
                        "executionSucceeded", true,
                        "executionFailed", false));

        String response = service.executeIntent(
                "volume_up",
                Map.of("delta", "15"),
                "ru",
                "corr-1",
                "сделай громче",
                "user-42");

        assertEquals("ok", response);
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("VOLUME_UP", Map.of("delta", 15), "user-42", "corr-1"));
    }

    @Test
    void executeIntentPassesUserScopedSmartHomeActionAndDesktopConfirmation() {
        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                smartHomeClient,
                new OrchestratorExecutorProperties());

        when(smartHomeClient.executeAction(
                eq("user-42"),
                eq("kitchen_light"),
                eq(new SmartHomeClient.ActionRequest("TURN_ON", null))))
                .thenReturn(new SmartHomeClient.ActionResult(
                        true,
                        "user-42",
                        "TURN_ON",
                        "Action executed locally",
                        new SmartHomeClient.DeviceView(
                                "kitchen_light",
                                "Kitchen Light",
                                "Kitchen",
                                "LIGHT",
                                java.util.List.of("TURN_ON", "TURN_OFF"),
                                Map.of("power", true),
                                "mock",
                                "2026-03-14T10:45:00Z"),
                        "2026-03-14T10:45:00Z"));
        when(apiGatewayPcClient.sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "NOTIFY",
                Map.of(
                        "title", "Умный дом",
                        "message", "Kitchen Light is now on."),
                "user-42",
                "corr-2")))
                .thenReturn(Map.of(
                        "status", "executed",
                        "executorFound", true,
                        "executionAttempted", true,
                        "executionSucceeded", true,
                        "executionFailed", false));
        when(phraseProvider.getPhrase(
                eq(PhraseContext.SMART_HOME_TURN_ON),
                eq(Language.RU),
                eq(Map.of("device", "кухонный свет"))))
                .thenReturn("Кухонный свет включён, сэр.");

        String response = service.executeIntent(
                "smart_home_action",
                Map.of("deviceId", "kitchen_light", "action", "TURN_ON"),
                "ru",
                "corr-2",
                "включи кухонный свет",
                "user-42");

        assertEquals("Кухонный свет включён, сэр.", response);
        verify(smartHomeClient).executeAction(
                "user-42",
                "kitchen_light",
                new SmartHomeClient.ActionRequest("TURN_ON", null));
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest(
                        "NOTIFY",
                        Map.of(
                                "title", "Умный дом",
                                "message", "Kitchen Light is now on."),
                        "user-42",
                        "corr-2"));
    }

    @Test
    void executeIntentPassesOpenUrlToGatewayWithHint() {
        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                smartHomeClient,
                new OrchestratorExecutorProperties());

        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_URL), eq(Language.RU))).thenReturn("Загружаю, сэр.");
        when(apiGatewayPcClient.sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "OPEN_URL",
                Map.of("url", "https://www.youtube.com/"),
                "user-42",
                "corr-3")))
                .thenReturn(Map.of(
                        "status", "executed",
                        "executorFound", true,
                        "executionAttempted", true,
                        "executionSucceeded", true,
                        "executionFailed", false));

        String response = service.executeIntent(
                "open_url",
                Map.of("url", "https://www.youtube.com/"),
                "ru",
                "corr-3",
                "открой ютуб",
                "user-42");

        assertEquals("Загружаю, сэр.", response);
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest(
                        "OPEN_URL",
                        Map.of("url", "https://www.youtube.com/"),
                        "user-42",
                        "corr-3"));
    }

    @Test
    void executeIntentPassesMonitorOffSystemCommandToGateway() {
        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                smartHomeClient,
                new OrchestratorExecutorProperties());

        when(phraseProvider.getPhrase(eq(PhraseContext.MONITOR_OFF), eq(Language.RU))).thenReturn("Экран погашен.");
        when(apiGatewayPcClient.sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "SYSTEM_COMMAND",
                Map.of("command", "monitor_off"),
                "user-42",
                "corr-4")))
                .thenReturn(Map.of(
                        "status", "executed",
                        "executorFound", true,
                        "executionAttempted", true,
                        "executionSucceeded", true,
                        "executionFailed", false));

        String response = service.executeIntent(
                "monitor_off",
                Map.of(),
                "ru",
                "corr-4",
                "выключи монитор",
                "user-42");

        assertEquals("Экран погашен.", response);
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest(
                        "SYSTEM_COMMAND",
                        Map.of("command", "monitor_off"),
                        "user-42",
                        "corr-4"));
    }

    @Test
    void executeIntentDetailedMarksMissingDesktopExecutorAsFailure() {
        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                smartHomeClient,
                new OrchestratorExecutorProperties());

        when(apiGatewayPcClient.sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "VOLUME_UP",
                Map.of("delta", 10),
                "user-42",
                "corr-5")))
                .thenReturn(Map.of(
                        "status", "no_clients",
                        "executorFound", false,
                        "executionAttempted", false,
                        "executionSucceeded", false,
                        "executionFailed", true,
                        "failureReason", "No desktop executor is connected"));
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU)))
                .thenReturn("Не удалось выполнить команду.");

        IntentExecutionResult result = service.executeIntentDetailed(
                "volume_up",
                Map.of("delta", "10"),
                "ru",
                "corr-5",
                "сделай громче",
                "user-42");

        assertEquals("Не удалось выполнить команду.", result.responseText());
        assertFalse(result.executorFound());
        assertFalse(result.executionAttempted());
        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertEquals("No desktop executor is connected", result.failureReason());
    }
}
