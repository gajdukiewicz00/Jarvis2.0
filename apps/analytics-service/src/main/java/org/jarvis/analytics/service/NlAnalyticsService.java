package org.jarvis.analytics.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.client.LlmAnalyticsClient;
import org.jarvis.analytics.dto.NlAnalyticsResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Guarded natural-language analytics endpoint. When {@code analytics.nl.llm.enabled}
 * is {@code false} (default) or llm-service is unreachable, falls back to a
 * rule-based summary built from {@link InsightService#dailyReport()} instead of
 * failing the request — llm-service needs the host GPU Qwen3-14B brain, which
 * isn't assumed to be present/reachable in every environment.
 */
@Slf4j
@Service
public class NlAnalyticsService {

    private final LlmAnalyticsClient llmAnalyticsClient;
    private final InsightService insightService;
    private final boolean llmEnabled;

    public NlAnalyticsService(
            LlmAnalyticsClient llmAnalyticsClient,
            InsightService insightService,
            @Value("${analytics.nl.llm.enabled:false}") boolean llmEnabled) {
        this.llmAnalyticsClient = llmAnalyticsClient;
        this.insightService = insightService;
        this.llmEnabled = llmEnabled;
    }

    public NlAnalyticsResponseDTO ask(String userId, String question) {
        if (question == null || question.isBlank()) {
            return new NlAnalyticsResponseDTO(question,
                    "Задайте вопрос, например: «Почему неделя пошла плохо?»", false, "EMPTY_QUESTION");
        }

        String contextSummary = buildContextSummary();
        if (!llmEnabled) {
            return new NlAnalyticsResponseDTO(question, ruleBasedFallback(contextSummary), false, "LLM_DISABLED");
        }

        try {
            String answer = llmAnalyticsClient.ask(userId, question, contextSummary);
            return new NlAnalyticsResponseDTO(question, answer, true, "OK");
        } catch (RuntimeException e) {
            log.warn("NL analytics: llm-service unavailable, falling back to rule-based summary: {}", e.getMessage());
            return new NlAnalyticsResponseDTO(question, ruleBasedFallback(contextSummary), false, "LLM_UNAVAILABLE");
        }
    }

    private String buildContextSummary() {
        Map<String, Object> report = insightService.dailyReport();
        Object text = report.get("report");
        return text == null ? "" : text.toString();
    }

    private String ruleBasedFallback(String contextSummary) {
        return "Готовый анализ на LLM недоступен, вот сводка на основе правил: "
                + (contextSummary.isBlank() ? "нет данных." : contextSummary);
    }
}
