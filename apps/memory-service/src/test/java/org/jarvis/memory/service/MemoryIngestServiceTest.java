package org.jarvis.memory.service;

import org.jarvis.memory.dto.IngestRequest;
import org.jarvis.memory.entity.ConversationMessage;
import org.jarvis.memory.entity.MemoryChunk;
import org.jarvis.memory.repository.ConversationMessageRepository;
import org.jarvis.memory.repository.MemoryChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain-mock happy-path coverage for {@link MemoryIngestService} —
 * complements {@code MemoryIngestServiceTransactionTest}, which only
 * exercises the rollback-on-failure path with a real H2-backed
 * transaction manager.
 */
class MemoryIngestServiceTest {

    private ConversationMessageRepository messageRepository;
    private MemoryChunkRepository chunkRepository;
    private EmbeddingClient embeddingClient;
    private ChunkingService chunkingService;
    private MemoryIngestService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ConversationMessageRepository.class);
        chunkRepository = mock(MemoryChunkRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        chunkingService = mock(ChunkingService.class);
        service = new MemoryIngestService(messageRepository, chunkRepository, embeddingClient, chunkingService);

        when(messageRepository.save(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage msg = invocation.getArgument(0);
            msg.setId(UUID.randomUUID());
            return msg;
        });
    }

    private IngestRequest.MessageDto message(String role, String content, Map<String, Object> metadata) {
        return IngestRequest.MessageDto.builder().role(role).content(content).metadata(metadata).build();
    }

    @Test
    void ingestSavesEachMessageAndSkipsChunkingWhenNotRequested() {
        IngestRequest request = IngestRequest.builder()
                .userId("user-1")
                .sessionId("session-1")
                .createChunks(false)
                .messages(List.of(
                        message("user", "hello", null),
                        message("assistant", "hi there", null)))
                .build();

        service.ingest(request, "corr-1");

        ArgumentCaptor<ConversationMessage> captor = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ConversationMessage::getContent)
                .containsExactly("hello", "hi there");
        assertThat(captor.getAllValues()).extracting(ConversationMessage::getRole)
                .containsExactly(ConversationMessage.MessageRole.user, ConversationMessage.MessageRole.assistant);
        verify(chunkingService, never()).chunkConversation(anyList());
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void ingestMergesRequestAndMessageMetadataWithMessageWinningOnConflict() {
        IngestRequest request = IngestRequest.builder()
                .userId("user-1")
                .sessionId("session-1")
                .createChunks(false)
                .metadata(Map.of("source", "voice", "shared", "request"))
                .messages(List.of(message("user", "hi", Map.of("shared", "message", "extra", "yes"))))
                .build();

        service.ingest(request, "corr-2");

        ArgumentCaptor<ConversationMessage> captor = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messageRepository).save(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertThat(metadata).containsEntry("source", "voice");
        assertThat(metadata).containsEntry("extra", "yes");
        assertThat(metadata).containsEntry("shared", "message"); // per-message metadata wins
    }

    @Test
    void ingestHandlesNullMetadataOnBothSides() {
        IngestRequest request = IngestRequest.builder()
                .userId("user-1")
                .sessionId("session-1")
                .createChunks(false)
                .metadata(null)
                .messages(List.of(message("user", "hi", null)))
                .build();

        service.ingest(request, "corr-3");

        ArgumentCaptor<ConversationMessage> captor = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getMetadata()).isEmpty();
    }

    @Test
    void ingestCreatesChunksAndEmbeddingsWhenChunkerReturnsResults() {
        IngestRequest request = IngestRequest.builder()
                .userId("user-2")
                .sessionId("session-2")
                .createChunks(true)
                .messages(List.of(message("user", "remember this fact", null)))
                .build();
        when(chunkingService.chunkConversation(anyList())).thenReturn(List.of("chunk one", "chunk two"));
        when(embeddingClient.embedBatch(List.of("chunk one", "chunk two"), "corr-4"))
                .thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.3f, 0.4f)));

        service.ingest(request, "corr-4");

        ArgumentCaptor<MemoryChunk> captor = ArgumentCaptor.forClass(MemoryChunk.class);
        verify(chunkRepository, times(2)).save(captor.capture());
        List<MemoryChunk> saved = captor.getAllValues();
        assertThat(saved.get(0).getChunkText()).isEqualTo("chunk one");
        assertThat(saved.get(0).getEmbedding()).containsExactly(0.1f, 0.2f);
        assertThat(saved.get(0).getUserId()).isEqualTo("user-2");
        assertThat(saved.get(0).getSourceMessageIds()).hasSize(1);
        assertThat(saved.get(1).getChunkText()).isEqualTo("chunk two");
        assertThat(saved.get(1).getEmbedding()).containsExactly(0.3f, 0.4f);
    }

    @Test
    void ingestFallsBackToSingleRawChunkWhenChunkerDropsShortText() {
        IngestRequest request = IngestRequest.builder()
                .userId("user-3")
                .sessionId("session-3")
                .createChunks(true)
                .messages(List.of(message("user", "short code X", null)))
                .build();
        when(chunkingService.chunkConversation(anyList())).thenReturn(List.of());
        when(embeddingClient.embedBatch(anyList(), anyString()))
                .thenReturn(List.of(List.of(0.9f)));

        service.ingest(request, "corr-5");

        ArgumentCaptor<MemoryChunk> captor = ArgumentCaptor.forClass(MemoryChunk.class);
        verify(chunkRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getChunkText()).isEqualTo("user: short code X");
    }

    @Test
    void ingestStoresNothingWhenChunkerDropsEverythingAndJoinedTextIsBlank() {
        // No messages at all: the raw-text-join fallback then joins zero
        // strings, producing a blank string — the one case where even the
        // fallback chunk is skipped.
        IngestRequest request = IngestRequest.builder()
                .userId("user-4")
                .sessionId("session-4")
                .createChunks(true)
                .messages(List.of())
                .build();
        when(chunkingService.chunkConversation(anyList())).thenReturn(List.of());

        service.ingest(request, "corr-6");

        verify(messageRepository, never()).save(any());
        verify(chunkRepository, never()).save(any());
        verify(embeddingClient, never()).embedBatch(anyList(), anyString());
    }
}
