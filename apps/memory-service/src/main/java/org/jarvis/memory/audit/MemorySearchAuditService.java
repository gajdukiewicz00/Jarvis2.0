package org.jarvis.memory.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Records one row in {@code memory_search_audit} per /memory/search call.
 *
 * <p>Failures are intentionally swallowed: the search response must never
 * be degraded by the audit path. The contract is "best-effort".</p>
 *
 * <p>The query body is hashed (SHA-256) and only optionally excerpted, gated
 * by {@code logging.pii.allowQuerySnippet}. No note bodies are stored —
 * only chunk ids and (in a future patch) note paths.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySearchAuditService {

    private final MemorySearchAuditRepository repository;

    @Value("${logging.pii.allowQuerySnippet:false}")
    private boolean allowQuerySnippet;

    @Value("${logging.pii.querySnippetMaxLength:32}")
    private int querySnippetMaxLength;

    /**
     * Persist one audit row. Runs in a new transaction so a search-time
     * commit failure does not roll back the search itself, and so an
     * audit failure cannot trigger a re-throw of {@code TransactionSystemException}
     * upward.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SearchRequest request,
                       SearchResponse response,
                       String selectedModel,
                       boolean rerankUsed,
                       String correlationId) {
        try {
            String queryHash = sha256Hex(request.getQuery());
            String excerpt = excerptOrNull(request.getQuery());
            List<String> chunkIds = response.getChunks() == null
                    ? List.of()
                    : response.getChunks().stream()
                            .map(c -> c.getId() == null ? null : c.getId().toString())
                            .filter(java.util.Objects::nonNull)
                            .toList();

            MemorySearchAuditEntity row = MemorySearchAuditEntity.builder()
                    .userId(request.getUserId())
                    .queryHash(queryHash)
                    .queryExcerpt(excerpt)
                    .selectedModel(selectedModel)
                    .retrievalMode(safe(response.getRetrievalMode(), "unknown"))
                    .rerankUsed(rerankUsed)
                    .topK(request.getTopK() > 0 ? request.getTopK() : 0)
                    .resultCount(response.getChunks() == null ? 0 : response.getChunks().size())
                    .retrievedNotePaths(List.of())
                    .retrievedChunkIds(chunkIds)
                    .processingTimeMs(toIntOrNull(response.getProcessingTimeMs()))
                    .correlationId(correlationId)
                    .createdAt(Instant.now())
                    .build();

            repository.save(row);
        } catch (DataAccessException ex) {
            log.warn("[{}] memory_search_audit insert failed: {}", correlationId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("[{}] memory_search_audit unexpected error: {}", correlationId, ex.getMessage(), ex);
        }
    }

    private String excerptOrNull(String query) {
        if (!allowQuerySnippet || query == null || query.isBlank()) {
            return null;
        }
        int max = Math.max(0, querySnippetMaxLength);
        if (max == 0) {
            return null;
        }
        return query.length() <= max ? query : query.substring(0, max);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer toIntOrNull(long value) {
        if (value < 0) {
            return null;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
