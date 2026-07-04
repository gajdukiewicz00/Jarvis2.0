package org.jarvis.voicegateway.voiceloop;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.voice.VoiceFeedback;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7 — calls {@code orchestrator /api/v1/orchestrator/voice/dispatch}.
 *
 * <p>Returns a {@link VoiceLoopReply} that carries the orchestrator's
 * spoken-feedback envelope and the session status. The voice-gateway
 * controller then renders the spoken text via TTS (existing pipeline)
 * and writes the reply back into the session.</p>
 */
@Slf4j
@Component
public class OrchestratorVoiceClient {

    private final RestTemplate restTemplate;
    private final String dispatchUrl;
    private final ServiceJwtProvider serviceJwtProvider;
    private final String serviceName;

    public OrchestratorVoiceClient(
            @Value("${jarvis.voice.orchestrator-dispatch-url:http://orchestrator:8083/api/v1/orchestrator/voice/dispatch}") String dispatchUrl,
            @Value("${jarvis.voice.orchestrator-timeout-ms:30000}") long timeoutMs,
            @Value("${spring.application.name:voice-gateway}") String serviceName,
            ServiceJwtProvider serviceJwtProvider) {
        this.dispatchUrl = dispatchUrl;
        this.serviceName = serviceName;
        this.serviceJwtProvider = serviceJwtProvider;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5_000)));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        log.info("OrchestratorVoiceClient init: url={} timeout={}ms serviceTokenEnabled={}",
                dispatchUrl, timeoutMs, serviceJwtProvider != null && serviceJwtProvider.isEnabled());
    }

    public VoiceLoopReply dispatch(String sessionId, String userId, String correlationId,
                                    String intent, String transcript) {
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", sessionId);
        body.put("userId", userId);
        body.put("source", "VOICE");
        body.put("intent", intent);
        body.put("transcript", transcript);
        body.put("correlationId", correlationId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // orchestrator /voice/dispatch is on the internal trust plane (requires SVC_INTERNAL).
        // Attach a short-lived service token; the token itself is never logged.
        if (serviceJwtProvider != null && serviceJwtProvider.isEnabled()) {
            headers.set("X-Service-Token",
                    serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
        }

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    dispatchUrl, new HttpEntity<>(body, headers), Map.class);
            return toReply(resp.getBody());
        } catch (RuntimeException ex) {
            log.error("[{}] orchestrator dispatch failed: {}", sessionId, ex.getMessage());
            return new VoiceLoopReply(
                    null, null, VoiceSessionStatus.FAILED,
                    VoiceFeedback.builder()
                            .code("FAILED")
                            .level(VoiceFeedback.Level.ERROR)
                            .spokenText("Не удалось связаться с оркестратором, сэр.")
                            .displayText("orchestrator dispatch error: " + ex.getMessage())
                            .build()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private VoiceLoopReply toReply(Map<String, Object> body) {
        if (body == null) {
            return new VoiceLoopReply(null, null, VoiceSessionStatus.FAILED,
                    VoiceFeedback.builder().code("FAILED").level(VoiceFeedback.Level.ERROR)
                            .spokenText("Пустой ответ от оркестратора, сэр.").build());
        }
        String commandId = (String) body.get("commandId");
        String correlationId = (String) body.get("correlationId");
        VoiceSessionStatus status = body.get("sessionStatus") instanceof String s
                ? VoiceSessionStatus.valueOf(s) : VoiceSessionStatus.FAILED;
        VoiceFeedback fb = mapFeedback((Map<String, Object>) body.get("feedback"));
        return new VoiceLoopReply(commandId, correlationId, status, fb);
    }

    private VoiceFeedback mapFeedback(Map<String, Object> raw) {
        if (raw == null) {
            return VoiceFeedback.builder().code("UNKNOWN").level(VoiceFeedback.Level.WARN)
                    .spokenText("").build();
        }
        VoiceFeedback.Level level = raw.get("level") instanceof String l
                ? VoiceFeedback.Level.valueOf(l) : VoiceFeedback.Level.INFO;
        return VoiceFeedback.builder()
                .code((String) raw.get("code"))
                .level(level)
                .spokenText((String) raw.get("spokenText"))
                .displayText((String) raw.get("displayText"))
                .build();
    }

    public record VoiceLoopReply(String commandId, String correlationId,
                                  VoiceSessionStatus status, VoiceFeedback feedback) {}
}
