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
    public DispatchResult dispatch(String action, Map<String, Object> params, String userId, String correlationId) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("action", action);
        requestBody.put("params", params == null ? Map.of() : params);
        if (userId != null && !userId.isBlank()) {
            requestBody.put("userId", userId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            requestBody.put("correlationId", correlationId);
        }

        log.info(
                "📤 Dispatching direct PC action via API Gateway: action={}, userId={}, correlationId={}, apiGatewayUrl={}, params={}",
                action,
                userId,
                correlationId,
                apiGatewayUrl,
                params);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClientBuilder.build()
                    .post()
                    .uri(apiGatewayUrl + "/internal/pc-control/action")
                    .header("X-Service-Token", serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            DispatchResult dispatchResult = response != null
                    ? new DispatchResult(
                            String.valueOf(response.getOrDefault("status", "")),
                            readBoolean(response, "executorFound"),
                            readBoolean(response, "executionAttempted"),
                            readBoolean(response, "executionSucceeded"),
                            readBoolean(response, "executionFailed"),
                            readString(response, "failureReason", "message"),
                            Map.copyOf(response))
                    : new DispatchResult(
                            "",
                            false,
                            false,
                            false,
                            true,
                            "API Gateway returned an empty response",
                            Map.of());

            log.info(
                    "📥 Voice gateway PC action result: action={}, userId={}, correlationId={}, status={}, executionSucceeded={}, failureReason={}, apiGatewayUrl={}",
                    action,
                    userId,
                    correlationId,
                    dispatchResult.status(),
                    dispatchResult.executionSucceeded(),
                    dispatchResult.failureReason(),
                    apiGatewayUrl);
            return dispatchResult;
        } catch (RuntimeException e) {
            log.warn(
                    "⚠️ Failed to dispatch direct PC action via API Gateway: action={}, userId={}, correlationId={}, apiGatewayUrl={}, error={}",
                    action,
                    userId,
                    correlationId,
                    apiGatewayUrl,
                    e.getMessage());
            throw e;
        }
    }

    private boolean readBoolean(Map<String, Object> payload, String key) {
        if (payload == null) {
            return false;
        }
        Object value = payload.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String readString(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
