package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
                    .header("Authorization", "Bearer " + serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send command to Orchestrator", e);
        }
    }

    @Override
    public String sendIntent(String action, Map<String, Object> parameters, String language, String correlationId) {
        return sendIntent(action, parameters, language, correlationId, null);
    }
    
    @Override
    public String sendIntent(String action, Map<String, Object> parameters, String language, 
                             String correlationId, String originalText) {
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
            
            String response = restClientBuilder.build()
                    .post()
                    .uri(orchestratorUrl + "/api/v1/orchestrator/execute")
                    .header("Authorization", "Bearer " + serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            
            log.info("📥 Orchestrator response: '{}', correlationId={}", response, correlationId);
            return response;
            
        } catch (Exception e) {
            log.error("❌ Failed to send intent to Orchestrator: action={}, correlationId={}", 
                    action, correlationId, e);
            throw new RuntimeException("Failed to call orchestrator: " + e.getMessage(), e);
        }
    }
}
