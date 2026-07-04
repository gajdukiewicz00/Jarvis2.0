package org.jarvis.nlp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3 — calls {@code llm-service /api/v1/llm/intent} for fast intent
 * classification through the host router model.
 *
 * <p>This client never reaches the model daemon directly. Per SPEC-1 the only
 * permitted path is {@code nlp-service -> llm-service -> host-model-daemon}.
 * The deterministic rule-based path stays in place and is used as a fallback
 * whenever this client returns {@code Optional.empty()}.</p>
 */
@Slf4j
@Component
public class FastIntentClient {

    private final RestTemplate restTemplate;
    private final String url;
    private final boolean enabled;
    private final long timeoutMillis;

    public FastIntentClient(
            @Value("${jarvis.nlp.fast-intent.enabled:false}") boolean enabled,
            @Value("${jarvis.nlp.fast-intent.url:http://llm-service:8091/api/v1/llm/intent}") String url,
            @Value("${jarvis.nlp.fast-intent.timeout-ms:1500}") long timeoutMillis) {
        this.enabled = enabled;
        this.url = url;
        this.timeoutMillis = timeoutMillis;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        this.restTemplate = new RestTemplate(factory);
        log.info("FastIntentClient init: enabled={}, url={}, timeout={}ms",
                enabled, url, timeoutMillis);
    }

    /**
     * Returns the router-classified intent if the daemon answered with a
     * non-fallback result. Otherwise empty so the caller can fall back to
     * the deterministic rule engine.
     */
    public Optional<String> classify(String text, String language, List<String> candidates) {
        if (!enabled) {
            return Optional.empty();
        }
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        if (language != null && !language.isBlank()) {
            body.put("language", language);
        }
        if (candidates != null && !candidates.isEmpty()) {
            body.put("candidates", candidates);
        }
        String correlationId = UUID.randomUUID().toString();
        body.put("correlationId", correlationId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), Map.class);
            Map<?, ?> bodyMap = response.getBody();
            if (bodyMap == null) {
                return Optional.empty();
            }
            Object source = bodyMap.get("source");
            Object intent = bodyMap.get("intent");
            if (!"router".equals(source) || !(intent instanceof String s) || s.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(s);
        } catch (ResourceAccessException ex) {
            log.debug("[{}] llm-service unreachable, falling back to regex: {}",
                    correlationId, ex.getMessage());
            return Optional.empty();
        } catch (RestClientException ex) {
            log.debug("[{}] llm-service error, falling back to regex: {}",
                    correlationId, ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getUrl() {
        return url;
    }
}
