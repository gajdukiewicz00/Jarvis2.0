package org.jarvis.orchestrator.assist;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.MemoryServiceClient;
import org.jarvis.orchestrator.resilience.RetryWithBackoff;
import org.jarvis.orchestrator.resilience.SimpleCircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real memory read/write via memory-service. All calls are best-effort: any
 * failure (service down, embeddings unavailable) degrades to empty/"skipped"
 * so the assist flow still completes. Raw screenshots are never sent.
 *
 * <p>Resilience: reads (a pure lookup, safe to retry) go through a bounded
 * retry with backoff; both reads and writes share a circuit breaker so a
 * consistently failing memory-service stops being hammered on every assist
 * turn. Writes are never retried (ingest is not idempotent).</p>
 */
@Slf4j
@Component
public class AssistMemoryImpl implements AssistMemory {

    private final MemoryServiceClient memory;
    private final SimpleCircuitBreaker circuitBreaker = new SimpleCircuitBreaker();

    @Value("${jarvis.memory.circuit-breaker.failure-threshold:3}")
    private int failureThreshold = 3;

    @Value("${jarvis.memory.circuit-breaker.reset-timeout-seconds:30}")
    private int resetTimeoutSeconds = 30;

    @Value("${jarvis.memory.retry.max-attempts:2}")
    private int retryMaxAttempts = 2;

    @Value("${jarvis.memory.retry.initial-backoff-ms:200}")
    private long retryInitialBackoffMs = 200;

    public AssistMemoryImpl(MemoryServiceClient memory) {
        this.memory = memory;
    }

    @Override
    public ReadOutcome readRecent(String userId, String query, String correlationId) {
        if (!circuitBreaker.tryAcquire()) {
            log.info("assist memory read skipped cid={}: circuit breaker open", correlationId);
            return ReadOutcome.unavailable();
        }
        try {
            List<String> items = RetryWithBackoff.call(
                    () -> doSearch(userId, query, correlationId),
                    retryMaxAttempts,
                    Duration.ofMillis(retryInitialBackoffMs));
            circuitBreaker.recordSuccess();
            return ReadOutcome.ok(items);
        } catch (RuntimeException ex) {
            circuitBreaker.recordFailure(failureThreshold, Duration.ofSeconds(resetTimeoutSeconds));
            log.info("assist memory read skipped cid={}: {}", correlationId, ex.getMessage());
            return ReadOutcome.unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> doSearch(String userId, String query, String correlationId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("userId", userId);
        req.put("query", query);
        req.put("topK", 5);
        Map<String, Object> resp = memory.search(req, correlationId);
        List<String> out = new ArrayList<>();
        if (resp != null) {
            Object results = resp.getOrDefault("results", resp.get("chunks"));
            if (results instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object txt = m.get("text");
                        if (txt == null) txt = m.get("content");
                        if (txt == null) txt = m.get("excerpt");
                        if (txt != null) out.add(txt.toString());
                    } else if (o != null) {
                        out.add(o.toString());
                    }
                }
            }
        }
        return out;
    }

    @Override
    public String write(String userId, String command, String answer, String screenSummary,
                        String actionResult, String correlationId) {
        if (!circuitBreaker.tryAcquire()) {
            log.info("assist memory write skipped cid={}: circuit breaker open", correlationId);
            return "skipped:circuit-open";
        }
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", "jarvis-assist");
            meta.put("screenSummary", screenSummary);
            meta.put("actionResult", actionResult);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("userId", userId);
            req.put("sessionId", "assist-" + userId);
            req.put("createChunks", true);
            req.put("metadata", meta);
            req.put("messages", List.of(
                    Map.of("role", "user", "content", command),
                    Map.of("role", "assistant", "content", answer == null ? "" : answer)));
            memory.ingest(req, correlationId);
            circuitBreaker.recordSuccess();
            return "memory:assist-" + userId;
        } catch (Exception ex) {
            circuitBreaker.recordFailure(failureThreshold, Duration.ofSeconds(resetTimeoutSeconds));
            log.info("assist memory write skipped cid={}: {}", correlationId, ex.getMessage());
            return "skipped:" + ex.getClass().getSimpleName();
        }
    }
}
