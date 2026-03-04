package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Client for life-tracker service
 */
@Slf4j
@Component
public class LifeTrackerClient {
    
    private final RestTemplate restTemplate;
    private final String lifeTrackerUrl;
    
    public LifeTrackerClient(
            RestTemplate restTemplate,
            @Value("${services.life-tracker.url}") String lifeTrackerUrl
    ) {
        this.restTemplate = restTemplate;
        this.lifeTrackerUrl = lifeTrackerUrl;
    }
    
    // TODO: Implement when life-tracker DTOs are available
    public boolean isHealthy() {
        try {
            String url = lifeTrackerUrl + "/actuator/health";
            restTemplate.getForEntity(url, String.class);
            return true;
        } catch (RestClientException e) {
            log.warn("life-tracker health check failed: {}", e.getMessage());
            return false;
        }
    }
}
