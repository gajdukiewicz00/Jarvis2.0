package org.jarvis.syncservice.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.sync.SyncPayload;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12 — HTTP-backed dispatcher.
 *
 * <p>Posts FINANCE_ENTRY payloads to {@code life-tracker} and
 * COMMAND_INTENT payloads to {@code orchestrator/execute} so commands
 * pass through the existing risk/confirmation pipeline (Phase 5) — a
 * mobile command never bypasses the safe-command model.</p>
 */
@Slf4j
@Component
public class HttpDispatchClient implements DispatchClient {

    private final RestTemplate http;
    private final SyncServiceProperties props;

    public HttpDispatchClient(RestTemplateBuilder builder, SyncServiceProperties props) {
        Duration timeout = Duration.ofMillis(props.getDispatchTimeoutMillis());
        this.http = builder
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .build();
        this.props = props;
    }

    @Override
    public DispatchResult dispatchFinanceEntry(String userId, SyncPayload payload) {
        Map<String, Object> data = payload.getData();
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("amount", data.get("amount"));
        body.put("currency", data.getOrDefault("currency", "EUR"));
        body.put("category", data.get("category"));
        body.put("description", data.get("description"));
        body.put("type", data.getOrDefault("type", "EXPENSE"));
        body.put("merchant", data.get("merchant"));
        body.put("paymentMethod", data.get("paymentMethod"));
        // life-tracker expects a LocalDateTime ("2026-06-06T15:30:00"); Instant.toString()
        // carries a trailing 'Z'/offset that Jackson cannot bind to LocalDateTime (HTTP 400).
        Object occRaw = data.get("occurredAt");
        String occStr = occRaw != null ? occRaw.toString()
                : java.time.LocalDateTime.ofInstant(payload.getClientOccurredAt(),
                        java.time.ZoneId.systemDefault()).toString();
        occStr = occStr.replaceAll("([+-]\\d{2}:\\d{2}|Z)$", "");
        if (occStr.length() > 19) {
            occStr = occStr.substring(0, 19);
        }
        body.put("occurredAt", occStr);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);
        headers.set("X-Client-Nonce", payload.getClientNonce());

        try {
            http.postForEntity(props.getLifeTrackerUrl() + "/api/v1/life/finance/transaction",
                    new HttpEntity<>(body, headers), Map.class);
            return DispatchResult.success();
        } catch (RestClientException e) {
            log.warn("life-tracker dispatch failed: {}", e.getMessage());
            return DispatchResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public DispatchResult dispatchHealthEntry(String userId, SyncPayload payload) {
        Map<String, Object> data = payload.getData();
        Map<String, Object> body = new HashMap<>();
        body.put("sleepHours", data.get("sleepHours"));
        body.put("steps", data.get("steps"));
        body.put("date", data.getOrDefault("date", payload.getClientOccurredAt().toString()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);
        headers.set("X-Client-Nonce", payload.getClientNonce());

        try {
            http.postForEntity(props.getLifeTrackerUrl() + "/api/v1/life/wellness/health-entry",
                    new HttpEntity<>(body, headers), Map.class);
            return DispatchResult.success();
        } catch (RestClientException e) {
            log.warn("life-tracker health dispatch failed: {}", e.getMessage());
            return DispatchResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public DispatchResult dispatchCommandIntent(String userId, SyncPayload payload) {
        Map<String, Object> data = payload.getData();
        Map<String, Object> body = new HashMap<>();
        body.put("intent", data.get("intent"));
        body.put("parameters", data.getOrDefault("parameters", Map.of()));
        body.put("text", data.get("text"));
        body.put("originalText", data.get("originalText"));
        body.put("language", data.getOrDefault("language", "ru"));
        body.put("correlationId", data.getOrDefault("correlationId",
                "mobile-" + payload.getClientNonce() + "-" + UUID.randomUUID()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);
        headers.set("X-Source", "mobile");
        headers.set("X-Client-Nonce", payload.getClientNonce());

        try {
            http.postForEntity(props.getOrchestratorUrl() + "/api/v1/orchestrator/execute",
                    new HttpEntity<>(body, headers), String.class);
            return DispatchResult.success();
        } catch (RestClientException e) {
            log.warn("orchestrator dispatch failed: {}", e.getMessage());
            return DispatchResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
