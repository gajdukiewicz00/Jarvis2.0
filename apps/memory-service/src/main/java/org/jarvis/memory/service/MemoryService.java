package org.jarvis.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.dto.*;
import org.jarvis.memory.entity.ConversationMessage;
import org.jarvis.memory.entity.MemoryChunk;
import org.jarvis.memory.entity.SessionSummary;
import org.jarvis.memory.repository.ConversationMessageRepository;
import org.jarvis.memory.repository.MemoryChunkRepository;
import org.jarvis.memory.repository.SessionSummaryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main memory service orchestrating storage and retrieval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ConversationMessageRepository messageRepository;
    private final MemoryChunkRepository chunkRepository;
    private final SessionSummaryRepository summaryRepository;
    private final EmbeddingClient embeddingClient;
    private final ChunkingService chunkingService;

    /** Self-ref through proxy so that @Async → @Transactional AOP chain works. */
    @Lazy
    @Autowired
    private MemoryService self;

    @Value("${memory.search.top-k:5}")
    private int defaultTopK;

    @Value("${memory.search.max-tokens:600}")
    private int defaultMaxTokens;

    @Value("${memory.search.min-similarity:0.5}")
    private double defaultMinSimilarity;

    /**
     * Ingest messages into memory
     */
    @Transactional
    public void ingest(IngestRequest request, String correlationId) {
        log.info("[{}] Ingesting {} messages for user={}, session={}",
                correlationId, request.getMessages().size(), request.getUserId(), request.getSessionId());

        long startTime = System.currentTimeMillis();
        List<UUID> savedMessageIds = new ArrayList<>();

        // 1. Save raw messages
        for (IngestRequest.MessageDto msgDto : request.getMessages()) {
            ConversationMessage msg = ConversationMessage.builder()
                    .userId(request.getUserId())
                    .sessionId(request.getSessionId())
                    .role(ConversationMessage.MessageRole.valueOf(msgDto.getRole()))
                    .content(msgDto.getContent())
                    .metadata(mergeMaps(request.getMetadata(), msgDto.getMetadata()))
                    .build();

            ConversationMessage saved = messageRepository.save(msg);
            savedMessageIds.add(saved.getId());
        }

        log.debug("[{}] Saved {} messages", correlationId, savedMessageIds.size());

        // 2. Create chunks if requested
        if (request.isCreateChunks()) {
            createChunks(request.getUserId(), savedMessageIds, request.getMessages(), correlationId);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[{}] Ingest complete in {}ms", correlationId, elapsed);
    }

    /**
     * Create chunks from messages and embed them
     */
    private void createChunks(String userId, List<UUID> messageIds, 
                              List<IngestRequest.MessageDto> messages, String correlationId) {
        // Extract text from messages
        List<String> texts = messages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.toList());

        // Chunk the conversation
        List<String> chunks = chunkingService.chunkConversation(texts);
        
        if (chunks.isEmpty()) {
            log.debug("[{}] No chunks created (text too short)", correlationId);
            return;
        }

        log.debug("[{}] Created {} chunks", correlationId, chunks.size());

        // Get embeddings for chunks
        List<List<Float>> embeddings = embeddingClient.embedBatch(chunks, correlationId);

        // Save chunks with embeddings
        UUID[] messageIdArray = messageIds.toArray(new UUID[0]);
        
        for (int i = 0; i < chunks.size(); i++) {
            MemoryChunk chunk = MemoryChunk.builder()
                    .userId(userId)
                    .sourceMessageIds(messageIdArray)
                    .chunkText(chunks.get(i))
                    .embedding(MemoryChunk.toVectorString(embeddings.get(i)))
                    .build();

            chunkRepository.save(chunk);
        }

        log.debug("[{}] Saved {} chunks with embeddings", correlationId, chunks.size());
    }

    /**
     * Search memory for relevant context
     */
    @Transactional(readOnly = true)
    public SearchResponse search(SearchRequest request, String correlationId) {
        log.info("[{}] Searching memory for user={}, query='{}'",
                correlationId, request.getUserId(), 
                request.getQuery().substring(0, Math.min(50, request.getQuery().length())));

        long startTime = System.currentTimeMillis();

        // 1. Get query embedding
        List<Float> queryEmbedding = embeddingClient.embed(request.getQuery(), correlationId);
        String queryVector = MemoryChunk.toVectorString(queryEmbedding);

        // 2. Search for similar chunks
        int topK = request.getTopK() > 0 ? request.getTopK() : defaultTopK;
        double minSimilarity = request.getMinSimilarity() > 0 ? request.getMinSimilarity() : defaultMinSimilarity;

        List<Object[]> rawResults = chunkRepository.findSimilarWithScore(
                request.getUserId(), queryVector, topK, minSimilarity);

        // 3. Convert to response format
        List<SearchResponse.ChunkResult> chunks = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int tokenCount = 0;
        int maxTokens = request.getMaxTokens() > 0 ? request.getMaxTokens() : defaultMaxTokens;

        for (Object[] row : rawResults) {
            MemoryChunk chunk = (MemoryChunk) row[0];
            double similarity = ((Number) row[1]).doubleValue();

            // Check token budget
            int chunkTokens = chunkingService.estimateTokens(chunk.getChunkText());
            if (tokenCount + chunkTokens > maxTokens) {
                break;
            }

            chunks.add(SearchResponse.ChunkResult.builder()
                    .id(chunk.getId())
                    .text(chunk.getChunkText())
                    .similarity(similarity)
                    .createdAt(chunk.getCreatedAt())
                    .build());

            // Build context text
            if (contextBuilder.length() > 0) {
                contextBuilder.append("\n---\n");
            }
            contextBuilder.append(chunk.getChunkText());
            tokenCount += chunkTokens;
        }

        long totalChunks = chunkRepository.countByUserId(request.getUserId());
        long elapsed = System.currentTimeMillis() - startTime;

        log.info("[{}] Search complete: {} results in {}ms (total chunks: {})",
                correlationId, chunks.size(), elapsed, totalChunks);

        return SearchResponse.builder()
                .chunks(chunks)
                .contextText(contextBuilder.toString())
                .estimatedTokens(tokenCount)
                .totalChunksSearched((int) totalChunks)
                .processingTimeMs(elapsed)
                .build();
    }

    /**
     * Create or update session summary (placeholder - requires LLM)
     */
    @Transactional
    public void summarizeSession(SummarizeRequest request, String correlationId) {
        log.info("[{}] Summarizing session={} for user={}",
                correlationId, request.getSessionId(), request.getUserId());

        // Get messages for session
        List<ConversationMessage> messages = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(request.getSessionId());

        if (messages.isEmpty()) {
            log.warn("[{}] No messages found for session", correlationId);
            return;
        }

        // For now, create a simple summary (TODO: use LLM for better summaries)
        String summaryText = createSimpleSummary(messages);
        String[] topics = extractTopics(messages);

        SessionSummary summary = summaryRepository.findBySessionId(request.getSessionId())
                .orElse(SessionSummary.builder()
                        .sessionId(request.getSessionId())
                        .userId(request.getUserId())
                        .build());

        summary.setSummaryText(summaryText);
        summary.setMessageCount(messages.size());
        summary.setKeyTopics(topics);

        summaryRepository.save(summary);

        log.info("[{}] Session summary saved: {} messages, {} topics",
                correlationId, messages.size(), topics.length);
    }

    /**
     * Async version of ingest for fire-and-forget use
     */
    @Async
    public void ingestAsync(IngestRequest request, String correlationId) {
        try {
            self.ingest(request, correlationId);
        } catch (RuntimeException e) {
            log.error("[{}] Async ingest failed: {}", correlationId, e.getMessage(), e);
        }
    }

    // Helper methods

    private String createSimpleSummary(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Диалог из ").append(messages.size()).append(" сообщений. ");

        // Count by role
        long userMsgs = messages.stream().filter(m -> m.getRole() == ConversationMessage.MessageRole.user).count();
        long assistantMsgs = messages.stream().filter(m -> m.getRole() == ConversationMessage.MessageRole.assistant).count();

        sb.append("Пользователь: ").append(userMsgs).append(", Ассистент: ").append(assistantMsgs).append(". ");

        // First and last messages
        if (!messages.isEmpty()) {
            sb.append("Начало: '").append(truncate(messages.get(0).getContent(), 50)).append("'. ");
            if (messages.size() > 1) {
                sb.append("Конец: '").append(truncate(messages.get(messages.size() - 1).getContent(), 50)).append("'.");
            }
        }

        return sb.toString();
    }

    private String[] extractTopics(List<ConversationMessage> messages) {
        // Simple keyword extraction (TODO: use NLP/LLM for better extraction)
        Set<String> topics = new HashSet<>();
        
        for (ConversationMessage msg : messages) {
            if (msg.getRole() == ConversationMessage.MessageRole.user) {
                String content = msg.getContent().toLowerCase();
                // Extract potential topics based on keywords
                if (content.contains("напомни") || content.contains("помни")) topics.add("напоминание");
                if (content.contains("таймер") || content.contains("время")) topics.add("время");
                if (content.contains("погода")) topics.add("погода");
                if (content.contains("музыка") || content.contains("играй")) topics.add("музыка");
                if (content.contains("свет") || content.contains("лампа")) topics.add("умный дом");
            }
        }

        return topics.toArray(new String[0]);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private Map<String, Object> mergeMaps(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> result = new HashMap<>();
        if (a != null) result.putAll(a);
        if (b != null) result.putAll(b);
        return result;
    }
}



