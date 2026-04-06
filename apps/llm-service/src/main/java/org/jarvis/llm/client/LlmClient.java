package org.jarvis.llm.client;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for communicating with Python LLM server.
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
public class LlmClient {

    private final RestTemplate chatRestTemplate;
    private final RestTemplate healthRestTemplate;
    private final String llmServerUrl;
    private final boolean enabled;

    // Retry only on connection errors, not on timeouts
    private static final int MAX_CONNECT_RETRIES = 2; // 1 initial + 1 retry
    private static final long RETRY_DELAY_MS = 500;

    // Track health state to log only on state changes
    private volatile boolean lastHealthState = false;
    private volatile long lastHealthCheckTime = 0;
    private static final long HEALTH_CHECK_LOG_INTERVAL_MS = 60000;

    public LlmClient(
            @Qualifier("llmChatRestTemplate") RestTemplate chatRestTemplate,
            @Qualifier("llmHealthRestTemplate") RestTemplate healthRestTemplate,
            @Value("${llm.base-url:http://localhost:5000}") String llmServerUrl,
            @Value("${jarvis.llm.enabled:true}") boolean enabled) {
        this.chatRestTemplate = chatRestTemplate;
        this.healthRestTemplate = healthRestTemplate;
        this.llmServerUrl = llmServerUrl;
        this.enabled = enabled;
        
        // Diagnostic logging
        log.info("========================================");
        log.info("🤖 LlmClient INITIALIZED:");
        log.info("   baseUrl = {}", llmServerUrl);
        log.info("   enabled = {}", enabled);
        log.info("   chatRestTemplate.requestFactory = {}", 
                chatRestTemplate.getRequestFactory().getClass().getSimpleName());
        log.info("   healthRestTemplate.requestFactory = {}", 
                healthRestTemplate.getRequestFactory().getClass().getSimpleName());
        log.info("========================================");
    }

    /**
     * Send chat request to LLM server.
     * 
     * Retry policy:
     * - NO retry on timeout (SocketTimeout) - inference is expensive
     * - 1 retry on connection error with 500ms backoff
     * - NO retry on 4xx errors
     */
    public ChatResponseDto chat(List<ChatMessageDto> messages, Integer maxTokens, Double temperature,
            String correlationId) {
        
        String url = llmServerUrl + "/api/v1/llm/chat";
        long startTime = System.currentTimeMillis();

        log.info("[{}] LLM chat -> POST {} (messages={})", correlationId, url, messages.size());

        // Build request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("messages", messages.stream()
                .map(m -> Map.of(
                        "role", m.getRole().name().toLowerCase(),
                        "content", m.getContent()))
                .toList());

