package org.jarvis.llm.config;

import org.jarvis.llm.client.ExternalApiLlmProvider;
import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MockLlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmProviderConfigTest {

    private final LlmProviderConfig config = new LlmProviderConfig();

    @SuppressWarnings("unchecked")
    @Test
    void selectsProviderByConfigValue() {
        LlmClient llama = mock(LlmClient.class);
        when(llama.providerName()).thenReturn("llama-cpp");
        MockLlmProvider mockProvider = new MockLlmProvider();
        ExternalApiLlmProvider external = mock(ExternalApiLlmProvider.class);
        when(external.providerName()).thenReturn("external");

        ObjectProvider<MockLlmProvider> mockOP = mock(ObjectProvider.class);
        ObjectProvider<ExternalApiLlmProvider> externalOP = mock(ObjectProvider.class);
        when(mockOP.getIfAvailable(any())).thenReturn(mockProvider);
        when(externalOP.getIfAvailable(any())).thenReturn(external);

        assertThat(config.activeLlmProvider(llama, mockOP, externalOP, "llama")).isSameAs(llama);
        assertThat(config.activeLlmProvider(llama, mockOP, externalOP, null)).isSameAs(llama); // default
        assertThat(config.activeLlmProvider(llama, mockOP, externalOP, "mock").providerName()).isEqualTo("mock");
        assertThat(config.activeLlmProvider(llama, mockOP, externalOP, "external")).isSameAs(external);
    }
}
