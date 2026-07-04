package org.jarvis.llm.config;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.client.ExternalApiLlmProvider;
import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.LlmProvider;
import org.jarvis.llm.client.MockLlmProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the active {@link LlmProvider} from the {@code llm.provider} property
 * ({@code llama} | {@code mock} | {@code external}). Defaults to the local
 * llama.cpp client. Callers inject {@link LlmProvider}; the concrete
 * {@link LlmClient} remains directly injectable for host-daemon
 * health/lifecycle code.
 */
@Slf4j
@Configuration
public class LlmProviderConfig {

    @Bean
    @Primary
    public LlmProvider activeLlmProvider(
            LlmClient llama,
            ObjectProvider<MockLlmProvider> mock,
            ObjectProvider<ExternalApiLlmProvider> external,
            @Value("${llm.provider:llama}") String provider) {
        LlmProvider selected = switch (provider == null ? "llama" : provider.trim().toLowerCase()) {
            case "mock" -> mock.getIfAvailable(() -> {
                throw new IllegalStateException("llm.provider=mock but MockLlmProvider bean is absent");
            });
            case "external" -> external.getIfAvailable(() -> {
                throw new IllegalStateException("llm.provider=external but ExternalApiLlmProvider bean is absent");
            });
            default -> llama;
        };
        log.info("🤖 Active LLM provider = {} (llm.provider={})", selected.providerName(), provider);
        return selected;
    }
}
