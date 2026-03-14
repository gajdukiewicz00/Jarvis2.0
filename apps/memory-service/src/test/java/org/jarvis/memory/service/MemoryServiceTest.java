package org.jarvis.memory.service;

import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.jarvis.memory.dto.SummarizeRequest;
import org.jarvis.memory.entity.ConversationMessage;
import org.jarvis.memory.entity.MemoryChunk;
import org.jarvis.memory.entity.SessionSummary;
import org.jarvis.memory.repository.ConversationMessageRepository;
import org.jarvis.memory.repository.MemoryChunkRepository;
import org.jarvis.memory.repository.SessionSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private ConversationMessageRepository messageRepository;

    @Mock
    private MemoryChunkRepository chunkRepository;

    @Mock
    private SessionSummaryRepository summaryRepository;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private ChunkingService chunkingService;

    @Mock
    private MemoryIngestService memoryIngestService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(
                messageRepository,
                chunkRepository,
                summaryRepository,
                embeddingClient,
                chunkingService,
                memoryIngestService,
                clock);
        ReflectionTestUtils.setField(memoryService, "defaultTopK", 5);
        ReflectionTestUtils.setField(memoryService, "defaultMaxTokens", 600);
        ReflectionTestUtils.setField(memoryService, "defaultMinSimilarity", 0.5d);
    }

    @Test
    void summarizeSessionBuildsDeterministicTopicAwareSummaryForScopedMessages() {
        when(messageRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc("user-123", "session-9"))
                .thenReturn(List.of(
                        message("user", "Set a timer for tea and turn on the living room lights."),
                        message("assistant", "I can help with timers and smart-home actions."),
                        message("user", "Also play relaxing music later.")));
        when(summaryRepository.findBySessionIdAndUserId("session-9", "user-123")).thenReturn(Optional.empty());

        memoryService.summarizeSession(new SummarizeRequest("session-9", "user-123", false), "corr-summary");

        ArgumentCaptor<SessionSummary> captor = ArgumentCaptor.forClass(SessionSummary.class);
        verify(summaryRepository).save(captor.capture());
        SessionSummary saved = captor.getValue();

        assertEquals("session-9", saved.getSessionId());
        assertEquals("user-123", saved.getUserId());
        assertEquals(3, saved.getMessageCount());
        assertTrue(List.of(saved.getKeyTopics()).contains("timer"));
        assertTrue(List.of(saved.getKeyTopics()).contains("smart-home"));
        assertTrue(List.of(saved.getKeyTopics()).contains("music"));
        assertTrue(saved.getSummaryText().contains("Topics:"));
    }

    @Test
    void searchFallsBackToLexicalRankingWhenEmbeddingLookupFails() {
        when(embeddingClient.embed(eq("lights timer"), eq("corr-search")))
                .thenThrow(new RuntimeException("embedding unavailable"));

        MemoryChunk relevantChunk = MemoryChunk.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .chunkText("User asked about a kitchen timer and hallway lights.")
                .createdAt(OffsetDateTime.parse("2026-03-13T10:00:00Z"))
                .metadata(Map.of("sessionId", "s-1"))
                .build();
        MemoryChunk irrelevantChunk = MemoryChunk.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .chunkText("Conversation about grocery shopping and recipes.")
                .createdAt(OffsetDateTime.parse("2026-03-11T10:00:00Z"))
                .metadata(Map.of("sessionId", "s-2"))
                .build();
        SessionSummary summary = SessionSummary.builder()
                .id(UUID.randomUUID())
                .sessionId("s-9")
                .userId("user-123")
                .summaryText("Topics: smart-home, timer. User discussed lights automation.")
                .keyTopics(new String[]{"smart-home", "timer"})
                .updatedAt(OffsetDateTime.parse("2026-03-13T11:30:00Z"))
                .build();

        when(chunkRepository.findByUserIdOrderByCreatedAtDesc("user-123"))
                .thenReturn(List.of(relevantChunk, irrelevantChunk));
        when(summaryRepository.findByUserIdOrderByUpdatedAtDesc("user-123"))
                .thenReturn(List.of(summary));
        when(chunkingService.estimateTokens(any(String.class))).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return Math.max(1, text.length() / 4);
        });

        SearchResponse response = memoryService.search(SearchRequest.builder()
                .userId("user-123")
                .query("lights timer")
                .topK(3)
                .maxTokens(300)
                .build(), "corr-search");

        assertEquals(3, response.getTotalChunksSearched());
        assertTrue(response.getChunks().size() >= 2);
        assertTrue(response.getChunks().get(0).getText().toLowerCase().contains("timer"));
        assertTrue(response.getContextText().toLowerCase().contains("lights"));
    }

    private ConversationMessage message(String role, String content) {
        return ConversationMessage.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .sessionId("session-9")
                .role(ConversationMessage.MessageRole.valueOf(role))
                .content(content)
                .createdAt(OffsetDateTime.now(clock))
                .build();
    }
}
