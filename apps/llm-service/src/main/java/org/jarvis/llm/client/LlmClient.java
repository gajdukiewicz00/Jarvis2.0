package org.jarvis.llm.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for communicating with the llama.cpp OpenAI-compatible host daemon.
 *
 * Retry policy:
 * - SocketTimeout/ReadTimeout: NO retry (inference takes time, retrying wastes resources)
 * - ConnectException/Connection refused: 1 retry with short backoff
 * - 5xx server errors: 1 retry with short backoff
 * - 4xx client errors: NO retry (immediate throw)
 * - Health checks: NO retry (fail fast)
 */
@Slf4j
@Component
public class LlmClient implements LlmProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String HEALTH_PATH = "/health";
    private static final String MODELS_PATH = "/v1/models";

    private final RestTemplate chatRestTemplate;
    private final RestTemplate healthRestTemplate;
    private final String llmServerUrl;
    private final boolean enabled;
    private final String configuredModel;
    private final String configuredModelPath;

    // Retry only on connection errors, not on timeouts.
    private static final int MAX_CONNECT_RETRIES = 2; // 1 initial + 1 retry
    private static final long RETRY_DELAY_MS = 500;

    // Track health state to log only on state changes.
    private volatile boolean lastHealthState = false;
    private volatile long lastHealthCheckTime = 0;
    private static final long HEALTH_CHECK_LOG_INTERVAL_MS = 60000;

    public LlmClient(
            @Qualifier("llmChatRestTemplate") RestTemplate chatRestTemplate,
            @Qualifier("llmHealthRestTemplate") RestTemplate healthRestTemplate,
            @Value("${llm.base-url:http://localhost:5000}") String llmServerUrl,
            @Value("${jarvis.llm.enabled:true}") boolean enabled,
            @Value("${JARVIS_LLM_MODEL_ID:Qwen/Qwen2.5-3B-Instruct-GGUF}") String configuredModel,
            @Value("${JARVIS_LLM_MODEL_PATH:}") String configuredModelPath) {
        this.chatRestTemplate = chatRestTemplate;
        this.healthRestTemplate = healthRestTemplate;
        this.llmServerUrl = llmServerUrl;
        this.enabled = enabled;
        this.configuredModel = configuredModel;
        this.configuredModelPath = configuredModelPath;

        log.info("========================================");
        log.info("🤖 LlmClient INITIALIZED:");
        log.info("   baseUrl = {}", llmServerUrl);
        log.info("   enabled = {}", enabled);
        log.info("   configuredModel = {}", configuredModel);
        log.info("   chatRestTemplate.requestFactory = {}",
                chatRestTemplate.getRequestFactory().getClass().getSimpleName());
        log.info("   healthRestTemplate.requestFactory = {}",
                healthRestTemplate.getRequestFactory().getClass().getSimpleName());
        log.info("========================================");
    }

    /**
     * Send chat request to the OpenAI-compatible llama.cpp endpoint.
     *
     * Retry policy:
     * - NO retry on timeout (SocketTimeout) - inference is expensive
     * - 1 retry on connection error with 500ms backoff
     * - NO retry on 4xx errors
     */
    @Override
    public String providerName() {
        return "llama-cpp";
    }

    @Override
    public boolean isLocal() {
        return true; // on-device llama.cpp — data never leaves the machine
    }

    @Override
    public ChatResponseDto chat(List<ChatMessageDto> messages, Integer maxTokens, Double temperature,
            String correlationId) {

        if (!enabled) {
            log.warn("[{}] LLM chat BLOCKED: jarvis.llm.enabled=false", correlationId);
            throw new LlmClientException("LLM is disabled (jarvis.llm.enabled=false)", null);
        }

        String url = llmServerUrl + CHAT_COMPLETIONS_PATH;
        long startTime = System.currentTimeMillis();

        log.info("[{}] LLM chat -> POST {} (messages={})", correlationId, url, messages.size());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", configuredModel);
        requestBody.put("stream", false);
        // Qwen3 reasoning control: without "/no_think" the model spends the whole
        // max_tokens budget on a hidden <think> block and returns an empty or
        // truncated reply (observed in RAG recall). The streaming path already
        // injects it; do the same here for every non-stream chat (RAG,
        // orchestrator reasoning, planner) at this single choke point.
        int lastUserIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equalsIgnoreCase(messages.get(i).getRole().name())) {
                lastUserIdx = i;
            }
        }
        final int noThinkIdx = lastUserIdx;
        requestBody.put("messages", java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(i -> {
                    ChatMessageDto m = messages.get(i);
                    String content = m.getContent() == null ? "" : m.getContent();
                    if (i == noThinkIdx && !content.contains("/no_think")) {
                        content = content + " /no_think";
                    }
                    return Map.of("role", m.getRole().name().toLowerCase(), "content", content);
                })
                .toList());

        if (maxTokens != null) {
            requestBody.put("max_tokens", maxTokens);
        }
        if (temperature != null) {
            requestBody.put("temperature", temperature);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (correlationId != null) {
            headers.set("X-Correlation-ID", correlationId);
        }
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_RETRIES; attempt++) {
            try {
                log.debug("[{}] Sending chat request (attempt {}/{})",
                        correlationId, attempt, MAX_CONNECT_RETRIES);

                ResponseEntity<Map> response = chatRestTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        request,
                        Map.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    ChatResponseDto body = mapOpenAiResponse(response.getBody(), elapsed);
                    log.info("[{}] LLM chat <- {} in {}ms: reply.length={}, tokens={}",
                            correlationId, response.getStatusCode().value(), elapsed,
                            body.getReply() != null ? body.getReply().length() : 0,
                            body.getTokens());
                    return body;
                }

                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("[{}] LLM chat <- {} in {}ms: EMPTY/NULL BODY",
                        correlationId, response.getStatusCode().value(), elapsed);

            } catch (HttpClientErrorException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("[{}] LLM chat <- 4xx in {}ms: {} - {}",
                        correlationId, elapsed, e.getStatusCode(), e.getResponseBodyAsString());
                throw new LlmClientException("Invalid request to LLM daemon: " + e.getMessage(), e);

            } catch (HttpServerErrorException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("[{}] LLM chat <- 5xx in {}ms (attempt {}/{}): {} - {}",
                        correlationId, elapsed, attempt, MAX_CONNECT_RETRIES,
                        e.getStatusCode(), e.getResponseBodyAsString());
                lastException = e;

            } catch (ResourceAccessException e) {
                long elapsed = System.currentTimeMillis() - startTime;

                if (isTimeoutException(e)) {
                    log.error("[{}] LLM chat <- TIMEOUT in {}ms (NO RETRY): {}",
                            correlationId, elapsed, e.getMessage());
                    throw new LlmClientException("LLM request timed out after " + elapsed + "ms", e);
                }

                log.warn("[{}] LLM chat <- CONNECTION ERROR in {}ms (attempt {}/{}): {}",
                        correlationId, elapsed, attempt, MAX_CONNECT_RETRIES, e.getMessage());
                lastException = e;

            } catch (RuntimeException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("[{}] LLM chat <- EXCEPTION in {}ms: {} - {}",
                        correlationId, elapsed, e.getClass().getSimpleName(), e.getMessage());
                throw new LlmClientException("Unexpected error: " + e.getMessage(), e);
            }

            if (attempt < MAX_CONNECT_RETRIES) {
                try {
                    log.debug("[{}] Waiting {}ms before retry...", correlationId, RETRY_DELAY_MS);
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmClientException("Retry interrupted", ie);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.error("[{}] ❌ LLM failed after {} attempts ({}ms)",
                correlationId, MAX_CONNECT_RETRIES, elapsed);
        throw new LlmClientException(
                "Failed to get response from LLM daemon after " + MAX_CONNECT_RETRIES + " attempts",
                lastException);
    }

    private ChatResponseDto mapOpenAiResponse(Map<?, ?> body, long elapsedMs) {
        String reply = extractAssistantMessage(body);
        if (reply == null) {
            throw new LlmClientException("LLM response missing choices[0].message.content", null);
        }

        Map<String, Integer> tokens = extractUsageTokens(body.get("usage"));
        String model = stringValue(body.get("model"));
        return new ChatResponseDto(
                reply,
                tokens.isEmpty() ? null : tokens,
                model != null ? model : configuredModel,
                Math.toIntExact(Math.min(elapsedMs, Integer.MAX_VALUE)),
                null);
    }

    private String extractAssistantMessage(Map<?, ?> body) {
        Object choices = body.get("choices");
        if (choices instanceof List<?> choiceList && !choiceList.isEmpty()) {
            Object first = choiceList.get(0);
            if (first instanceof Map<?, ?> choice) {
                Object message = choice.get("message");
                if (message instanceof Map<?, ?> messageMap) {
                    String content = stringValue(messageMap.get("content"));
                    if (content != null) {
                        return content;
                    }
                }
                String text = stringValue(choice.get("text"));
                if (text != null) {
                    return text;
                }
            }
        }
        return stringValue(body.get("reply"));
    }

    private Map<String, Integer> extractUsageTokens(Object usage) {
        Map<String, Integer> tokens = new LinkedHashMap<>();
        if (!(usage instanceof Map<?, ?> usageMap)) {
            return tokens;
        }

        putToken(tokens, "prompt", usageMap.get("prompt_tokens"));
        putToken(tokens, "completion", usageMap.get("completion_tokens"));
        putToken(tokens, "total", usageMap.get("total_tokens"));
        return tokens;
    }

    private void putToken(Map<String, Integer> tokens, String key, Object value) {
        Integer tokenValue = integerValue(value);
        if (tokenValue != null) {
            tokens.put(key, tokenValue);
        }
    }

    private boolean isTimeoutException(ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Read timed out") || msg.contains("timeout"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Check if the host model daemon is healthy.
     * Uses short timeout RestTemplate. NO retries - fail fast.
     * Returns false immediately if LLM is disabled.
     */
    public boolean isHealthy() {
        return getHealth().available();
    }

    public LlmServerHealth getHealth() {
        if (!enabled) {
            log.warn("LLM health check: DISABLED (jarvis.llm.enabled=false)");
            return new LlmServerHealth(
                    false,
                    "disabled",
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Collections.emptyMap(),
                    "jarvis.llm.enabled=false");
        }

        String url = llmServerUrl + HEALTH_PATH;
        long startTime = System.currentTimeMillis();

        try {
            log.debug("LLM health check -> GET {}", url);
            ResponseEntity<String> response = healthRestTemplate.getForEntity(url, String.class);
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> body = parseJsonObject(response.getBody());
                Object status = body.get("status");
                Object modelLoaded = body.get("model_loaded");
                @SuppressWarnings("unchecked")
                Map<String, Object> existingDiagnostics = body.get("diagnostics") instanceof Map<?, ?>
                        ? (Map<String, Object>) body.get("diagnostics")
                        : Collections.emptyMap();
                Map<String, Object> diagnostics = new LinkedHashMap<>(existingDiagnostics);
                OpenAiModelInfo modelInfo = fetchOpenAiModelInfo();

                String statusValue = stringValue(status);
                if (statusValue == null && response.getBody() != null && !response.getBody().isBlank()
                        && !response.getBody().stripLeading().startsWith("{")) {
                    statusValue = response.getBody().strip();
                }
                if (statusValue == null || statusValue.isBlank()) {
                    statusValue = "reachable";
                }

                boolean reachable = response.getStatusCode().is2xxSuccessful();
                boolean modelLoadedValue = Boolean.TRUE.equals(booleanValue(modelLoaded))
                        || modelInfo.modelName() != null
                        || healthyStatus(statusValue);
                boolean isHealthy = reachable && (healthyStatus(statusValue) || modelLoadedValue);

                diagnostics.put("runtime", "host-model-daemon");
                diagnostics.put("health_url", url);
                diagnostics.put("models_url", llmServerUrl + MODELS_PATH);
                diagnostics.put("chat_completions_url", llmServerUrl + CHAT_COMPLETIONS_PATH);
                diagnostics.put("configured_model", configuredModel);
                diagnostics.put("configured_model_path", configuredModelPath);
                diagnostics.put("model_profile", modelInfo.modelName() != null ? modelInfo.modelName() : configuredModel);
                diagnostics.put("reachable", reachable);
                if (modelInfo.modelCount() != null) {
                    diagnostics.put("openai_model_count", modelInfo.modelCount());
                }
                if (modelInfo.error() != null) {
                    diagnostics.put("models_error", modelInfo.error());
                }

                log.info("LLM health check <- {} in {}ms: status={}, model_loaded={}, result={}",
                        response.getStatusCode().value(), elapsed, statusValue, modelLoadedValue, isHealthy);

                logHealthStateChange(isHealthy, url, null);
                return new LlmServerHealth(
                        isHealthy,
                        statusValue,
                        "llamacpp-openai",
                        modelLoadedValue,
                        stringValue(body.get("device")),
                        booleanValue(body.get("gpu_available")),
                        stringValue(body.get("cuda_version")),
                        firstNonBlank(
                                modelInfo.modelName(),
                                stringValue(diagnostics.get("model_name")),
                                configuredModel),
                        firstNonBlank(
                                stringValue(diagnostics.get("model_path")),
                                configuredModelPath),
                        diagnostics,
                        null);
            }

            long elapsed2 = System.currentTimeMillis() - startTime;
            log.warn("LLM health check <- INVALID RESPONSE in {}ms: status={}, body={}",
                    elapsed2, response.getStatusCode(), response.getBody());
            logHealthStateChange(false, url, "invalid response: " + response.getStatusCode());
            return new LlmServerHealth(
                    false,
                    "invalid-response",
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Collections.emptyMap(),
                    "invalid response: " + response.getStatusCode());

        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("LLM health check <- EXCEPTION in {}ms: {} - {}",
                    elapsed, e.getClass().getSimpleName(), e.getMessage());
            logHealthStateChange(false, url, e.getClass().getSimpleName() + ": " + e.getMessage());
            return new LlmServerHealth(
                    false,
                    "error",
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Collections.emptyMap(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return null;
    }

    private boolean healthyStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        return "ok".equals(normalized)
                || "healthy".equals(normalized)
                || "ready".equals(normalized)
                || "reachable".equals(normalized)
                || "up".equals(normalized);
    }

    private Map<String, Object> parseJsonObject(String body) {
        if (body == null || body.isBlank() || !body.stripLeading().startsWith("{")) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            log.debug("Failed to parse LLM health JSON body: {}", exception.getMessage());
            return Collections.emptyMap();
        }
    }

    private OpenAiModelInfo fetchOpenAiModelInfo() {
        String url = llmServerUrl + MODELS_PATH;
        try {
            ResponseEntity<String> response = healthRestTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return new OpenAiModelInfo(null, null, "status=" + response.getStatusCode().value());
            }
            Map<String, Object> body = parseJsonObject(response.getBody());
            Object data = body.get("data");
            if (data instanceof List<?> models) {
                String firstModel = null;
                if (!models.isEmpty() && models.get(0) instanceof Map<?, ?> modelMap) {
                    firstModel = stringValue(modelMap.get("id"));
                }
                return new OpenAiModelInfo(firstModel, models.size(), null);
            }
            return new OpenAiModelInfo(null, null, "missing data[]");
        } catch (RuntimeException exception) {
            return new OpenAiModelInfo(null, null,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void logHealthStateChange(boolean isHealthy, String url, String errorMsg) {
        long now = System.currentTimeMillis();
        boolean stateChanged = isHealthy != lastHealthState;
        boolean shouldLog = stateChanged
                || (now - lastHealthCheckTime > HEALTH_CHECK_LOG_INTERVAL_MS && !isHealthy);

        if (shouldLog) {
            if (isHealthy) {
                log.info("✓ LLM host daemon health check passed: {}", url);
            } else if (errorMsg != null) {
                log.warn("⚠ LLM host daemon health check failed: {}", errorMsg);
            } else {
                log.warn("⚠ LLM host daemon health check failed");
            }
            lastHealthState = isHealthy;
            lastHealthCheckTime = now;
        } else if (!isHealthy) {
            log.debug("LLM health check failed: {}", errorMsg);
        }
    }

    /**
     * Custom exception for LLM client errors.
     */
    public static class LlmClientException extends RuntimeException {
        public LlmClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record LlmServerHealth(
            boolean available,
            String status,
            String backend,
            boolean modelLoaded,
            String device,
            Boolean gpuAvailable,
            String cudaVersion,
            String modelName,
            String modelPath,
            Map<String, Object> diagnostics,
            String error) {
    }

    private record OpenAiModelInfo(String modelName, Integer modelCount, String error) {
    }
}
