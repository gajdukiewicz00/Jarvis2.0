package org.jarvis.orchestrator.assist;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.MemoryServiceClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real memory read/write via memory-service. All calls are best-effort: any
 * failure (service down, embeddings unavailable) degrades to empty/"skipped"
 * so the assist flow still completes. Raw screenshots are never sent.
 */
@Slf4j
@Component
public class AssistMemoryImpl implements AssistMemory {

    private final MemoryServiceClient memory;

    public AssistMemoryImpl(MemoryServiceClient memory) { this.memory = memory; }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> readRecent(String userId, String query, String correlationId) {
        try {
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
        } catch (Exception ex) {
            log.info("assist memory read skipped cid={}: {}", correlationId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public String write(String userId, String command, String answer, String screenSummary,
                        String actionResult, String correlationId) {
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
            return "memory:assist-" + userId;
        } catch (Exception ex) {
            log.info("assist memory write skipped cid={}: {}", correlationId, ex.getMessage());
            return "skipped:" + ex.getClass().getSimpleName();
        }
    }
}
