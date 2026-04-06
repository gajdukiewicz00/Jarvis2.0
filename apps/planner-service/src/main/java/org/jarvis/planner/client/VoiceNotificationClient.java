package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for pushing reminder speech to active user voice sessions.
 */
@Slf4j
@Component
public class VoiceNotificationClient {

    private final RestTemplate restTemplate;
    private final String voiceGatewayUrl;

    public VoiceNotificationClient(
            RestTemplate restTemplate,
            @Value("${services.voice-gateway.url:http://voice-gateway:8081}") String voiceGatewayUrl) {
        this.restTemplate = restTemplate;
        this.voiceGatewayUrl = voiceGatewayUrl;
    }

    public boolean sendNotification(String userId, String message, String languageCode) {
        Map<String, Object> request = Map.of(
                "userId", userId,
                "message", message,
                "languageCode", languageCode);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    voiceGatewayUrl + "/internal/voice/notify",
                    request,
                    Map.class);
            log.info("Planner voice notification routed: userId={}, voiceGatewayUrl={}, statusCode={}",
                    userId, voiceGatewayUrl, response.getStatusCode().value());
            return response.getStatusCode().is2xxSuccessful();
        } catch (HttpStatusCodeException e) {
            log.info("Planner voice notification routed: userId={}, voiceGatewayUrl={}, statusCode={}",
                    userId, voiceGatewayUrl, e.getStatusCode().value());
            return false;
        } catch (RestClientException e) {
            log.warn("Voice notification delivery failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
