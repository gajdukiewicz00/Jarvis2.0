package org.jarvis.analytics.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.client.LlmAnalyticsClient;
import org.jarvis.analytics.dto.NlAnalyticsResponseDTO;
import org.jarvis.analytics.safety.AnalyticsTextGuard;
import org.jarvis.analytics.safety.LlmPrivacyGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Guarded natural-language analytics endpoint.
 *
 * <p>Canonical questions ("куда ушли деньги", "почему я устал", "что
 * изменилось за неделю", "какие привычки просели", "что улучшить завтра")
 * are answered deterministically by {@link ConcreteAnswerService} and never
 * touch the LLM — llm-service needs the host GPU Qwen3-14B brain, which
 * isn't assumed to be present/reachable in every environment, so these core
 * answers must not depend on it.</p>
 *
 * <p>For everything else: when {@code analytics.nl.llm.enabled} is
 * {@code false} (default) or llm-service is unreachable, falls back to a
 * rule-based summary built from {@link InsightService#dailyReport()}
 * instead of failing the request.</p>
 *
 * <p>Two guards sit in front of the LLM hand-off: {@link AnalyticsTextGuard}
 * neutralizes prompt-injection markers in the user's question before it is
 * ever forwarded to llm-service, and {@link LlmPrivacyGuard} refuses to
 * forward the derived context if it were ever to contain raw finance/health
 * records instead of aggregates.</p>
 */
@Slf4j
@Service
public class NlAnalyticsService {

    private final LlmAnalyticsClient llmAnalyticsClient;
    private final InsightService insightService;
    private final AnalyticsTextGuard textGuard;
    private final LlmPrivacyGuard privacyGuard;
    private final ConcreteAnswerService concreteAnswerService;
    private final boolean llmEnabled;

    public NlAnalyticsService(
            LlmAnalyticsClient llmAnalyticsClient,
            InsightService insightService,
            AnalyticsTextGuard textGuard,
            LlmPrivacyGuard privacyGuard,
            ConcreteAnswerService concreteAnswerService,
            @Value("${analytics.nl.llm.enabled:false}") boolean llmEnabled) {
        this.llmAnalyticsClient = llmAnalyticsClient;
        this.insightService = insightService;
        this.textGuard = textGuard;
        this.privacyGuard = privacyGuard;
        this.concreteAnswerService = concreteAnswerService;
        this.llmEnabled = llmEnabled;
    }

    public NlAnalyticsResponseDTO ask(String userId, String question) {
        if (question == null || question.isBlank()) {
            return new NlAnalyticsResponseDTO(question,
                    "Задайте вопрос, например: «Почему неделя пошла плохо?»", false, "EMPTY_QUESTION");
        }

        // Prompt-injection guard: neutralize before matching or forwarding to the LLM.
        String neutralizedQuestion = textGuard.neutralize(question);

        Optional<String> concreteAnswer = concreteAnswerService.tryAnswer(neutralizedQuestion);
        if (concreteAnswer.isPresent()) {
            return new NlAnalyticsResponseDTO(question, concreteAnswer.get(), false, "RULE_BASED_MATCH");
        }

        Map<String, Object> report = insightService.dailyReport();
        String contextSummary = extractSummary(report);
        if (!llmEnabled) {
            return new NlAnalyticsResponseDTO(question, ruleBasedFallback(contextSummary), false, "LLM_DISABLED");
        }

        try {
            // Privacy guard: never let raw finance/health records reach a non-local/non-cleared provider.
            privacyGuard.assertSafeForExternalLlm(report);
            String answer = llmAnalyticsClient.ask(userId, neutralizedQuestion, contextSummary);
            return new NlAnalyticsResponseDTO(question, answer, true, "OK");
        } catch (LlmPrivacyGuard.SensitiveDataBlockedException e) {
            log.warn("NL analytics: privacy guard blocked external LLM call: {}", e.getMessage());
            return new NlAnalyticsResponseDTO(question, ruleBasedFallback(contextSummary), false, "PRIVACY_BLOCKED");
        } catch (RuntimeException e) {
            log.warn("NL analytics: llm-service unavailable, falling back to rule-based summary: {}", e.getMessage());
            return new NlAnalyticsResponseDTO(question, ruleBasedFallback(contextSummary), false, "LLM_UNAVAILABLE");
        }
    }

    private String extractSummary(Map<String, Object> report) {
        Object text = report.get("report");
        return text == null ? "" : text.toString();
    }

    private String ruleBasedFallback(String contextSummary) {
        return "Готовый анализ на LLM недоступен, вот сводка на основе правил: "
                + (contextSummary.isBlank() ? "нет данных." : contextSummary);
    }
}
