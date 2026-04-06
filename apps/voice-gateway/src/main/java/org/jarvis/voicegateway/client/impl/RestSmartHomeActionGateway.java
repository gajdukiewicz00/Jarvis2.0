package org.jarvis.voicegateway.client.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
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
public class RestSmartHomeActionGateway implements SmartHomeActionGateway {

    @Value("${jarvis.smart-home.url:http://smart-home-service:8086}")
    private String smartHomeUrl;

    @Value("${spring.application.name:voice-gateway}")
    private String serviceName;

    private final RestClient.Builder restClientBuilder;
    private final ServiceJwtProvider serviceJwtProvider;
    private final ObjectMapper objectMapper;

    @Override
    public void execute(String userId, String deviceId, String action, Object payload) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("action", action);
        requestBody.put("payload", serializePayload(payload));

        log.info("🏠 Dispatching smart-home action: userId={}, deviceId={}, action={}",
                userId, deviceId, action);

        restClientBuilder.build()
                .post()
                .uri(smartHomeUrl + "/api/v1/smarthome/devices/{deviceId}/action", deviceId)
                .header("X-Service-Token", serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toBodilessEntity();

        log.info("Voice-gateway smart-home route: userId={}, deviceId={}, action={}, smartHomeUrl={}",
                userId, deviceId, action, smartHomeUrl);
    }

    private String serializePayload(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String stringPayload) {
            return stringPayload;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize smart-home payload", e);
        }
    }
}
