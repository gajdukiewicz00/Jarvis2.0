package org.jarvis.analytics.dto;

/**
 * Response for the guarded natural-language analytics endpoint.
 *
 * @param llmUsed whether llm-service actually answered ({@code false} when
 *                disabled, unreachable, privacy-blocked, or a canonical
 *                rule-based question matched, all of which use a rule-based
 *                answer instead)
 * @param status  one of {@code OK}, {@code LLM_DISABLED}, {@code LLM_UNAVAILABLE},
 *                {@code EMPTY_QUESTION}, {@code RULE_BASED_MATCH} (a canonical question
 *                like "куда ушли деньги" was answered deterministically without the LLM),
 *                {@code PRIVACY_BLOCKED} (the LLM privacy guard refused to send the
 *                derived context to a non-local/non-cleared provider)
 */
public record NlAnalyticsResponseDTO(String question, String answer, boolean llmUsed, String status) {
}
