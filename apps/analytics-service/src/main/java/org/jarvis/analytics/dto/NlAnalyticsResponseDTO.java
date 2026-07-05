package org.jarvis.analytics.dto;

/**
 * Response for the guarded natural-language analytics endpoint.
 *
 * @param llmUsed whether llm-service actually answered ({@code false} when
 *                disabled or unreachable and a rule-based fallback was used)
 * @param status  one of {@code OK}, {@code LLM_DISABLED}, {@code LLM_UNAVAILABLE},
 *                {@code EMPTY_QUESTION}
 */
public record NlAnalyticsResponseDTO(String question, String answer, boolean llmUsed, String status) {
}
