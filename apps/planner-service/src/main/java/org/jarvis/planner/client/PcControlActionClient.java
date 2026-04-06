package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for user-scoped desktop actions routed through api-gateway.
 */
@Slf4j
@Component
public class PcControlActionClient {

    private final RestTemplate restTemplate;
    private final String apiGatewayUrl;

    public PcControlActionClient(
            RestTemplate restTemplate,
            @Value("${services.api-gateway.url:http://api-gateway:8080}") String apiGatewayUrl) {
        this.restTemplate = restTemplate;
        this.apiGatewayUrl = apiGatewayUrl;
    }

    public boolean sendAction(String userId, String action, Map<String, Object> params) {
        Map<String, Object> request = Map.of(
                "action", action,
                "userId", userId,
                "params", params);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    apiGatewayUrl + "/internal/pc-control/action",
                    request,
                    Map.class);
            log.info("PC action {} routed via {} for user {} with status {}",
                    action,
                    apiGatewayUrl,
                    userId,
                    response.getStatusCodeValue());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.warn("PC action {} via {} failed for user {}: {}",
                    action,
                    apiGatewayUrl,
                    userId,
                    e.getMessage());
            return false;
        }
    }
}
