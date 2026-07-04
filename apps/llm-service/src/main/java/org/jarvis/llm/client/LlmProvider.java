package org.jarvis.llm.client;

import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;

import java.util.List;

/**
 * Abstraction over the chat LLM so the concrete backend (local llama.cpp,
 * an external OpenAI-compatible API, or a deterministic mock for tests) can be
 * swapped via the {@code llm.provider} property without touching callers
 * (orchestrator, RAG chat).
 *
 * <p>EPIC 2 — "External LLM support only behind a provider abstraction" +
 * "mock provider for tests". Health/lifecycle of the local host daemon stays on
 * the concrete {@link LlmClient}; this interface is intentionally narrow.</p>
 */
public interface LlmProvider {

    /**
     * Single chat-completion call. Implementations must return a non-null
     * {@link ChatResponseDto} or throw {@link LlmClient.LlmClientException}.
     */
    ChatResponseDto chat(List<ChatMessageDto> messages, Integer maxTokens, Double temperature,
            String correlationId);

    /** Whether this provider is currently usable. Defaults to true (e.g. mock/external stubs). */
    default boolean isHealthy() {
        return true;
    }

    /** Stable id of the provider implementation (for diagnostics/logs). */
    String providerName();

    /**
     * B2 — capability metadata for memory privacy enforcement. A local provider
     * (on-device llama.cpp) never sends data off the machine, so it may receive
     * {@code local_only} and {@code sensitive} memory. Remote providers default
     * to false and must not receive private/sensitive memory.
     */
    default boolean isLocal() {
        return false;
    }

    /** Whether this provider may receive {@code sensitive}-classified memory. Defaults to {@link #isLocal()}. */
    default boolean allowsSensitiveData() {
        return isLocal();
    }
}
