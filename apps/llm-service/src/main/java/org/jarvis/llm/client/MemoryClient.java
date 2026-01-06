package org.jarvis.llm.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Client for memory-service API.
 * Provides methods to ingest messages and search memory.
 */
@Slf4j
@Component
public class MemoryClient {

    private final RestTemplate restTemplate;
    private final String memoryServiceUrl;
    private final boolean enabled;

    public MemoryClient(
            @Value("${memory.service.url:http://localhost:8093}") String memoryServiceUrl,
            @Value("${memory.service.enabled:true}") boolean enabled) {
        this.restTemplate = new RestTemplate();
        this.memoryServiceUrl = memoryServiceUrl;
        this.enabled = enabled;
        
        log.info("MemoryClient initialized: url={}, enabled={}", memoryServiceUrl, enabled);
    }

    /**
     * Search memory for relevant context
     * 
     * @param userId User ID
     * @param query Search query
     * @param topK Maximum results
     * @param maxTokens Maximum tokens in result
     * @param correlationId Correlation ID for tracing
     * @return Memory context or empty string if not available
     */
    public String searchContext(String userId, String query, int topK, int maxTokens, String correlationId) {
        if (!enabled) {
            log.debug("[{}] Memory service disabled, skipping search", correlationId);
            return "";
        }

        String url = memoryServiceUrl + "/memory/search";

        try {
            Map<String, Object> requestBody = Map.of(
                    "userId", userId,
                    "query", query,
                    "topK", topK,
                    "maxTokens", maxTokens,
                    "minSimilarity", 0.5
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Correlation-ID", correlationId);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            long startTime = System.currentTimeMillis();
            ResponseEntity<SearchResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, SearchResponse.class);

            long elapsed = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                SearchResponse body = response.getBody();
                log.info("[{}] Memory search: {} chunks in {}ms, tokens={}",
                        correlationId, body.chunks != null ? body.chunks.size() : 0, 
                        elapsed, body.estimatedTokens);
                return body.contextText != null ? body.contextText : "";
            }

            log.warn("[{}] Memory search returned empty response", correlationId);
            return "";

        } catch (Exception e) {
            log.warn("[{}] Memory search failed (non-fatal): {}", correlationId, e.getMessage());
            return "";
        }
    }

    /**
     * Ingest messages into memory (async, fire-and-forget)
     */
    public void ingestAsync(String userId, String sessionId, String userMessage, String assistantReply, String correlationId) {
        if (!enabled) {
            log.debug("[{}] Memory service disabled, skipping ingest", correlationId);
            return;
        }

        String url = memoryServiceUrl + "/memory/ingest/async";

        try {
            Map<String, Object> requestBody = Map.of(
                    "userId", userId,
                    "sessionId", sessionId,
                    "messages", List.of(
                            Map.of("role", "user", "content", userMessage),
                            Map.of("role", "assistant", "content", assistantReply)
                    ),
                    "createChunks", true
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Correlation-ID", correlationId);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            // Fire-and-forget
            restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
            
            log.debug("[{}] Memory ingest queued", correlationId);

        } catch (Exception e) {
            log.warn("[{}] Memory ingest failed (non-fatal): {}", correlationId, e.getMessage());
        }
    }

    /**
     * Check if memory service is healthy
     */
    public boolean isHealthy() {
        if (!enabled) {
            return false;
        }

        try {
            String url = memoryServiceUrl + "/memory/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            return response.getStatusCode().is2xxSuccessful() 
                    && response.getBody() != null 
                    && "healthy".equals(response.getBody().get("status"));
        } catch (Exception e) {
            log.debug("Memory service health check failed: {}", e.getMessage());
            return false;
        }
    }

    // Response DTO
    private static class SearchResponse {
        public List<ChunkResult> chunks;
        public String contextText;
        public int estimatedTokens;
        public int totalChunksSearched;
        public long processingTimeMs;
    }

    private static class ChunkResult {
        public String id;
        public String text;
        public double similarity;
    }
}



