package org.jarvis.memory.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.dto.*;
import org.jarvis.memory.service.EmbeddingClient;
import org.jarvis.memory.service.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for memory operations
 */
@Slf4j
@RestController
@RequestMapping("/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;
    private final EmbeddingClient embeddingClient;

    /**
     * Ingest messages into memory
     * 
     * POST /memory/ingest
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @Valid @RequestBody IngestRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[{}] POST /memory/ingest: userId={}, sessionId={}, messages={}",
                corrId, request.getUserId(), request.getSessionId(), request.getMessages().size());
        
        long startTime = System.currentTimeMillis();
        
        memoryService.ingest(request, corrId);
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "messagesIngested", request.getMessages().size(),
                "processingTimeMs", elapsed
        ));
    }

    /**
     * Ingest messages asynchronously (fire-and-forget)
     * 
     * POST /memory/ingest/async
     */
    @PostMapping("/ingest/async")
    public ResponseEntity<Map<String, Object>> ingestAsync(
            @Valid @RequestBody IngestRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[{}] POST /memory/ingest/async: userId={}, sessionId={}",
                corrId, request.getUserId(), request.getSessionId());
        
        memoryService.ingestAsync(request, corrId);
        
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "correlationId", corrId
        ));
    }

    /**
     * Search memory for relevant context
     * 
     * POST /memory/search
     */
    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @Valid @RequestBody SearchRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[{}] POST /memory/search: userId={}, query='{}', topK={}",
                corrId, request.getUserId(), 
                request.getQuery().substring(0, Math.min(50, request.getQuery().length())),
                request.getTopK());
        
        SearchResponse response = memoryService.search(request, corrId);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Generate or update session summary
     * 
     * POST /memory/summarize-session
     */
    @PostMapping("/summarize-session")
    public ResponseEntity<Map<String, Object>> summarizeSession(
            @Valid @RequestBody SummarizeRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[{}] POST /memory/summarize-session: sessionId={}, userId={}",
                corrId, request.getSessionId(), request.getUserId());
        
        long startTime = System.currentTimeMillis();
        
        memoryService.summarizeSession(request, corrId);
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "sessionId", request.getSessionId(),
                "processingTimeMs", elapsed
        ));
    }

    /**
     * Health check with embedding service status
     * 
     * GET /memory/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean embeddingHealthy = embeddingClient.isHealthy();
        
        return ResponseEntity.ok(Map.of(
                "status", embeddingHealthy ? "healthy" : "degraded",
                "embeddingService", embeddingHealthy ? "up" : "down"
        ));
    }
}



