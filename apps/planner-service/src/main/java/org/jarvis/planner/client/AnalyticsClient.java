package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for analytics-service
 */
@Slf4j
@Component
public class AnalyticsClient {
    
    private final RestTemplate restTemplate;
    private final String analyticsUrl;
    
    public AnalyticsClient(
            RestTemplate restTemplate,
            @Value("${services.analytics.url}") String analyticsUrl
    ) {
        this.restTemplate = restTemplate;
        this.analyticsUrl = analyticsUrl;
    }
    
    public boolean isHealthy() {
        try {
            String url = analyticsUrl + "/actuator/health";
            restTemplate.getForEntity(url, String.class);
            return true;
        } catch (Exception e) {
            log.warn("analytics-service health check failed: {}", e.getMessage());
            return false;
        }
    }
    
    // Placeholder methods for analytics data
    public Double getAverageSleepHours(String userId) {
        // TODO: Implement when analytics DTOs are ready
        return 7.5; // Mock value
    }
    
    public Integer getWeeklyOvertimeHours(String userId) {
        // TODO: Implement when analytics DTOs are ready
        return 5; // Mock value
    }
}
