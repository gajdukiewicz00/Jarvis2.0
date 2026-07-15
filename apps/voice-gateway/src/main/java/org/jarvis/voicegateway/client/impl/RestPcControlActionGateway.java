package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.PcControlActionGateway;
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
 * Dispatches PC-control voice actions to the SAME working executor path the desktop uses,
 * via the orchestrator.
 *
 * <p>voice-gateway is NetworkPolicy-blocked from calling api-gateway:8080 directly (only the
 * ingress controller and the orchestrator are allowed ingress there), which is why the old
 * direct path failed with "Connection refused". The orchestrator IS allowed to reach
 * api-gateway, and voice-gateway IS allowed to reach the orchestrator, so we post to the
 * orchestrator's {@code /internal/pc-control/action} passthrough, which forwards to the exact
 * api-gateway → desktop WebSocket dispatch used by the intent handlers. This reuses the
 * working PC-control client instead of a separate, blocked path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestPcControlActionGateway implements PcControlActionGateway {

    @Value("${jarvis.pc-control.dispatch-url:${jarvis.orchestrator.url:http://orchestrator:8083}}")
    private String pcDispatchUrl;

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

        String endpoint = pcDispatchUrl + "/internal/pc-control/action";
        // Structured dispatch log (requirement 7). userId is masked; recognizedText/matchedRuleId
        // /final userMessage are logged by the WS handler which owns that context.
        log.info(
                "📤 PC action dispatch: actionType={}, targetService=orchestrator, endpoint={}, userId={}, correlationId={}, payload={}",
                action, endpoint, maskUserId(userId), correlationId, params);

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
                            "EMPTY_RESPONSE: orchestrator returned an empty response",
                            Map.of());

            log.info(
                    "📥 PC action result: actionType={}, endpoint={}, userId={}, correlationId={}, statusCode=200, status={}, executionSucceeded={}, failureReason={}, response={}",
                    action, endpoint, maskUserId(userId), correlationId,
                    dispatchResult.status(), dispatchResult.executionSucceeded(),
                    dispatchResult.failureReason(), response);
            return dispatchResult;
        } catch (HttpStatusCodeException e) {
            // Preserve the HTTP status so the WS handler can speak a specific reason (auth, etc.)
            HttpStatusCode status = e.getStatusCode();
            String coded = codedHttpReason(status);
            log.warn(
                    "⚠️ PC action HTTP error: actionType={}, endpoint={}, userId={}, correlationId={}, statusCode={}, body={}",
                    action, endpoint, maskUserId(userId), correlationId, status.value(), e.getResponseBodyAsString());
            return new DispatchResult(
                    "http_error", false, false, false, true, coded, Map.of("statusCode", status.value()));
        } catch (ResourceAccessException e) {
            // Connection refused / timeout / DNS — the dispatch endpoint is unreachable.
            log.warn(
                    "⚠️ PC action endpoint unreachable: actionType={}, endpoint={}, userId={}, correlationId={}, error={}",
                    action, endpoint, maskUserId(userId), correlationId, e.getMessage());
            return new DispatchResult(
                    "unreachable", false, false, false, true,
                    "ENDPOINT_UNREACHABLE: " + e.getMessage(), Map.of());
        } catch (RuntimeException e) {
            log.warn(
                    "⚠️ PC action dispatch failed: actionType={}, endpoint={}, userId={}, correlationId={}, error={}",
                    action, endpoint, maskUserId(userId), correlationId, e.getMessage());
            return new DispatchResult(
                    "error", false, false, false, true,
                    "DISPATCH_FAILED: " + e.getMessage(), Map.of());
        }
    }

    private String codedHttpReason(HttpStatusCode status) {
        int code = status.value();
        if (code == 401) {
            return "HTTP_401: authentication rejected by pc-control dispatch";
        }
        if (code == 403) {
            return "HTTP_403: authorization denied by pc-control dispatch";
        }
        if (code == 400 || code == 422) {
            return "INVALID_PAYLOAD: pc-control dispatch rejected the request (" + code + ")";
        }
        return "HTTP_" + code + ": pc-control dispatch returned an error";
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
