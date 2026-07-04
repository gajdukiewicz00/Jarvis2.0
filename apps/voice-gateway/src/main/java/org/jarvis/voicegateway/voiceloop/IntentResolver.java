package org.jarvis.voicegateway.voiceloop;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7 — resolves a transcript to an intent by calling
 * {@code nlp-service /api/v1/nlp/intent-fast}.
 *
 * <p>nlp-service itself decides whether to use the host router model
 * (Phase 3) or the deterministic regex engine — the wire is one-way and
 * we always get back {@code intent + source + confidence}. If
 * nlp-service is unreachable we return an empty result so the caller
 * can speak {@code unknownIntent} feedback.</p>
 */
@Slf4j
@Component
public class IntentResolver {

    private final RestTemplate restTemplate;
    private final String nlpUrl;
    private final ServiceJwtProvider serviceJwtProvider;
    private final String serviceName;

    public IntentResolver(
            @Value("${jarvis.voice.nlp-intent-url:http://nlp-service:8082/api/v1/nlp/intent-fast}") String nlpUrl,
            @Value("${jarvis.voice.nlp-timeout-ms:1500}") long timeoutMs,
            @Value("${spring.application.name:voice-gateway}") String serviceName,
            ServiceJwtProvider serviceJwtProvider) {
        this.nlpUrl = nlpUrl;
        this.serviceName = serviceName;
        this.serviceJwtProvider = serviceJwtProvider;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        log.info("IntentResolver init: url={} timeout={}ms serviceTokenEnabled={}",
                nlpUrl, timeoutMs, serviceJwtProvider != null && serviceJwtProvider.isEnabled());
    }

    public Resolution resolve(String transcript, String locale) {
        if (transcript == null || transcript.isBlank()) {
            return Resolution.empty();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("text", transcript);
        if (locale != null && !locale.isBlank()) {
            body.put("locale", locale);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // nlp-service is on the internal trust plane (BaseSecurityConfig / ServiceJwtFilter,
        // requires SVC_INTERNAL). Mirror the working voice-gateway internal clients and attach
        // a short-lived service token. The token itself is never logged.
        if (serviceJwtProvider != null && serviceJwtProvider.isEnabled()) {
            headers.set("X-Service-Token",
                    serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
        }

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    nlpUrl, new HttpEntity<>(body, headers), Map.class);
            Map<?, ?> bodyMap = response.getBody();
            if (bodyMap == null) return Resolution.empty();
            String intent = stringFrom(bodyMap.get("intent"));
            String source = stringFrom(bodyMap.get("source"));
            double confidence = doubleFrom(bodyMap.get("confidence"));
            return new Resolution(intent, source == null ? "regex" : source, confidence);
        } catch (ResourceAccessException ex) {
            log.warn("nlp-service unreachable: {}", ex.getMessage());
            return Resolution.empty();
        } catch (RestClientException ex) {
            log.warn("nlp-service error: {}", ex.getMessage());
            return Resolution.empty();
        } catch (RuntimeException ex) {
            // Defensive: never let intent resolution 500 the utterance (e.g. token minting
            // failure) — fall back to UNKNOWN_INTENT so the caller speaks graceful feedback.
            log.warn("intent resolution failed: {}", ex.getClass().getSimpleName());
            return Resolution.empty();
        }
    }

    private String stringFrom(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private double doubleFrom(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    public record Resolution(String intent, String source, double confidence) {
        public static Resolution empty() { return new Resolution(null, "none", 0.0); }
        public boolean isResolved() { return intent != null && !intent.isBlank(); }
    }
}
