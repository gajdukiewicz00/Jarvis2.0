package org.jarvis.memory.obsidian;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 9 — calls the Python embedding-service to compute a 384-dim
 * sentence vector for a memory note.
 *
 * <p>Resilient: any failure (timeout, 5xx, malformed payload) returns
 * {@code null}. The note still gets persisted + mirrored to Obsidian;
 * a Phase 12 re-index job can backfill missing embeddings.</p>
 */
@Slf4j
@Component
public class MemoryEmbeddingClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String embedUrl;
    private final boolean enabled;

    public MemoryEmbeddingClient(
            ObjectMapper mapper,
            @Value("${jarvis.memory.embedding.url:http://embedding-service:5001/embed}") String embedUrl,
            @Value("${jarvis.memory.embedding.enabled:true}") boolean enabled,
            @Value("${jarvis.memory.embedding.timeout-ms:5000}") long timeoutMs) {
        this.mapper = mapper;
        this.embedUrl = embedUrl;
        this.enabled = enabled;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 2_000)));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        log.info("MemoryEmbeddingClient init: url={} enabled={} timeout={}ms",
                embedUrl, enabled, timeoutMs);
    }

    public float[] embed(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        // embedding-service /embed expects a batch shape: {"texts": [...]} (sending the
        // singular "text" returns 422 and silently skips the embedding).
        body.put("texts", java.util.List.of(text));
        body.put("input_type", "passage");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    embedUrl, new HttpEntity<>(body, headers), Map.class);
            return parseEmbedding(response.getBody());
        } catch (ResourceAccessException ex) {
            log.warn("embedding-service unreachable, embedding skipped: {}", ex.getMessage());
            return null;
        } catch (RestClientException ex) {
            log.warn("embedding-service error, embedding skipped: {}", ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private float[] parseEmbedding(Map<String, Object> body) {
        if (body == null) return null;
        Object raw = body.get("embeddings");           // batch response: [[...]]
        if (raw instanceof List<?> outer && !outer.isEmpty() && outer.get(0) instanceof List<?> first) {
            raw = first;
        } else {                                        // single-format fallbacks
            raw = body.get("embedding");
            if (raw == null) raw = body.get("vector");
            if (raw == null) raw = body.get("data");
        }
        if (!(raw instanceof List<?> list)) return null;
        float[] out = new float[list.size()];
        int i = 0;
        for (Object element : list) {
            if (element instanceof Number n) {
                out[i++] = n.floatValue();
            } else {
                return null;
            }
        }
        return out;
    }
}
