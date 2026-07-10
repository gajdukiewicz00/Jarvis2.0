package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Triggers an on-screen analysis through the API Gateway's vision-security proxy
 * ({@code /api/v1/vision-security/cv/ask-screen} → vision-security-service:8094, a
 * workstation host-bridge process). voice-gateway is NetworkPolicy-blocked from reaching
 * api-gateway directly, so it routes the "analyze my screen" request through the orchestrator,
 * which IS allowed to call the gateway. {@link ServiceAuthFeignConfig} attaches the
 * SVC_INTERNAL service token; X-User-Id is forwarded so vision-security scopes the capture to
 * the right user. The response is the raw {@code AskScreenResult} JSON (its {@code answer}
 * field carries the human-readable analysis).
 */
@FeignClient(
        name = "api-gateway-vision",
        url = "${api-gateway.url:${API_GATEWAY_URL:http://api-gateway:8080}}",
        configuration = ServiceAuthFeignConfig.class)
public interface ApiGatewayVisionClient {

    /**
     * Ask the local CV stack a question about the current screen, capturing a fresh
     * screenshot server-side.
     */
    @PostMapping("/api/v1/vision-security/cv/ask-screen")
    Map<String, Object> askScreen(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody AskScreenRequest request);

    /**
     * {@code captureFreshScreenshot} is always sent {@code true}: the passthrough analyzes the
     * live screen rather than a stored image path.
     */
    record AskScreenRequest(String question, boolean captureFreshScreenshot) {
    }
}
