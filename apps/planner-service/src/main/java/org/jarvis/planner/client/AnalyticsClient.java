package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
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
        } catch (RestClientException e) {
            log.warn("analytics-service health check failed: {}", e.getMessage());
            return false;
        }
    }
    
    public Double getAverageSleepHours(String userId) {
        try {
            ResponseEntity<SleepSummaryResponse> response = restTemplate.exchange(
                    analyticsUrl + "/api/v1/analytics/habits/sleep-average?days=14",
                    HttpMethod.GET,
                    requestEntity(userId),
                    SleepSummaryResponse.class);
            return response.getBody() != null ? response.getBody().averageHours() : null;
        } catch (RestClientException e) {
            log.warn("sleep summary fetch failed for {}: {}", userId, e.getMessage());
            return null;
        }
    }
    
    public Integer getWeeklyOvertimeHours(String userId) {
        try {
            ResponseEntity<OvertimeSummaryResponse> response = restTemplate.exchange(
                    analyticsUrl + "/api/v1/analytics/habits/weekly-overtime?days=7&baselineHours=40",
                    HttpMethod.GET,
                    requestEntity(userId),
                    OvertimeSummaryResponse.class);
            return response.getBody() != null ? response.getBody().overtimeHours() : null;
        } catch (RestClientException e) {
            log.warn("overtime summary fetch failed for {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private HttpEntity<Void> requestEntity(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        return new HttpEntity<>(headers);
    }

    private record SleepSummaryResponse(Double averageHours) {
    }

    private record OvertimeSummaryResponse(Integer overtimeHours) {
    }
}
