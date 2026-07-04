package org.jarvis.llm.client;

import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockLlmProviderTest {

    private final MockLlmProvider provider = new MockLlmProvider();

    @Test
    void providerNameIsMock() {
        assertThat(provider.providerName()).isEqualTo("mock");
        assertThat(provider.isHealthy()).isTrue();
    }

    @Test
    void mockIsLocalAndAllowsSensitive() {
        assertThat(provider.isLocal()).isTrue();
        assertThat(provider.allowsSensitiveData()).isTrue();
    }

    @Test
    void plainChatReturnsDeterministicReply() {
        ChatResponseDto r1 = provider.chat(List.of(new ChatMessageDto("user", "Привет")), 100, 0.5, "c1");
        ChatResponseDto r2 = provider.chat(List.of(new ChatMessageDto("user", "Привет")), 100, 0.5, "c2");

        assertThat(r1.getReply()).isNotBlank();
        assertThat(r1.getReply()).isEqualTo(r2.getReply()); // deterministic
        assertThat(r1.getModel()).isEqualTo("mock-provider");
    }

    @Test
    void orchestrationPromptReturnsValidEmptyToolPlanJson() {
        // When the prompt looks like a tool-plan request, the mock returns a
        // valid (empty) plan so the orchestrator can run without a GPU.
        ChatResponseDto r = provider.chat(
                List.of(new ChatMessageDto("system", "Available tools: TOOLS_JSON ... return tool_calls")),
                256, 0.2, "c3");

        assertThat(r.getReply()).contains("\"tool_calls\"");
        assertThat(r.getReply()).contains("\"confidence\"");
    }
}
