package org.jarvis.llm.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.orchestrator.dto.ModelToolCall;
import org.jarvis.llm.orchestrator.dto.ModelToolPlan;
import org.jarvis.llm.orchestrator.dto.OrchestrationRequest;
import org.jarvis.llm.orchestrator.dto.OrchestrationResponse;
import org.jarvis.llm.orchestrator.dto.ToolCallDto;
import org.jarvis.llm.service.TokenBudgetManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmOrchestratorService {

    private static final double DEFAULT_TEMPERATURE = 0.2;
    private static final int DEFAULT_MAX_TOKENS = 700;

    private final LlmClient llmClient;
    private final TokenBudgetManager tokenBudgetManager;
    private final SystemPromptProvider systemPromptProvider;
    private final ToolSchemaRegistry toolSchemaRegistry;
    private final ObjectMapper objectMapper;

    public OrchestrationResponse orchestrate(OrchestrationRequest request, String correlationId) {
        String systemPrompt = buildSystemPrompt();
        String userMessage = buildUserMessage(request);

        String memoryContext = resolveMemoryContext(request);

        List<ChatMessageDto> messages = tokenBudgetManager.buildMessages(
                systemPrompt,
                memoryContext,
                List.of(),
                userMessage
        );

        ChatResponseDto response = llmClient.chat(messages, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE, correlationId);
        String raw = response.getReply();

        ModelToolPlan plan = parsePlan(raw);
        List<String> warnings = new ArrayList<>();
        List<ToolCallDto> toolCalls = new ArrayList<>();

        if (plan.getToolCalls() == null || plan.getToolCalls().isEmpty()) {
            warnings.add("LLM returned no tool_calls");
        } else {
            for (ModelToolCall call : plan.getToolCalls()) {
                if (call.getName() == null || call.getName().isBlank()) {
                    warnings.add("Tool call missing name");
                    continue;
                }
                ToolCallDto toolCall = new ToolCallDto();
                toolCall.setName(call.getName());
                toolCall.setArguments(call.getArguments());
                toolCall.setRequiresConfirmation(call.getRequiresConfirmation() != null
                        ? call.getRequiresConfirmation()
                        : Boolean.FALSE);
                toolCall.setIdempotencyKey(buildIdempotencyKey(request.getUserId(), call.getName(), call.getArguments()));
                toolCalls.add(toolCall);
                log.info("[{}] AI_TOOL_PLANNED userId={} tool={} idempotencyKey={} outcome=planned",
                        correlationId, request.getUserId(), toolCall.getName(), toolCall.getIdempotencyKey());
            }
        }

        if (plan.getWarnings() != null && !plan.getWarnings().isEmpty()) {
            warnings.addAll(plan.getWarnings());
        }

        if (toolCalls.isEmpty()) {
            log.info("[{}] AI_TOOL_PLAN_EMPTY userId={} outcome=empty explanation={}",
                    correlationId, request.getUserId(), plan.getExplanation());
        }

        return new OrchestrationResponse(
                plan.getExplanation(),
                toolCalls,
                warnings.isEmpty() ? null : warnings,
                raw
        );
    }

    private String buildSystemPrompt() {
        String basePrompt = systemPromptProvider.getPrompt();
        String tools = toolSchemaRegistry.renderForPrompt();
        return basePrompt.replace("{{TOOLS_JSON}}", tools);
    }

    private String buildUserMessage(OrchestrationRequest request) {
        Map<String, Object> payload = Map.of(
                "intent", request.getIntent(),
                "context", request.getContext() == null ? Map.of() : request.getContext(),
                "locale", request.getLocale() == null ? "ru" : request.getLocale(),
                "timestamp", Instant.now().toString(),
                "maxToolCalls", request.getMaxToolCalls() == null ? 4 : request.getMaxToolCalls()
        );
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize orchestration payload: {}", e.getMessage());
            return "{\"intent\":\"" + request.getIntent() + "\"}";
        }
    }

    private String resolveMemoryContext(OrchestrationRequest request) {
        if (!Boolean.TRUE.equals(request.getIncludeMemory())) {
            return "";
        }
        if (request.getContext() == null) {
            return "";
        }
        Object memory = request.getContext().get("memory");
        if (memory == null) {
            return "";
        }
        if (memory instanceof String value) {
            return value;
        }
        try {
            return objectMapper.writeValueAsString(memory);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize memory context: {}", e.getMessage());
            return "";
        }
    }

    private ModelToolPlan parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ModelToolPlan(List.of(), "", List.of("Empty LLM response"));
        }
        String json = extractJson(raw);
        try {
            return objectMapper.readValue(json, ModelToolPlan.class);
        } catch (Exception e) {
            log.warn("Failed to parse tool plan JSON: {}", e.getMessage());
            return new ModelToolPlan(List.of(), "", List.of("Invalid tool plan JSON"));
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1).trim();
        }
        return raw.trim();
    }

    private String buildIdempotencyKey(String userId, String toolName, Map<String, Object> args) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "userId", userId,
                    "tool", toolName,
                    "args", args == null ? Map.of() : args
            ));
        } catch (JsonProcessingException e) {
            payload = userId + ":" + toolName + ":" + String.valueOf(args);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (Exception e) {
            return "idem-" + Integer.toHexString(payload.hashCode());
        }
    }
}
