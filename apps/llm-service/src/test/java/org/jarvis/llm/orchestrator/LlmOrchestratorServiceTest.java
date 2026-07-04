package org.jarvis.llm.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.llm.client.LlmProvider;
import org.jarvis.llm.config.ModelProfileProperties;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.orchestrator.dto.OrchestrationRequest;
import org.jarvis.llm.orchestrator.dto.OrchestrationResponse;
import org.jarvis.llm.orchestrator.dto.ToolCallDto;
import org.jarvis.llm.safety.UntrustedTextGuard;
import org.jarvis.llm.service.TokenBudgetManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private TokenBudgetManager tokenBudgetManager;
    private ToolCallValidator realToolCallValidator;
    private LlmOrchestratorService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmProvider.class);
        tokenBudgetManager = mock(TokenBudgetManager.class);
        SystemPromptProvider systemPromptProvider = mock(SystemPromptProvider.class);
        ToolSchemaRegistry toolSchemaRegistry = mock(ToolSchemaRegistry.class);
        realToolCallValidator = new ToolCallValidator(new ObjectMapper());
        realToolCallValidator.loadFromParsed(List.of(
                Map.of("name", "create_todo", "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of("title", Map.of("type", "string", "minLength", 1)),
                        "required", List.of("title")
                ))
        ));
        ModelProfileProperties profileProperties = mock(ModelProfileProperties.class);

        when(systemPromptProvider.getPrompt()).thenReturn("You are orchestrator. {{TOOLS_JSON}}");
        when(toolSchemaRegistry.renderForPrompt()).thenReturn("[]");
        when(tokenBudgetManager.buildMessages(any(), any(), any(), any()))
                .thenReturn(List.of(new ChatMessageDto("user", "do x")));
        when(profileProperties.resolve(any())).thenReturn(new ModelProfileProperties.Profile(256, 0.2, 30));

        service = new LlmOrchestratorService(
                llmClient, tokenBudgetManager, systemPromptProvider, toolSchemaRegistry,
                realToolCallValidator, new ObjectMapper(), profileProperties, new UntrustedTextGuard());
    }

    private OrchestrationRequest request() {
        return new OrchestrationRequest("s1", "2", "сделай план", Map.of(), Boolean.FALSE, "ru", 2);
    }

    @Test
    void propagatesModelConfidenceToResponse() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"ok\",\"confidence\":0.77,\"tool_calls\":[]}", null, "mock", 1, null));

        OrchestrationResponse response = service.orchestrate(request(), "c1");

        assertThat(response.getConfidence()).isEqualTo(0.77);
        assertThat(response.getExplanation()).isEqualTo("ok");
    }

    @Test
    void emptyToolCallsAddsWarning() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"nothing to do\",\"tool_calls\":[]}", null, "mock", 1, null));

        OrchestrationResponse response = service.orchestrate(request(), "c-empty");

        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getWarnings()).contains("LLM returned no tool_calls");
    }

    @Test
    void acceptsValidToolCallAndDefaultsRequiresConfirmationToFalse() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto("""
                {"explanation":"do it","tool_calls":[{"name":"create_todo","arguments":{"title":"Buy milk"}}]}
                """, null, "mock", 1, null));

        OrchestrationResponse response = service.orchestrate(request(), "c-valid");

        assertThat(response.getToolCalls()).hasSize(1);
        ToolCallDto call = response.getToolCalls().get(0);
        assertThat(call.getName()).isEqualTo("create_todo");
        assertThat(call.getRequiresConfirmation()).isFalse();
        assertThat(call.getIdempotencyKey()).isNotBlank();
    }

    @Test
    void truncatesToolCallsAtMaxCalls() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto("""
                {"explanation":"multi","tool_calls":[
                  {"name":"create_todo","arguments":{"title":"one"}},
                  {"name":"create_todo","arguments":{"title":"two"}}
                ]}
                """, null, "mock", 1, null));

        OrchestrationRequest req = new OrchestrationRequest("s1", "2", "intent", Map.of(), Boolean.FALSE, "ru", 1);
        OrchestrationResponse response = service.orchestrate(req, "c-truncate");

        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getWarnings()).anyMatch(w -> w.contains("Truncated tool calls at max=1"));
    }

    @Test
    void rejectsInvalidToolCallAndReportsPlanEmpty() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto("""
                {"explanation":"bad tool","tool_calls":[{"name":"delete_everything","arguments":{}}]}
                """, null, "mock", 1, null));

        OrchestrationResponse response = service.orchestrate(request(), "c-invalid");

        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getWarnings()).anyMatch(w -> w.contains("unknown tool"));
    }

    @Test
    void parsePlanHandlesEmptyRawResponse() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "", null, "mock", 1, null));

        OrchestrationResponse response = service.orchestrate(request(), "c-blank");

        assertThat(response.getWarnings()).contains("Empty LLM response");
        assertThat(response.getExplanation()).isEmpty();
    }

    @Test
    void parsePlanHandlesInvalidJson() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "not json at all", null, "mock", 1, null));

        OrchestrationResponse response = service.orchestrate(request(), "c-badjson");

        assertThat(response.getWarnings()).contains("Invalid tool plan JSON");
    }

    @Test
    void parsePlanExtractsJsonEmbeddedInProseText() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "Here is the plan: {\"explanation\":\"ok\",\"tool_calls\":[]} Thanks!", null, "mock", 1, null));

        OrchestrationResponse response = service.orchestrate(request(), "c-embedded");

        assertThat(response.getExplanation()).isEqualTo("ok");
    }

    @Test
    void resolveMemoryContextReturnsEmptyWhenIncludeMemoryFalse() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"ok\",\"tool_calls\":[]}", null, "mock", 1, null));

        ArgumentCaptor<String> memoryCaptor = ArgumentCaptor.forClass(String.class);
        when(tokenBudgetManager.buildMessages(any(), memoryCaptor.capture(), any(), any()))
                .thenReturn(List.of(new ChatMessageDto("user", "do x")));

        OrchestrationRequest req = new OrchestrationRequest(
                "s1", "2", "intent", Map.of("memory", "should be ignored"), Boolean.FALSE, "ru", 2);
        service.orchestrate(req, "c-nomemory");

        assertThat(memoryCaptor.getValue()).isEmpty();
    }

    @Test
    void resolveMemoryContextWrapsStringMemoryAsUntrustedData() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"ok\",\"tool_calls\":[]}", null, "mock", 1, null));

        ArgumentCaptor<String> memoryCaptor = ArgumentCaptor.forClass(String.class);
        when(tokenBudgetManager.buildMessages(any(), memoryCaptor.capture(), any(), any()))
                .thenReturn(List.of(new ChatMessageDto("user", "do x")));

        OrchestrationRequest req = new OrchestrationRequest(
                "s1", "2", "intent", Map.of("memory", "prior note"), Boolean.TRUE, "ru", 2);
        service.orchestrate(req, "c-memory");

        assertThat(memoryCaptor.getValue()).contains("prior note");
        assertThat(memoryCaptor.getValue()).contains("UNTRUSTED_DATA");
    }

    @Test
    void resolveMemoryContextSerializesNonStringMemoryValue() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"ok\",\"tool_calls\":[]}", null, "mock", 1, null));

        ArgumentCaptor<String> memoryCaptor = ArgumentCaptor.forClass(String.class);
        when(tokenBudgetManager.buildMessages(any(), memoryCaptor.capture(), any(), any()))
                .thenReturn(List.of(new ChatMessageDto("user", "do x")));

        OrchestrationRequest req = new OrchestrationRequest(
                "s1", "2", "intent", Map.of("memory", Map.of("key", "value")), Boolean.TRUE, "ru", 2);
        service.orchestrate(req, "c-memory-obj");

        assertThat(memoryCaptor.getValue()).contains("key");
        assertThat(memoryCaptor.getValue()).contains("UNTRUSTED_DATA");
    }

    @Test
    void resolveMemoryContextReturnsEmptyWhenContextNull() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"ok\",\"tool_calls\":[]}", null, "mock", 1, null));

        ArgumentCaptor<String> memoryCaptor = ArgumentCaptor.forClass(String.class);
        when(tokenBudgetManager.buildMessages(any(), memoryCaptor.capture(), any(), any()))
                .thenReturn(List.of(new ChatMessageDto("user", "do x")));

        OrchestrationRequest req = new OrchestrationRequest("s1", "2", "intent", null, Boolean.TRUE, "ru", 2);
        service.orchestrate(req, "c-nullcontext");

        assertThat(memoryCaptor.getValue()).isEmpty();
    }

    @Test
    void buildUserMessageDefaultsLocaleAndMaxToolCallsWhenNull() {
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(new ChatResponseDto(
                "{\"explanation\":\"ok\",\"tool_calls\":[]}", null, "mock", 1, null));

        ArgumentCaptor<String> userMessageCaptor = ArgumentCaptor.forClass(String.class);
        when(tokenBudgetManager.buildMessages(any(), any(), any(), userMessageCaptor.capture()))
                .thenReturn(List.of(new ChatMessageDto("user", "do x")));

        OrchestrationRequest req = new OrchestrationRequest("s1", "2", "intent", Map.of(), null, null, null);
        service.orchestrate(req, "c-defaults");

        assertThat(userMessageCaptor.getValue()).contains("\"locale\":\"ru\"");
        assertThat(userMessageCaptor.getValue()).contains("\"maxToolCalls\":4");
    }
}
