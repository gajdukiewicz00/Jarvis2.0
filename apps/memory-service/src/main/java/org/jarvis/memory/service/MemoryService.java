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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
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
    private final MemoryIngestService memoryIngestService;
    private final Clock clock;

    @Value("${memory.search.top-k:5}")
    private int defaultTopK;

    @Value("${memory.search.max-tokens:600}")
    private int defaultMaxTokens;

    @Value("${memory.search.min-similarity:0.5}")
    private double defaultMinSimilarity;

    private static final Map<String, List<String>> TOPIC_ALIASES = Map.ofEntries(
            Map.entry("timer", List.of("timer", "timers", "таймер", "таймеры", "alarm", "будильник")),
            Map.entry("reminders", List.of("remind", "reminder", "напомни", "напомин", "remember this")),
            Map.entry("music", List.of("music", "song", "spotify", "playlist", "музык", "песн", "играй")),
            Map.entry("smart-home", List.of("light", "lights", "lamp", "switch", "home", "ламп", "свет", "умный дом")),
            Map.entry("weather", List.of("weather", "forecast", "погод", "дожд", "солнц")),
            Map.entry("calendar", List.of("calendar", "meeting", "event", "schedule", "календар", "встреч", "событ")),
            Map.entry("finance", List.of("budget", "expense", "finance", "money", "бюджет", "расход", "деньг")),
            Map.entry("tasks", List.of("todo", "task", "tasks", "задач", "дела", "checklist")),
            Map.entry("sleep", List.of("sleep", "slept", "nap", "сон", "спал", "дрем")),
            Map.entry("work", List.of("work", "coding", "project", "job", "работ", "код", "проект"))
    );
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "have", "from", "later", "also",
            "как", "это", "для", "что", "тебя", "если", "потом", "можно", "please");
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    /**
     * Ingest messages into memory
     */
    public void ingest(IngestRequest request, String correlationId) {
        memoryIngestService.ingest(request, correlationId);
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

        int topK = request.getTopK() > 0 ? request.getTopK() : defaultTopK;
        double minSimilarity = request.getMinSimilarity() > 0 ? request.getMinSimilarity() : defaultMinSimilarity;
        int maxTokens = request.getMaxTokens() > 0 ? request.getMaxTokens() : defaultMaxTokens;

        try {
            List<Float> queryEmbedding = embeddingClient.embed(request.getQuery(), correlationId);
            String queryVector = MemoryChunk.toVectorString(queryEmbedding);

            List<Object[]> rawResults = chunkRepository.findSimilarWithScore(
                    request.getUserId(), queryVector, topK, minSimilarity);

            SearchResponse semanticResponse = buildSemanticResponse(
                    request.getUserId(), rawResults, maxTokens, startTime, correlationId);
            if (!semanticResponse.getChunks().isEmpty()) {
                return semanticResponse;
            }

            log.info("[{}] Semantic search returned no results, falling back to lexical ranking", correlationId);
        } catch (RuntimeException ex) {
            log.warn("[{}] Semantic search unavailable, using lexical fallback: {}", correlationId, ex.getMessage());
        }

        return buildLexicalFallbackResponse(request, topK, maxTokens, startTime, correlationId);
    }

    private SearchResponse buildSemanticResponse(
            String userId,
            List<Object[]> rawResults,
            int maxTokens,
            long startTime,
            String correlationId) {
        List<SearchResponse.ChunkResult> chunks = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int tokenCount = 0;

        for (Object[] row : rawResults) {
            MemoryChunk chunk = (MemoryChunk) row[0];
            double similarity = ((Number) row[1]).doubleValue();
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
            if (contextBuilder.length() > 0) {
                contextBuilder.append("\n---\n");
            }
            contextBuilder.append(chunk.getChunkText());
            tokenCount += chunkTokens;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        long totalChunks = chunkRepository.countByUserId(userId);
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

    private SearchResponse buildLexicalFallbackResponse(
            SearchRequest request,
            int topK,
            int maxTokens,
            long startTime,
            String correlationId) {
        Set<String> queryTerms = expandQueryTerms(request.getQuery());
        String normalizedQuery = normalizeText(request.getQuery());

        List<MemoryChunk> userChunks = chunkRepository.findByUserIdOrderByCreatedAtDesc(request.getUserId());
        List<SessionSummary> summaries = summaryRepository.findByUserIdOrderByUpdatedAtDesc(request.getUserId());

        List<RankedMemoryItem> rankedItems = new ArrayList<>();
        for (MemoryChunk chunk : userChunks) {
            double score = lexicalScore(chunk.getChunkText(), queryTerms, normalizedQuery) + recencyBoost(chunk.getCreatedAt());
            if (score > 0) {
                rankedItems.add(new RankedMemoryItem(chunk.getId(), chunk.getChunkText(), score, chunk.getCreatedAt()));
            }
        }
        for (SessionSummary summary : summaries) {
            String text = "Session summary: " + summary.getSummaryText();
            double score = lexicalScore(summary.getSummaryText(), queryTerms, normalizedQuery)
                    + topicBoost(summary.getKeyTopics(), queryTerms)
                    + recencyBoost(summary.getUpdatedAt());
            if (score > 0) {
                rankedItems.add(new RankedMemoryItem(summary.getId(), text, score, summary.getUpdatedAt()));
            }
        }

        rankedItems.sort(Comparator.comparingDouble(RankedMemoryItem::score).reversed()
                .thenComparing(RankedMemoryItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (rankedItems.size() > topK) {
            rankedItems = new ArrayList<>(rankedItems.subList(0, topK));
        }

        List<SearchResponse.ChunkResult> chunks = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int tokenCount = 0;
        for (RankedMemoryItem item : rankedItems) {
            int candidateTokens = chunkingService.estimateTokens(item.text());
            if (tokenCount + candidateTokens > maxTokens) {
                break;
            }
            chunks.add(SearchResponse.ChunkResult.builder()
                    .id(item.id())
                    .text(item.text())
                    .similarity(roundSimilarity(item.score()))
                    .createdAt(item.createdAt())
                    .build());
            if (contextBuilder.length() > 0) {
                contextBuilder.append("\n---\n");
            }
            contextBuilder.append(item.text());
            tokenCount += candidateTokens;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        int totalCandidates = userChunks.size() + summaries.size();
        log.info("[{}] Lexical search complete: {} results in {}ms (candidates: {})",
                correlationId, chunks.size(), elapsed, totalCandidates);

        return SearchResponse.builder()
                .chunks(chunks)
                .contextText(contextBuilder.toString())
                .estimatedTokens(tokenCount)
                .totalChunksSearched(totalCandidates)
                .processingTimeMs(elapsed)
                .build();
    }

    /**
     * Create or update session summary using deterministic local enrichment.
     */
    @Transactional
    public void summarizeSession(SummarizeRequest request, String correlationId) {
        log.info("[{}] Summarizing session={} for user={}",
                correlationId, request.getSessionId(), request.getUserId());

        // Get messages for session
        List<ConversationMessage> messages = messageRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(request.getUserId(), request.getSessionId());

        if (messages.isEmpty()) {
            log.warn("[{}] No messages found for session", correlationId);
            return;
        }

        String[] topics = extractTopics(messages);
        String summaryText = createDeterministicSummary(messages, topics);

        SessionSummary summary = summaryRepository.findBySessionIdAndUserId(request.getSessionId(), request.getUserId())
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
            memoryIngestService.ingest(request, correlationId);
        } catch (RuntimeException e) {
            log.error("[{}] Async ingest failed: {}", correlationId, e.getMessage(), e);
        }
    }

    // Helper methods

    private String createDeterministicSummary(List<ConversationMessage> messages, String[] topics) {
        long userMessages = messages.stream().filter(m -> m.getRole() == ConversationMessage.MessageRole.user).count();
        long assistantMessages = messages.stream().filter(m -> m.getRole() == ConversationMessage.MessageRole.assistant).count();
        String firstUserMessage = messages.stream()
                .filter(m -> m.getRole() == ConversationMessage.MessageRole.user)
                .map(ConversationMessage::getContent)
                .findFirst()
                .map(content -> truncate(content, 96))
                .orElse("");
        String lastAssistantMessage = messages.stream()
                .filter(m -> m.getRole() == ConversationMessage.MessageRole.assistant)
                .reduce((first, second) -> second)
                .map(ConversationMessage::getContent)
                .map(content -> truncate(content, 96))
                .orElse("");

        StringBuilder summary = new StringBuilder();
        summary.append("Session with ").append(messages.size()).append(" messages");
        summary.append(" (user: ").append(userMessages).append(", assistant: ").append(assistantMessages).append(").");
        if (topics.length > 0) {
            summary.append(" Topics: ").append(String.join(", ", topics)).append(".");
        }
        if (!firstUserMessage.isBlank()) {
            summary.append(" User asked: \"").append(firstUserMessage).append("\".");
        }
        if (!lastAssistantMessage.isBlank()) {
            summary.append(" Assistant replied: \"").append(lastAssistantMessage).append("\".");
        }
        return summary.toString();
    }

    private String[] extractTopics(List<ConversationMessage> messages) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        Map<String, Long> keywordFrequency = new HashMap<>();

        for (ConversationMessage message : messages) {
            String normalized = normalizeText(message.getContent());
            for (Map.Entry<String, List<String>> entry : TOPIC_ALIASES.entrySet()) {
                boolean matches = entry.getValue().stream().anyMatch(normalized::contains);
                if (matches) {
                    topics.add(entry.getKey());
                }
            }
            for (String token : tokenize(message.getContent())) {
                keywordFrequency.merge(token, 1L, Long::sum);
            }
        }

        keywordFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .filter(token -> topics.stream().noneMatch(token::equals))
                .limit(Math.max(0, 5 - topics.size()))
                .forEach(topics::add);

        return topics.stream().limit(5).toArray(String[]::new);
    }

    private Set<String> expandQueryTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>(tokenize(query));
        String normalizedQuery = normalizeText(query);
        for (Map.Entry<String, List<String>> entry : TOPIC_ALIASES.entrySet()) {
            boolean matches = entry.getValue().stream().anyMatch(normalizedQuery::contains);
            if (matches) {
                terms.add(entry.getKey());
            }
        }
        if (terms.isEmpty()) {
            terms.add(normalizedQuery);
        }
        return terms;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return TOKEN_SPLIT_PATTERN.splitAsStream(normalizeText(text))
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .filter(token -> !STOP_WORDS.contains(token))
                .toList();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private double lexicalScore(String text, Set<String> queryTerms, String normalizedQuery) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        String normalizedText = normalizeText(text);
        Set<String> textTerms = new HashSet<>(tokenize(text));
        long matches = queryTerms.stream()
                .filter(term -> textTerms.contains(term) || normalizedText.contains(term))
                .count();
        if (matches == 0) {
            return 0.0;
        }
        double score = (double) matches / queryTerms.size();
        if (!normalizedQuery.isBlank() && normalizedText.contains(normalizedQuery)) {
            score += 0.2;
        }
        return Math.min(1.0, score);
    }

    private double topicBoost(String[] keyTopics, Set<String> queryTerms) {
        if (keyTopics == null || keyTopics.length == 0) {
            return 0.0;
        }
        Set<String> summaryTopics = Arrays.stream(keyTopics)
                .filter(Objects::nonNull)
                .map(topic -> topic.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        long matches = queryTerms.stream().filter(summaryTopics::contains).count();
        if (matches == 0) {
            return 0.0;
        }
        return Math.min(0.35, (double) matches / queryTerms.size());
    }

    private double recencyBoost(OffsetDateTime createdAt) {
        if (createdAt == null) {
            return 0.0;
        }
        long days = Math.max(0, Duration.between(createdAt, OffsetDateTime.now(clock)).toDays());
        return 0.1 / (1 + days);
    }

    private double roundSimilarity(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private record RankedMemoryItem(UUID id, String text, double score, OffsetDateTime createdAt) {
    }
}
