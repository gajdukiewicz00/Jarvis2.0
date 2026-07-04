package org.jarvis.llm.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.llm.client.LlmProvider;
import org.jarvis.llm.config.ModelProfileProperties;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.orchestrator.dto.OrchestrationRequest;
import org.jarvis.llm.orchestrator.dto.OrchestrationResponse;
import org.jarvis.llm.safety.UntrustedTextGuard;
import org.jarvis.llm.service.TokenBudgetManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2 — confidence is propagated from the model plan to the orchestration
 * response. Uses a stubbed {@link LlmProvider} (no GPU).
 */
class LlmOrchestratorServiceTest {

    private LlmProvider llmClient;
    private LlmOrchestratorService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmProvider.class);
        TokenBudgetManager tokenBudgetManager = mock(TokenBudgetManager.class);
        SystemPromptProvider systemPromptProvider = mock(SystemPromptProvider.class);
        ToolSchemaRegistry toolSchemaRegistry = mock(ToolSchemaRegistry.class);
        ToolCallValidator toolCallValidator = mock(ToolCallValidator.class);
        ModelProfileProperties profileProperties = mock(ModelProfileProperties.class);

        when(systemPromptProvider.getPrompt()).thenReturn("You are orchestrator. {{TOOLS_JSON}}");
        when(toolSchemaRegistry.renderForPrompt()).thenReturn("[]");
        when(tokenBudgetManager.buildMessages(any(), any(), any(), any()))
                .thenReturn(List.of(new ChatMessageDto("user", "do x")));
        when(profileProperties.resolve(any())).thenReturn(new ModelProfileProperties.Profile(256, 0.2, 30));

        service = new LlmOrchestratorService(
                llmClient, tokenBudgetManager, systemPromptProvider, toolSchemaRegistry,
                toolCallValidator, new ObjectMapper(), profileProperties, new UntrustedTextGuard());
    }

    @Test
    void propagatesModelConfidenceToResponse() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"ok\",\"confidence\":0.77,\"tool_calls\":[]}", null, "mock", 1, null));

        OrchestrationRequest req = new OrchestrationRequest(
                "s1", "2", "сделай план", Map.of(), Boolean.FALSE, "ru", 2);

        OrchestrationResponse response = service.orchestrate(req, "c1");

        assertThat(response.getConfidence()).isEqualTo(0.77);
        assertThat(response.getExplanation()).isEqualTo("ok");
    }
}
