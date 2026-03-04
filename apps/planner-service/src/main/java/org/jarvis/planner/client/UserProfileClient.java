package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Client for user-profile service
 */
@Slf4j
@Component
public class UserProfileClient {
    
    private final RestTemplate restTemplate;
    private final String userProfileUrl;
    
    public UserProfileClient(
            RestTemplate restTemplate,
            @Value("${services.user-profile.url}") String userProfileUrl
    ) {
        this.restTemplate = restTemplate;
        this.userProfileUrl = userProfileUrl;
    }
    
    public boolean isHealthy() {
        try {
            String url = userProfileUrl + "/actuator/health";
            restTemplate.getForEntity(url, String.class);
            return true;
        } catch (RestClientException e) {
            log.warn("user-profile health check failed: {}", e.getMessage());
            return false;
        }
    }
    
    // Placeholder methods
    public List<String> getUserGoals(String userId) {
        // TODO: Implement when user-profile goals API is ready
        List<String> goals = new ArrayList<>();
        goals.add("Изучить кибербезопасность");
        goals.add("Улучшить английский");
        return goals;
    }
}
