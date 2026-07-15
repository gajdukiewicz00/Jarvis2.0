package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.VisionActionGateway;
import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher.DispatchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts "analyze the screen" requests to the orchestrator's vision passthrough and turns the
 * JSON contract into a {@link DispatchResult} the voice handler can speak.
 *
 * <p>Contract (implemented by the orchestrator):
 * <pre>
 *   POST {jarvis.vision.dispatch-url}/internal/vision/ask-screen
 *   request : {"question": String, "userId": String, "correlationId": String}
 *   success : {"status":"analyzed","answer":"&lt;text&gt;"}
 *   failure : {"status":"vision_failed","answer":"","failureReason":"VISION_HTTP_x|VISION_UNAVAILABLE|VISION_EMPTY"}
 * </pre>
 * Auth uses the same {@code X-Service-Token} SVC JWT as the PC-control gateway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestVisionActionGateway implements VisionActionGateway {

    /** Spoken when the screen was analyzed but no readable text/answer came back. */
    private static final String EMPTY_SCREEN_TEXT = "Готово, сэр. На экране нет распознаваемого текста.";

    private static final String ROUTED_ACTION = "VISION_SCREEN_ANALYZE";

    @Value("${jarvis.vision.dispatch-url:${jarvis.orchestrator.url:http://orchestrator:8083}}")
    private String visionDispatchUrl;

    @Value("${spring.application.name:voice-gateway}")
    private String serviceName;

    private final RestClient.Builder restClientBuilder;
    private final ServiceJwtProvider serviceJwtProvider;

    @Override
    public DispatchResult askScreen(String userId, String question, String correlationId) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("question", question == null ? "" : question);
        requestBody.put("userId", userId == null ? "" : userId);
        requestBody.put("correlationId", correlationId == null ? "" : correlationId);

        String endpoint = visionDispatchUrl + "/internal/vision/ask-screen";
        log.info("👁️ Vision ask-screen dispatch: endpoint={}, userId={}, correlationId={}, question='{}'",
                endpoint, maskUserId(userId), correlationId, question);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClientBuilder.build()
                    .post()
                    .uri(endpoint)
                    .header("X-Service-Token", serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                log.warn("👁️ Vision ask-screen returned empty body: endpoint={}, correlationId={}", endpoint, correlationId);
                return failure("VISION_UNAVAILABLE");
            }

            String status = String.valueOf(response.getOrDefault("status", ""));
            String answer = readString(response, "answer");
            if ("analyzed".equalsIgnoreCase(status)) {
                String override = (answer == null || answer.isBlank()) ? EMPTY_SCREEN_TEXT : answer;
                log.info("👁️ Vision ask-screen analyzed: endpoint={}, correlationId={}, answerLength={}",
                        endpoint, correlationId, answer == null ? 0 : answer.length());
                return success(override);
            }

            String reason = readString(response, "failureReason");
            if (reason == null || reason.isBlank()) {
                reason = "VISION_UNAVAILABLE";
            }
            log.warn("👁️ Vision ask-screen failed: endpoint={}, correlationId={}, status={}, failureReason={}",
                    endpoint, correlationId, status, reason);
            return failure(reason);
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            String coded = "VISION_HTTP_" + status.value();
            log.warn("👁️ Vision ask-screen HTTP error: endpoint={}, correlationId={}, statusCode={}, body={}",
                    endpoint, correlationId, status.value(), e.getResponseBodyAsString());
            return failure(coded);
        } catch (ResourceAccessException e) {
            log.warn("👁️ Vision ask-screen endpoint unreachable: endpoint={}, correlationId={}, error={}",
                    endpoint, correlationId, e.getMessage());
            return failure("VISION_UNAVAILABLE");
        } catch (RuntimeException e) {
            log.warn("👁️ Vision ask-screen dispatch failed: endpoint={}, correlationId={}, error={}",
                    endpoint, correlationId, e.getMessage());
            return failure("VISION_UNAVAILABLE");
        }
    }

    private DispatchResult success(String answer) {
        return new DispatchResult(true, true, true, true, false, null, ROUTED_ACTION, Map.of(), answer);
    }

    private DispatchResult failure(String failureReason) {
        return new DispatchResult(true, true, true, false, true, failureReason, ROUTED_ACTION, Map.of(), null);
    }

    private String readString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private String maskUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "<none>";
        }
        if (userId.length() <= 2) {
            return "***";
        }
        return userId.charAt(0) + "***" + userId.charAt(userId.length() - 1);
    }
}
