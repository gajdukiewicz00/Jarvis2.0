package org.jarvis.llm.client;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.GatewayAuthFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.llm.dto.UserPreferencesDto;
import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Client for user-profile service.
 * 
 * This is an OPTIONAL dependency - if unavailable or disabled,
 * default preferences are returned and dialog continues normally.
 */
@Slf4j
@Component
public class UserProfileClient {

    private final RestTemplate restTemplate;
    private final String userProfileUrl;
    private final boolean enabled;
    private final ServiceJwtProvider serviceJwtProvider;
    private final String serviceName;

    public UserProfileClient(
            @Qualifier("userProfileRestTemplate") RestTemplate restTemplate,
            @Value("${services.user-profile.base-url:http://localhost:8089}") String userProfileUrl,
            @Value("${services.user-profile.enabled:true}") boolean enabled,
            ServiceJwtProvider serviceJwtProvider,
            @Value("${spring.application.name:llm-service}") String serviceName
    ) {
        this.restTemplate = restTemplate;
        this.userProfileUrl = userProfileUrl;
        this.enabled = enabled;
        this.serviceJwtProvider = serviceJwtProvider;
        this.serviceName = serviceName;
        
        if (!enabled) {
            log.info("📋 UserProfileClient: DISABLED - will use default preferences");
        } else {
            log.info("📋 UserProfileClient: enabled, url={}", userProfileUrl);
        }
    }

    /**
     * Get user preferences by userId.
     * Returns default preferences if:
     * - Service is disabled
     * - Service is unavailable
     * - Any error occurs
     * 
     * This method NEVER throws exceptions - it's an optional dependency.
     * 
     * @param userId User ID
     * @param correlationId Correlation ID for logging
     * @return User preferences (real or defaults)
     */
    public UserPreferencesDto getPreferences(String userId, String correlationId) {
        if (!enabled) {
            log.debug("[{}] user-profile disabled, using defaults for user: {}", 
                    correlationId, userId);
            return getDefaultPreferences(userId);
        }

        long startTime = System.currentTimeMillis();
        try {
            String url = userProfileUrl + "/api/v1/profile/preferences/" + userId;
            log.debug("[{}] Fetching preferences from: {}", correlationId, url);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
            headers.set(GatewayAuthFilter.USER_ID_HEADER, userId);
            ResponseEntity<UserPreferencesDto> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    UserPreferencesDto.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[{}] ✓ Preferences fetched for user {} in {}ms", 
                        correlationId, userId, elapsed);
                return response.getBody();
            }

        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("[{}] ⚠ user-profile unavailable ({}ms): {} - using defaults for user: {}", 
                    correlationId, elapsed, e.getMessage(), userId);
        }

        // Return default preferences - this is expected behavior when service is down
        return getDefaultPreferences(userId);
    }

    /**
     * Get user preferences (backward compatible overload).
     */
    public UserPreferencesDto getPreferences(String userId) {
        return getPreferences(userId, "no-correlation-id");
    }

    /**
     * Check if user-profile service is configured and enabled.
     * Note: This does NOT check if the service is actually available.
     */
    public boolean isEnabled() {
        return enabled;
    }

    private UserPreferencesDto getDefaultPreferences(String userId) {
        UserPreferencesDto dto = new UserPreferencesDto();
        dto.setUserId(userId);
        dto.setFullName("User");
        dto.setTimezone("Europe/Warsaw");
        dto.setLanguage("ru");
        dto.setOccupation("User");
        dto.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        dto.setAllowAutoAdaptation(true);
        dto.setAllowSarcasm(false);
        dto.setTtsVoiceId("jarvis_male_en");
        dto.setTtsEmotionDefault(Emotion.NEUTRAL);
        return dto;
    }
}
