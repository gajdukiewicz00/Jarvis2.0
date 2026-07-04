package org.jarvis.memory.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.logging.LogSanitizer;
import org.jarvis.memory.dto.*;
import org.jarvis.memory.service.MemoryDependencyStatusService;
import org.jarvis.memory.service.MemoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for memory operations
 */
@Slf4j
@RestController
// Dual base path: internal callers use /memory/*, the api-gateway forwards the
// full /api/v1/memory/* path unchanged — both must resolve to this controller.
@RequestMapping({"/memory", "/api/v1/memory"})
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;
    private final MemoryDependencyStatusService dependencyStatusService;

    @Value("${logging.pii.enabled:true}")
    private boolean piiLoggingEnabled = true;

    @Value("${logging.pii.allowQuerySnippet:false}")
    private boolean piiAllowQuerySnippet = false;

    @Value("${logging.pii.querySnippetMaxLength:32}")
    private int piiQuerySnippetMaxLength = 32;

    /**
     * Ingest messages into memory
     * 
     * POST /memory/ingest
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @Valid @RequestBody IngestRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        
        String userId = requireUserId();
        request.setUserId(userId);
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        LogSanitizer sanitizer = logSanitizer();
        
        log.info("[{}] POST /memory/ingest: userId={}, sessionId={}, messages={}",
                corrId,
                sanitizer.sanitizeId(userId),
                sanitizer.sanitizeId(request.getSessionId()),
                request.getMessages().size());
        
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
        
        String userId = requireUserId();
        request.setUserId(userId);
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        LogSanitizer sanitizer = logSanitizer();
        
        log.info("[{}] POST /memory/ingest/async: userId={}, sessionId={}",
                corrId,
                sanitizer.sanitizeId(userId),
                sanitizer.sanitizeId(request.getSessionId()));
        
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
        
        String userId = requireUserId();
        request.setUserId(userId);
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        LogSanitizer sanitizer = logSanitizer();
        
        log.info("[{}] POST /memory/search: userId={}, query='{}', topK={}",
                corrId,
                sanitizer.sanitizeId(userId),
                sanitizer.sanitizeText(request.getQuery()),
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
        
        String userId = requireUserId();
        request.setUserId(userId);
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString().substring(0, 8);
        LogSanitizer sanitizer = logSanitizer();
        
        log.info("[{}] POST /memory/summarize-session: sessionId={}, userId={}",
                corrId,
                sanitizer.sanitizeId(request.getSessionId()),
                sanitizer.sanitizeId(userId));
        
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
        MemoryDependencyStatusService.DependencyStatus status = dependencyStatusService.checkDependencies();
        HttpStatus httpStatus = "healthy".equals(status.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(Map.of(
                "status", status.status(),
                "database", status.database(),
                "pgvector", status.pgvector(),
                "embeddingService", status.embeddingService(),
                "embeddingModel", status.embeddingModel() == null ? "unknown" : status.embeddingModel(),
                "embeddingDimension", status.embeddingDimension() == null ? 0 : status.embeddingDimension(),
                "embeddingError", status.embeddingError() == null ? "" : status.embeddingError()
        ));
    }

    private String requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication");
        }
        return authentication.getName();
    }

    private LogSanitizer logSanitizer() {
        return new LogSanitizer(piiLoggingEnabled, piiAllowQuerySnippet, piiQuerySnippetMaxLength);
    }
}
