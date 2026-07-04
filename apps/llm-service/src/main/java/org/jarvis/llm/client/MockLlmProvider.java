package org.jarvis.llm.client;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Deterministic LLM provider for tests and GPU-free local dev. Selected with
 * {@code llm.provider=mock}. Never calls a real model.
 *
 * <p>Returns a valid (empty) tool-plan JSON when the prompt looks like an
 * orchestration request, so {@code LlmOrchestratorService} can be exercised
 * without a GPU; otherwise a short canned reply.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock")
public class MockLlmProvider implements LlmProvider {

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public boolean isLocal() {
        return true; // in-process mock — treated as local for privacy tests
    }

    @Override
    public ChatResponseDto chat(List<ChatMessageDto> messages, Integer maxTokens, Double temperature,
            String correlationId) {
        String joined = messages == null ? "" : messages.stream()
                .map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);

        boolean looksLikeToolPlan = joined.contains("tool_calls")
                || joined.contains("TOOLS_JSON")
                || joined.contains("\"tools\"");

        String reply = looksLikeToolPlan
                ? "{\"explanation\":\"[mock] no action planned\",\"confidence\":0.9,\"tool_calls\":[]}"
                : "[mock-llm] Готово, сэр.";

        log.info("[{}] MockLlmProvider returning deterministic reply (toolPlan={})", correlationId, looksLikeToolPlan);
        return new ChatResponseDto(reply, Map.of("prompt", 0, "completion", 0, "total", 0), "mock-provider", 1, null);
    }
}