        if (maxTokens != null) {
            requestBody.put("max_tokens", maxTokens);
        }
        if (temperature != null) {
            requestBody.put("temperature", temperature);
        }

        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (correlationId != null) {
            headers.set("X-Correlation-ID", correlationId);
        }
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // Retry logic - only for connection errors, NOT for timeouts
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_RETRIES; attempt++) {
            try {
                log.debug("[{}] Sending chat request (attempt {}/{})", 
                        correlationId, attempt, MAX_CONNECT_RETRIES);

                ResponseEntity<ChatResponseDto> response = chatRestTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        request,
                        ChatResponseDto.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    ChatResponseDto body = response.getBody();
                    log.info("[{}] LLM chat <- {} in {}ms: reply.length={}, tokens={}",
                            correlationId, response.getStatusCode().value(), elapsed,
                            body.getReply() != null ? body.getReply().length() : 0,
                            body.getTokens());
                    return body;
                }
                
                // Non-2xx or null body
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("[{}] LLM chat <- {} in {}ms: EMPTY/NULL BODY", 
                        correlationId, response.getStatusCode().value(), elapsed);

            } catch (HttpClientErrorException e) {
                // 4xx - don't retry, client error
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("[{}] LLM chat <- 4xx in {}ms: {} - {}", 
                        correlationId, elapsed, e.getStatusCode(), e.getResponseBodyAsString());
                throw new LlmClientException("Invalid request to LLM server: " + e.getMessage(), e);

            } catch (HttpServerErrorException e) {
                // 5xx - retry once
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("[{}] LLM chat <- 5xx in {}ms (attempt {}/{}): {} - {}",
                        correlationId, elapsed, attempt, MAX_CONNECT_RETRIES,
                        e.getStatusCode(), e.getResponseBodyAsString());
                lastException = e;

            } catch (ResourceAccessException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                
                // Check if this is a timeout - DO NOT RETRY timeouts
                if (isTimeoutException(e)) {
                    log.error("[{}] LLM chat <- TIMEOUT in {}ms (NO RETRY): {}", 
                            correlationId, elapsed, e.getMessage());
                    throw new LlmClientException("LLM request timed out after " + elapsed + "ms", e);
                }
                
                // Connection error - retry once
                log.warn("[{}] LLM chat <- CONNECTION ERROR in {}ms (attempt {}/{}): {}",
                        correlationId, elapsed, attempt, MAX_CONNECT_RETRIES, e.getMessage());
                lastException = e;

            } catch (RuntimeException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("[{}] LLM chat <- EXCEPTION in {}ms: {} - {}", 
                        correlationId, elapsed, e.getClass().getSimpleName(), e.getMessage());
                throw new LlmClientException("Unexpected error: " + e.getMessage(), e);
            }

            // Wait before retry (except on last attempt)
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
                "Failed to get response from LLM server after " + MAX_CONNECT_RETRIES + " attempts",
                lastException);
    }

    /**
     * Check if exception is a timeout (SocketTimeout or ReadTimeout).
     * These should NOT be retried - inference is expensive.
     */
    private boolean isTimeoutException(ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            // Check message for read timeout indication
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Read timed out") || msg.contains("timeout"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Check if LLM server is healthy.
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

        String url = llmServerUrl + "/health";
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("LLM health check -> GET {}", url);
            ResponseEntity<Map> response = healthRestTemplate.getForEntity(url, Map.class);
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> body = response.getBody();
                Object status = body.get("status");
                Object modelLoaded = body.get("model_loaded");
                @SuppressWarnings("unchecked")
                Map<String, Object> diagnostics = body.get("diagnostics") instanceof Map<?, ?>
                        ? (Map<String, Object>) body.get("diagnostics")
                        : Collections.emptyMap();

                boolean isHealthy = "healthy".equals(status) || Boolean.TRUE.equals(modelLoaded);

                log.info("LLM health check <- {} in {}ms: status={}, model_loaded={}, result={}",
                        response.getStatusCode().value(), elapsed, status, modelLoaded, isHealthy);
                
                logHealthStateChange(isHealthy, url, null);
                return new LlmServerHealth(
                        isHealthy,
                        stringValue(status),
                        stringValue(body.get("backend")),
                        Boolean.TRUE.equals(modelLoaded),
                        stringValue(body.get("device")),
                        booleanValue(body.get("gpu_available")),
                        stringValue(body.get("cuda_version")),
                        stringValue(diagnostics.get("model_name")),
                        stringValue(diagnostics.get("model_path")),
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

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return null;
    }

    private void logHealthStateChange(boolean isHealthy, String url, String errorMsg) {
        long now = System.currentTimeMillis();
        boolean stateChanged = isHealthy != lastHealthState;
        boolean shouldLog = stateChanged || 
                (now - lastHealthCheckTime > HEALTH_CHECK_LOG_INTERVAL_MS && !isHealthy);

        if (shouldLog) {
            if (isHealthy) {
                log.info("✓ LLM server health check passed: {}", url);
            } else if (errorMsg != null) {
                log.warn("⚠ LLM server health check failed: {}", errorMsg);
            } else {
                log.warn("⚠ LLM server health check failed");
            }
            lastHealthState = isHealthy;
            lastHealthCheckTime = now;
        } else if (!isHealthy) {
            log.debug("LLM health check failed: {}", errorMsg);
        }
    }

    /**
     * Custom exception for LLM client errors
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
}
