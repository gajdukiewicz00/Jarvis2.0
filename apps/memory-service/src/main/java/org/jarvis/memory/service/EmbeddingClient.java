package org.jarvis.memory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client for embedding-service API
 */
@Slf4j
@Service
public class EmbeddingClient {

    private final WebClient webClient;
    private final Duration timeout;

    public EmbeddingClient(
            @Value("${memory.embedding.service-url}") String serviceUrl,
            @Value("${memory.embedding.timeout-ms:30000}") long timeoutMs) {
        this.webClient = WebClient.builder()
                .baseUrl(serviceUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.timeout = Duration.ofMillis(timeoutMs);
        
        log.info("EmbeddingClient initialized: url={}, timeout={}ms", serviceUrl, timeoutMs);
    }

    /**
     * Get embedding for a single text
     */
    public List<Float> embed(String text, String correlationId) {
        log.debug("[{}] Embedding text: {}...", correlationId, text.substring(0, Math.min(50, text.length())));
        
        try {
            EmbedSingleResponse response = webClient.post()
                    .uri("/embed/single")
                    .header("X-Correlation-ID", correlationId)
                    .bodyValue(Map.of("text", text, "input_type", "query"))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.createException())
                    .bodyToMono(EmbedSingleResponse.class)
                    .timeout(timeout)
                    .block();
            
            if (response == null || response.embedding == null) {
                throw new RuntimeException("Empty response from embedding service");
            }
            
            log.debug("[{}] Embedding complete: dim={}", correlationId, response.embedding.size());
            return response.embedding;
            
        } catch (WebClientResponseException e) {
            log.error("[{}] Embedding service returned {}: {}", correlationId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to get embedding: " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            log.error("[{}] Embedding failed: {}", correlationId, e.getMessage());
            throw new RuntimeException("Failed to get embedding: " + e.getMessage(), e);
        }
    }

    /**
     * Get embeddings for multiple texts (batched)
     */
    public List<List<Float>> embedBatch(List<String> texts, String correlationId) {
        log.debug("[{}] Embedding batch: {} texts", correlationId, texts.size());
        
        try {
            EmbedBatchResponse response = webClient.post()
                    .uri("/embed")
                    .header("X-Correlation-ID", correlationId)
                    .bodyValue(Map.of("texts", texts, "input_type", "passage"))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.createException())
                    .bodyToMono(EmbedBatchResponse.class)
                    .timeout(timeout)
                    .block();
            
            if (response == null || response.embeddings == null) {
                throw new RuntimeException("Empty response from embedding service");
            }
            
            log.debug("[{}] Batch embedding complete: {} embeddings", correlationId, response.embeddings.size());
            return response.embeddings;
            
        } catch (WebClientResponseException e) {
            log.error("[{}] Batch embedding service returned {}: {}", correlationId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to get batch embeddings: " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            log.error("[{}] Batch embedding failed: {}", correlationId, e.getMessage());
            throw new RuntimeException("Failed to get batch embeddings: " + e.getMessage(), e);
        }
    }

    /**
     * Check if embedding service is healthy
     */
    public boolean isHealthy() {
        return getHealth().healthy();
    }

    public EmbeddingServiceHealth getHealth() {
        try {
            HealthResponse response = webClient.get()
                    .uri("/health")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.createException())
                    .bodyToMono(HealthResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (response == null) {
                return new EmbeddingServiceHealth(false, "unknown", null, null, "empty health response");
            }

            return new EmbeddingServiceHealth(
                    "healthy".equalsIgnoreCase(response.status),
                    response.status,
                    response.model_name,
                    response.embedding_dim,
                    null);

        } catch (WebClientResponseException e) {
            log.warn("Embedding service health check returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return new EmbeddingServiceHealth(false, "error", null, null, "http " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            log.warn("Embedding service health check failed: {}", e.getMessage());
            return new EmbeddingServiceHealth(false, "error", null, null, e.getMessage());
        }
    }

    // Response DTOs
    private static class EmbedSingleResponse {
        public List<Float> embedding;
        public String model;
        public int dimension;
        public int processing_time_ms;
    }

    private static class EmbedBatchResponse {
        public List<List<Float>> embeddings;
        public String model;
        public int dimension;
        public int count;
        public int processing_time_ms;
    }

    private static class HealthResponse {
        public String status;
        public String model_name;
        public Integer embedding_dim;
    }

    public record EmbeddingServiceHealth(
            boolean healthy,
            String status,
            String modelName,
            Integer dimension,
            String error) {
    }
}

