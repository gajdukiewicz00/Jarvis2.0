package org.jarvis.orchestrator.controller;

import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.ApiGatewayVisionClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal "analyze my screen" passthrough for voice-gateway. voice-gateway is
 * NetworkPolicy-blocked from api-gateway; it IS allowed to reach the orchestrator, which
 * forwards to the api-gateway vision-security proxy → vision-security-service:8094 (a
 * workstation host-bridge). Mirrors the pc-control / planner / finance passthroughs.
 *
 * <p>A vision capture is a best-effort side action: any downstream failure (connect/timeout,
 * 4xx/5xx, or an empty answer) is folded into a {@code vision_failed} status with a coded
 * {@code failureReason} rather than thrown, so the voice layer can speak an honest fallback
 * instead of erroring out.
 */
@Slf4j
@RestController
@RequestMapping("/internal/vision")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalVisionController {

    private static final String DEFAULT_QUESTION = "Что на экране?";

    private final ApiGatewayVisionClient visionClient;

    @PostMapping("/ask-screen")
    public Map<String, Object> askScreen(@RequestBody AskScreenRequest request) {
        String question = request.question() != null && !request.question().isBlank()
                ? request.question()
                : DEFAULT_QUESTION;
        String userId = request.userId() != null && !request.userId().isBlank()
                ? request.userId()
                : "local-user";
        String correlationId = request.correlationId() != null && !request.correlationId().isBlank()
                ? request.correlationId()
                : "N/A";

        Map<String, Object> response;
        try {
            Map<String, Object> visionResult = visionClient.askScreen(
                    userId, new ApiGatewayVisionClient.AskScreenRequest(question, true));
            Object answerValue = visionResult != null ? visionResult.get("answer") : null;
            String answer = answerValue != null ? String.valueOf(answerValue) : "";
            response = answer.isBlank() ? failure("VISION_EMPTY") : success(answer);
        } catch (RuntimeException e) {
            String failureReason = classifyFailure(e);
            log.warn("🖥️ Vision passthrough downstream failed: userId={}, correlationId={}, reason={}, error={}",
                    userId, correlationId, failureReason, e.getMessage());
            response = failure(failureReason);
        }

        log.info("🖥️ Vision passthrough: userId={}, correlationId={}, status={}, failureReason={}",
                userId, correlationId, response.get("status"), response.get("failureReason"));
        return response;
    }

    private Map<String, Object> success(String answer) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "analyzed");
        out.put("answer", answer);
        return out;
    }

    private Map<String, Object> failure(String failureReason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "vision_failed");
        out.put("answer", "");
        out.put("failureReason", failureReason);
        return out;
    }

    /**
     * Maps a downstream failure to a stable, machine-readable reason code:
     * connect/read timeouts and unrecognized errors become {@code VISION_UNAVAILABLE};
     * an HTTP error response becomes {@code VISION_HTTP_<code>}.
     */
    private String classifyFailure(RuntimeException e) {
        if (e instanceof RetryableException) {
            return "VISION_UNAVAILABLE";
        }
        if (e instanceof FeignException feignException && feignException.status() > 0) {
            return "VISION_HTTP_" + feignException.status();
        }
        return "VISION_UNAVAILABLE";
    }

    /**
     * Request from voice-gateway. {@code question} may be null/blank (defaults to
     * {@value #DEFAULT_QUESTION}); {@code userId} scopes the capture; {@code correlationId}
     * threads voice-turn tracing.
     */
    public record AskScreenRequest(String question, String userId, String correlationId) {
    }
}
