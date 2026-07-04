package org.jarvis.lifetracker.lifemap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 / movie-J.A.R.V.I.S. — makes the cluster speak on its own.
 *
 * <p>On a schedule, evaluates each monitored user's daily life-map warnings
 * (TIME_WASTE / OVERSPEND / LOW_SLEEP) and, when a fresh one appears, asks
 * voice-gateway to speak it (POST /internal/voice/notify → Piper TTS → the
 * user's WebSocket session). Heavily rate-limited and quiet-hours aware so it
 * behaves like a butler, not a nag. If the user has no active voice session,
 * voice-gateway returns 404 and we simply stay silent.</p>
 */
@Slf4j
@Service
public class ProactiveWarningScheduler {

    private final DailySummaryService summaryService;
    private final RestTemplate restTemplate;

    @Value("${services.voice-gateway.url:http://voice-gateway:8081}")
    private String voiceGatewayUrl;
    @Value("${jarvis.proactive.enabled:true}")
    private boolean enabled;
    @Value("${jarvis.proactive.users:owner}")
    private String usersCsv;
    @Value("${jarvis.proactive.min-gap-seconds:1800}")
    private long minGapSeconds;
    @Value("${jarvis.proactive.language:en-US}")
    private String language;
    @Value("${jarvis.proactive.quiet-start-hour:23}")
    private int quietStartHour;
    @Value("${jarvis.proactive.quiet-end-hour:8}")
    private int quietEndHour;

    /** key = "userId|warningCode" -> last time we spoke it. */
    private final Map<String, Instant> lastSpoke = new ConcurrentHashMap<>();

    public ProactiveWarningScheduler(DailySummaryService summaryService,
                                     @Qualifier("serviceRestTemplate") RestTemplate restTemplate) {
        this.summaryService = summaryService;
        this.restTemplate = restTemplate;
    }

    @Scheduled(fixedRateString = "${jarvis.proactive.interval-ms:300000}", initialDelay = 60000)
    public void tick() {
        if (!enabled || inQuietHours()) {
            return;
        }
        for (String raw : usersCsv.split(",")) {
            String userId = raw.trim();
            if (userId.isEmpty()) {
                continue;
            }
            try {
                evaluateAndSpeak(userId);
            } catch (Exception e) {
                log.warn("proactive tick failed for user={}: {}", userId, e.getMessage());
            }
        }
    }

    private void evaluateAndSpeak(String userId) {
        LifeMapDtos.DailySummary summary = summaryService.summarise(userId, LocalDate.now());
        for (LifeMapDtos.ProactiveWarning w : summary.warnings()) {
            String key = userId + "|" + w.code();
            Instant last = lastSpoke.get(key);
            if (last != null && Instant.now().minusSeconds(minGapSeconds).isBefore(last)) {
                continue; // spoke this warning code too recently
            }
            if (notifyVoice(userId, w.message())) {
                lastSpoke.put(key, Instant.now());
                log.info("proactive: spoke {} warning to user={}", w.code(), userId);
            }
        }
    }

    private boolean notifyVoice(String userId, String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String url = voiceGatewayUrl.replaceAll("/+$", "") + "/internal/voice/notify";
        Map<String, Object> body = Map.of(
                "userId", userId,
                "message", message,
                "languageCode", language);
        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, body, String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (HttpClientErrorException.NotFound nf) {
            return false; // user not connected — nothing to speak to
        } catch (RestClientException e) {
            log.debug("proactive voice notify failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean inQuietHours() {
        int h = LocalTime.now().getHour();
        if (quietStartHour > quietEndHour) {
            return h >= quietStartHour || h < quietEndHour;
        }
        return h >= quietStartHour && h < quietEndHour;
    }
}
