package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LlmAnalyticsClient;
import org.jarvis.analytics.dto.NlAnalyticsResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NlAnalyticsServiceTest {

    @Mock
    private LlmAnalyticsClient llmAnalyticsClient;

    @Mock
    private InsightService insightService;

    @Test
    void askReturnsRuleBasedFallbackWhenLlmDisabled() {
        when(insightService.dailyReport()).thenReturn(Map.of("report", "Сводка дня. Всё хорошо."));
        NlAnalyticsService service = new NlAnalyticsService(llmAnalyticsClient, insightService, false);

        NlAnalyticsResponseDTO result = service.ask("user-1", "Как дела?");

        assertEquals("LLM_DISABLED", result.status());
        assertFalse(result.llmUsed());
        assertTrue(result.answer().contains("Сводка дня"));
        verifyNoInteractions(llmAnalyticsClient);
    }

    @Test
    void askReturnsEmptyQuestionGuardWhenQuestionBlank() {
        NlAnalyticsService service = new NlAnalyticsService(llmAnalyticsClient, insightService, true);

        NlAnalyticsResponseDTO result = service.ask("user-1", "   ");

        assertEquals("EMPTY_QUESTION", result.status());
        assertFalse(result.llmUsed());
        verifyNoInteractions(llmAnalyticsClient, insightService);
    }

    @Test
    void askDelegatesToLlmClientWhenEnabledAndReturnsAnswer() {
        when(insightService.dailyReport()).thenReturn(Map.of("report", "context text"));
        when(llmAnalyticsClient.ask(eq("user-1"), eq("Почему я устал?"), eq("context text")))
                .thenReturn("Ты мало спал.");
        NlAnalyticsService service = new NlAnalyticsService(llmAnalyticsClient, insightService, true);

        NlAnalyticsResponseDTO result = service.ask("user-1", "Почему я устал?");

        assertEquals("OK", result.status());
        assertTrue(result.llmUsed());
        assertEquals("Ты мало спал.", result.answer());
    }

    @Test
    void askFallsBackToRuleBasedSummaryWhenLlmThrows() {
        when(insightService.dailyReport()).thenReturn(Map.of("report", "context text"));
        when(llmAnalyticsClient.ask(any(), any(), any()))
                .thenThrow(new LlmAnalyticsClient.LlmUnavailableException("down", new RuntimeException("boom")));
        NlAnalyticsService service = new NlAnalyticsService(llmAnalyticsClient, insightService, true);

        NlAnalyticsResponseDTO result = service.ask("user-1", "Почему я устал?");

        assertEquals("LLM_UNAVAILABLE", result.status());
        assertFalse(result.llmUsed());
        assertTrue(result.answer().contains("context text"));
    }

    @Test
    void askHandlesNullUserId() {
        when(insightService.dailyReport()).thenReturn(Map.of("report", "context text"));
        when(llmAnalyticsClient.ask(isNull(), eq("Почему я устал?"), eq("context text")))
                .thenReturn("Ответ без userId.");
        NlAnalyticsService service = new NlAnalyticsService(llmAnalyticsClient, insightService, true);

        NlAnalyticsResponseDTO result = service.ask(null, "Почему я устал?");

        assertEquals("OK", result.status());
        assertEquals("Ответ без userId.", result.answer());
    }
}
