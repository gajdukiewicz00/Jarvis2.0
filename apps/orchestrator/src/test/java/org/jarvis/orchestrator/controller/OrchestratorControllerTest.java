package org.jarvis.orchestrator.controller;

import org.jarvis.orchestrator.dto.IntentExecutionResult;
import org.jarvis.orchestrator.service.OrchestratorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorControllerTest {

    @Mock
    private OrchestratorService orchestratorService;

    @InjectMocks
    private OrchestratorController controller;

    @Test
    void executeReturnsOnlyResponseText() {
        when(orchestratorService.executeIntentDetailed(
                eq("volume_up"), eq(Map.of("delta", "10")), eq("ru"), eq("corr-1"), eq("volume_up"), eq("user-1")))
                .thenReturn(new IntentExecutionResult("Louder, sir.", true, true, true, false, null));

        String response = controller.execute("user-1", new OrchestratorController.ExecuteRequest(
                "volume_up", Map.of("delta", "10"), null, null, "ru", "corr-1"));

        assertThat(response).isEqualTo("Louder, sir.");
    }

    @Test
    void executeDetailedWithIntentDefaultsCorrelationIdAndLanguage() {
        when(orchestratorService.executeIntentDetailed(
                eq("mute"), eq(Map.of()), eq("ru"), eq("N/A"), eq("mute"), isNull()))
                .thenReturn(new IntentExecutionResult("Muted.", true, true, true, false, null));

        IntentExecutionResult result = controller.executeDetailed(null,
                new OrchestratorController.ExecuteRequest("mute", Map.of(), null, null, null, null));

        assertThat(result.responseText()).isEqualTo("Muted.");
        verify(orchestratorService).executeIntentDetailed("mute", Map.of(), "ru", "N/A", "mute", null);
    }

    @Test
    void executeDetailedWithIntentPrefersExplicitOriginalTextOverText() {
        when(orchestratorService.executeIntentDetailed(
                eq("open_url"), eq(Map.of("url", "https://x.com")), eq("en"), eq("corr-9"),
                eq("please open x"), eq("user-9")))
                .thenReturn(new IntentExecutionResult("Opening.", true, true, true, false, null));

        controller.executeDetailed("user-9", new OrchestratorController.ExecuteRequest(
                "open_url", Map.of("url", "https://x.com"), "raw transcript", "please open x", "en", "corr-9"));

        verify(orchestratorService).executeIntentDetailed(
                "open_url", Map.of("url", "https://x.com"), "en", "corr-9", "please open x", "user-9");
    }

    @Test
    void executeDetailedWithIntentFallsBackToTextWhenOriginalTextAbsent() {
        when(orchestratorService.executeIntentDetailed(
                eq("open_url"), eq(Map.of()), eq("ru"), eq("corr-2"), eq("raw transcript"), eq("user-2")))
                .thenReturn(new IntentExecutionResult("Opening.", true, true, true, false, null));

        controller.executeDetailed("user-2", new OrchestratorController.ExecuteRequest(
                "open_url", Map.of(), "raw transcript", null, null, "corr-2"));

        verify(orchestratorService).executeIntentDetailed(
                "open_url", Map.of(), "ru", "corr-2", "raw transcript", "user-2");
    }

    @Test
    void executeDetailedWithIntentFallsBackToIntentWhenNoTextAtAll() {
        when(orchestratorService.executeIntentDetailed(
                eq("mute"), eq(Map.of()), eq("ru"), eq("corr-3"), eq("mute"), eq("user-3")))
                .thenReturn(new IntentExecutionResult("Muted.", true, true, true, false, null));

        controller.executeDetailed("user-3", new OrchestratorController.ExecuteRequest(
                "mute", Map.of(), null, null, null, "corr-3"));

        verify(orchestratorService).executeIntentDetailed(
                "mute", Map.of(), "ru", "corr-3", "mute", "user-3");
    }

    @Test
    void executeDetailedWithTextOnlyCallsProcessTextDetailed() {
        when(orchestratorService.processTextDetailed(eq("what time is it"), eq("ru"), eq("corr-4"), eq("user-4")))
                .thenReturn(new IntentExecutionResult("It is noon, sir.", false, false, false, false, null));

        IntentExecutionResult result = controller.executeDetailed("user-4", new OrchestratorController.ExecuteRequest(
                null, null, "what time is it", null, null, "corr-4"));

        assertThat(result.responseText()).isEqualTo("It is noon, sir.");
        verify(orchestratorService).processTextDetailed("what time is it", "ru", "corr-4", "user-4");
    }

    @Test
    void executeDetailedThrowsWhenNeitherIntentNorTextProvided() {
        assertThatThrownBy(() -> controller.executeDetailed("user-5",
                new OrchestratorController.ExecuteRequest(null, null, null, null, null, "corr-5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Either intent or text must be provided");
    }
}
