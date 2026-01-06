package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for llm-service
 */
@Slf4j
@Component
public class LlmServiceClient {
    
    private final RestTemplate restTemplate;
    private final String llmServiceUrl;
    
    public LlmServiceClient(
            RestTemplate restTemplate,
            @Value("${services.llm-service.url}") String llmServiceUrl
    ) {
        this.restTemplate = restTemplate;
        this.llmServiceUrl = llmServiceUrl;
    }
    
    public boolean isHealthy() {
        try {
            String url = llmServiceUrl + "/api/v1/llm/health";
            restTemplate.getForEntity(url, String.class);
            return true;
        } catch (Exception e) {
            log.warn("llm-service health check failed: {}", e.getMessage());
            return false;
        }
    }
    
    // Placeholder for LLM enhancement
    public String enhancePlanDescription(String userId, String planText) {
        // TODO: Implement LLM-based plan enhancement
        return planText; // For now, return as-is
    }
}
