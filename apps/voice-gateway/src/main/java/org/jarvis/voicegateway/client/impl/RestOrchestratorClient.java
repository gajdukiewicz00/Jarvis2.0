package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtFilter;
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
        sendCommand(text, null);
    }

    @Override
    public void sendCommand(String text, String userId) {
        String targetUrl = orchestratorUrl + "/api/v1/orchestrator/execute";
        log.info("Sending text command to Orchestrator via {}: {}", targetUrl, text);
        try {
            RestClient.RequestBodySpec request = restClientBuilder.build()
                    .post()
                    .uri(targetUrl)
                    .header(ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                            serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON);

            if (userId != null && !userId.isBlank()) {
                request.header("X-User-Id", userId);
            }

            request.body(Map.of("text", text))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Voice gateway orchestrator routed: targetUrl={}, mode=text", targetUrl);
        } catch (HttpStatusCodeException e) {
            log.warn("Orchestrator returned error status={} body={}", e.getStatusCode(), e.getResponseBodyAsString(),
                    e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            log.warn("Orchestrator is unreachable: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Unexpected REST client error while calling orchestrator", e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Unexpected runtime error while calling orchestrator", e);
            throw e;
        }
    }

    @Override
    public String sendCommandWithResponse(String text) {
        return sendCommandWithResponse(text, null);
    }

    @Override
    public String sendCommandWithResponse(String text, String userId) {
        String targetUrl = orchestratorUrl + "/api/v1/orchestrator/execute";
        log.info("Sending text command with response to Orchestrator via {}: {}", targetUrl, text);
        try {
            RestClient.RequestBodySpec request = restClientBuilder.build()
                    .post()
                    .uri(targetUrl)
                    .header(ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                            serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON);

            if (userId != null && !userId.isBlank()) {
                request.header("X-User-Id", userId);
            }

            String response = request.body(Map.of("text", text))
                    .retrieve()
                    .body(String.class);
            log.info("Voice gateway orchestrator routed: targetUrl={}, mode=text-response", targetUrl);
            return response;
        } catch (HttpStatusCodeException e) {
            log.warn("Orchestrator returned error status={} body={}", e.getStatusCode(), e.getResponseBodyAsString(),
                    e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            log.warn("Orchestrator is unreachable: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Unexpected REST client error while calling orchestrator", e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        }
    }

    @Override
    public String sendIntent(String action, Map<String, Object> parameters, String language, String correlationId) {
        return sendIntentDetailed(action, parameters, language, correlationId, null, null).responseText();
    }

    @Override
    public String sendIntent(String action, Map<String, Object> parameters, String language,
            String correlationId, String originalText) {
        return sendIntentDetailed(action, parameters, language, correlationId, originalText, null).responseText();
    }

    @Override
    public IntentExecutionResult sendIntentDetailed(String action, Map<String, Object> parameters, String language,
            String correlationId, String originalText, String userId) {
        String targetUrl = orchestratorUrl + "/api/v1/orchestrator/execute-detailed";
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
                    .uri(targetUrl)
                    .header(ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                            serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .header("X-Model-Profile", "voice-fast")
                    .header("X-Correlation-ID", correlationId != null ? correlationId : "")
                    .contentType(MediaType.APPLICATION_JSON);

            if (userId != null && !userId.isBlank()) {
                request.header("X-User-Id", userId);
            }

            IntentExecutionResult response = request
                    .body(requestBody)
                    .retrieve()
                    .body(IntentExecutionResult.class);

            log.info(
                    "📥 Orchestrator response: response='{}', executorFound={}, executionAttempted={}, executionSucceeded={}, failureReason={}, correlationId={}",
                    response != null ? response.responseText() : null,
                    response != null && response.executorFound(),
                    response != null && response.executionAttempted(),
                    response != null && response.executionSucceeded(),
                    response != null ? response.failureReason() : null,
                    correlationId);
            log.info("Voice gateway orchestrator routed: targetUrl={}, correlationId={}, mode=intent",
                    targetUrl, correlationId);
            return response != null
                    ? response
                    : new IntentExecutionResult(
                            "",
                            false,
                            false,
                            false,
                            true,
                            "Orchestrator returned an empty response");

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
