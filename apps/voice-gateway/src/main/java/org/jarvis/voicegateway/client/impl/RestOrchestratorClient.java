package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestOrchestratorClient implements OrchestratorClient {

    @Value("${jarvis.orchestrator.url:http://orchestrator:8083}")
    private String orchestratorUrl;

    @Value("${spring.application.name:voice-gateway}")
    private String serviceName;

    private final RestClient.Builder restClientBuilder;
    private final ServiceJwtProvider serviceJwtProvider;

    @Override
    public void sendCommand(String text) {
        log.info("Sending text command to Orchestrator: {}", text);
        try {
            restClientBuilder.build()
                    .post()
                    .uri(orchestratorUrl + "/api/v1/orchestrator/execute")
                    .header("Authorization",
                            "Bearer " + serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            log.warn("Orchestrator returned error status={} body={}", e.getStatusCode(), e.getResponseBodyAsString(),
                    e);
        } catch (ResourceAccessException e) {
            log.warn("Orchestrator is unreachable: {}", e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Unexpected REST client error while calling orchestrator", e);
        } catch (RuntimeException e) {
            log.error("Unexpected runtime error while calling orchestrator", e);
        }
    }

    @Override
    public String sendIntent(String action, Map<String, Object> parameters, String language, String correlationId) {
        return sendIntent(action, parameters, language, correlationId, null, null);
    }

    @Override
    public String sendIntent(String action, Map<String, Object> parameters, String language,
            String correlationId, String originalText) {
        return sendIntent(action, parameters, language, correlationId, originalText, null);
    }

    @Override
    public String sendIntent(String action, Map<String, Object> parameters, String language,
            String correlationId, String originalText, String userId) {
        log.info("📤 Sending intent to Orchestrator: action={}, params={}, lang={}, correlationId={}, hasText={}",
                action, parameters, language, correlationId, originalText != null);

        try {
            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("intent", action.toLowerCase()); // Normalize to lowercase for orchestrator
            requestBody.put("language", language);
            requestBody.put("correlationId", correlationId);

            // Add original text for LLM fallback (if available)
            if (originalText != null && !originalText.isBlank()) {
                requestBody.put("originalText", originalText);
            }

            // Merge parameters
            Map<String, String> stringParams = new HashMap<>();
            if (parameters != null) {
                parameters.forEach((k, v) -> stringParams.put(k, String.valueOf(v)));
            }
            requestBody.put("parameters", stringParams);

            log.debug("Request body: {}", requestBody);

            RestClient.RequestBodySpec request = restClientBuilder.build()
                    .post()
                    .uri(orchestratorUrl + "/api/v1/orchestrator/execute")
                    .header("Authorization",
                            "Bearer " + serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON);

            if (userId != null && !userId.isBlank()) {
                request.header("X-User-Id", userId);
            }

            String response = request
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            log.info("📥 Orchestrator response: '{}', correlationId={}", response, correlationId);
            return response;

        } catch (HttpStatusCodeException e) {
            log.warn("❌ Orchestrator returned error status={} body={}", e.getStatusCode(), e.getResponseBodyAsString(),
                    e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            log.warn("❌ Orchestrator is unreachable: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("❌ Unexpected REST client error while calling orchestrator: action={}, correlationId={}", action,
                    correlationId, e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("❌ Unexpected runtime error while calling orchestrator: action={}, correlationId={}", action,
                    correlationId, e);
            throw e;
        }
    }
}
