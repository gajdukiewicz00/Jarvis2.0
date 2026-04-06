package org.jarvis.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response from memory search
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {

    /**
     * Found memory chunks
     */
    private List<ChunkResult> chunks;

    /**
     * Combined context text (formatted for LLM)
     */
    private String contextText;

    /**
     * Estimated token count of context
     */
    private int estimatedTokens;

    /**
     * Retrieval path that produced the response.
     * semantic = pgvector similarity
     * lexical-fallback = deterministic text ranking after semantic miss
     */
    private String retrievalMode;

    /**
     * Non-empty only when the response is degraded compared with the canonical
     * semantic path.
     */
    private String degradedReason;

    /**
     * Total chunks searched
     */
    private int totalChunksSearched;

    /**
     * Processing time in ms
     */
    private long processingTimeMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkResult {
        private UUID id;
        private String text;
        private double similarity;
        private OffsetDateTime createdAt;
    }
}


