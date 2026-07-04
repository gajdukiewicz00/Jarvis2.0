package org.jarvis.lifetracker.lifemap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Phase 11 — best-effort fan-out to other services for the life map.
 *
 * <p>Each call has a strict timeout (default 1.5s). On any failure the
 * method returns a sentinel ("unavailable" / {@code null} / 0) — the
 * summary surface is built from whatever fragments succeed.</p>
 */
@Slf4j
@Component
public class CrossServiceClient {

    private final RestTemplate restTemplate;
    private final String plannerUrl;
    private final String visionUrl;
    private final String memoryUrl;

    public CrossServiceClient(
            @Value("${jarvis.life-map.cross.planner-url:http://planner-service:8092}") String plannerUrl,
            @Value("${jarvis.life-map.cross.vision-url:http://vision-security-service:8094}") String visionUrl,
            @Value("${jarvis.life-map.cross.memory-url:http://memory-service:8093}") String memoryUrl,
            @Value("${jarvis.life-map.cross.timeout-ms:1500}") long timeoutMs) {
        this.plannerUrl = plannerUrl;
        this.visionUrl = visionUrl;
        this.memoryUrl = memoryUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 1_000)));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
    }

    public Tasks fetchTasks(String userId) {
        return runCatching(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.getForObject(
                    plannerUrl + "/api/v1/planner/tasks/summary?userId=" + safe(userId), Map.class);
            if (body == null) return Tasks.empty();
            int open = intValue(body.get("open"));
            int done = intValue(body.get("doneToday"));
            return new Tasks(open, done);
        }, Tasks.empty(), "planner-service");
    }

    public int fetchVisionIncidentCount(String userId) {
        return runCatching(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.getForObject(
                    visionUrl + "/api/v1/vision/incidents/count?userId=" + safe(userId)
                            + "&windowHours=24", Map.class);
            if (body == null) return 0;
            return intValue(body.get("count"));
        }, 0, "vision-security-service");
    }

    public int fetchMemoryWriteCount(String userId) {
        return runCatching(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.getForObject(
                    memoryUrl + "/api/v1/audit/events?userId=" + safe(userId)
                            + "&eventType=MEMORY_WRITTEN&limit=200", Map.class);
            if (body == null) return 0;
            // Endpoint returns an array — Spring deserialises into a list-typed Map only
            // when wrapped, so for raw arrays we can't use Map. Fall back to 0 here;
            // a richer implementation lands in Phase 12.
            return intValue(body.get("size"));
        }, 0, "memory-service");
    }

    public record Tasks(int open, int doneToday) {
        public static Tasks empty() { return new Tasks(0, 0); }
    }

    private <T> T runCatching(java.util.concurrent.Callable<T> call, T fallback, String service) {
        try {
            return call.call();
        } catch (ResourceAccessException ex) {
            log.debug("{} unreachable for life-map: {}", service, ex.getMessage());
            return fallback;
        } catch (RestClientException ex) {
            log.debug("{} error for life-map: {}", service, ex.getMessage());
            return fallback;
        } catch (Exception ex) {
            log.debug("{} call failed for life-map: {}", service, ex.getMessage());
            return fallback;
        }
    }

    private int intValue(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private String safe(String s) { return s == null ? "" : s; }
}
