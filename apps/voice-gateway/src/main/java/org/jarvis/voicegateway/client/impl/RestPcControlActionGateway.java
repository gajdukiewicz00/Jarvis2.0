package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestPcControlActionGateway implements PcControlActionGateway {

    @Value("${jarvis.api-gateway.url:${API_GATEWAY_URL:http://api-gateway:8080}}")
    private String apiGatewayUrl;

    @Value("${spring.application.name:voice-gateway}")
    private String serviceName;

    private final RestClient.Builder restClientBuilder;
    private final ServiceJwtProvider serviceJwtProvider;

    @Override
    public void dispatch(String action, Map<String, Object> params, String userId) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("action", action);
        requestBody.put("params", params == null ? Map.of() : params);
        if (userId != null && !userId.isBlank()) {
            requestBody.put("userId", userId);
        }

        log.info("📤 Dispatching direct PC action via API Gateway: action={}, userId={}, apiGatewayUrl={}, params={}",
                action, userId, apiGatewayUrl, params);

        try {
            restClientBuilder.build()
                    .post()
                    .uri(apiGatewayUrl + "/internal/pc-control/action")
                    .header("X-Service-Token", serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();

            log.info("✅ Voice gateway PC action routed: action={}, userId={}, apiGatewayUrl={}",
                    action, userId, apiGatewayUrl);
        } catch (RuntimeException e) {
            log.warn("⚠️ Failed to dispatch direct PC action via API Gateway: action={}, userId={}, apiGatewayUrl={}, error={}",
                    action, userId, apiGatewayUrl, e.getMessage());
            throw e;
        }
    }
}
