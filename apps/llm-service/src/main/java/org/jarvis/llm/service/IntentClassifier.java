package org.jarvis.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.config.HostModelDaemonProperties;
import org.jarvis.llm.dto.IntentRequest;
import org.jarvis.llm.dto.IntentResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 — classify intent through the host router model.
 *
 * <p>Calls {@code router} channel of {@code host-model-daemon}. If the daemon
 * is unreachable or returns an error, a {@code fallback} response is produced
 * so callers (e.g. nlp-service) can fall back to deterministic regex.</p>
 *
 * <p>This service is the ONLY allowed path from the cluster to the router
 * model — no other service may reach llama.cpp directly (SPEC-1).</p>
 */
@Slf4j
@Service
public class IntentClassifier {

    private final RestTemplate healthRestTemplate;
    private final HostModelDaemonProperties daemonProperties;

    public IntentClassifier(
            @Qualifier("llmHealthRestTemplate") RestTemplate healthRestTemplate,
            HostModelDaemonProperties daemonProperties) {
        this.healthRestTemplate = healthRestTemplate;
        this.daemonProperties = daemonProperties;
    }

    public IntentResponse classify(IntentRequest request) {
        String correlationId = request.getCorrelationId();
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        Instant started = Instant.now();

        if (!daemonProperties.isEnabled()) {
            return fallback(correlationId, "host-daemon disabled");
        }

        String url = daemonProperties.urlFor(daemonProperties.getRouter()) + "/v1/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("prompt", buildPrompt(request));
        body.put("temperature", 0.0);
        body.put("max_tokens", 32);
        body.put("stop", new String[]{"\n", "\""});

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);

        try {
            ResponseEntity<Map> response = healthRestTemplate.exchange(
                    url,
                    org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            String intent = parseIntent(response.getBody());
            double confidence = intent.isBlank() ? 0.0 : 0.7;
            log.info("[{}] router classified '{}' in {}ms",
                    correlationId, intent, java.time.Duration.between(started, Instant.now()).toMillis());
            return IntentResponse.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .source("router")
                    .correlationId(correlationId)
                    .build();
        } catch (ResourceAccessException ex) {
            log.warn("[{}] router unreachable: {}", correlationId, ex.getMessage());
            return fallback(correlationId, "daemon-unreachable: " + ex.getMessage());
        } catch (RestClientException ex) {
            log.warn("[{}] router error: {}", correlationId, ex.getMessage());
            return fallback(correlationId, "daemon-error: " + ex.getMessage());
        }
    }

    private IntentResponse fallback(String correlationId, String reason) {
        return IntentResponse.builder()
                .intent("")
                .confidence(0.0)
                .source("fallback")
                .reason(reason)
                .correlationId(correlationId)
                .build();
    }

    private String buildPrompt(IntentRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify the user utterance into a single intent label. ");
        sb.append("Reply with the label only, no prose.\n");
        if (request.getCandidates() != null && !request.getCandidates().isEmpty()) {
            sb.append("Candidates: ").append(String.join(", ", request.getCandidates())).append("\n");
        }
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            sb.append("Language: ").append(request.getLanguage()).append("\n");
        }
        sb.append("Utterance: \"").append(request.getText().replace("\"", "\\\"")).append("\"\n");
        sb.append("Intent:");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String parseIntent(Map<String, Object> body) {
        if (body == null) {
            return "";
        }
        Object choices = body.get("choices");
        if (choices instanceof Iterable<?> it) {
            for (Object choice : it) {
                if (choice instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text instanceof String s) {
                        return s.strip();
                    }
                }
            }
        }
        Object text = body.get("text");
        if (text instanceof String s) {
            return s.strip();
        }
        return "";
    }
}
