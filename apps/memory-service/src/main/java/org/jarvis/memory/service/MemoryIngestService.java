package org.jarvis.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.dto.IngestRequest;
import org.jarvis.memory.entity.ConversationMessage;
import org.jarvis.memory.entity.MemoryChunk;
import org.jarvis.memory.repository.ConversationMessageRepository;
import org.jarvis.memory.repository.MemoryChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryIngestService {

    private final ConversationMessageRepository messageRepository;
    private final MemoryChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final ChunkingService chunkingService;

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

    private void createChunks(String userId, List<UUID> messageIds,
            List<IngestRequest.MessageDto> messages, String correlationId) {
        List<String> texts = messages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.toList());

        List<String> chunks = chunkingService.chunkConversation(texts);

        if (chunks.isEmpty()) {
            // The chunker drops text below its minimum length. That silently
            // made short facts ("запомни: код = X") unrecallable — ingest
            // returned status:ok yet nothing was embedded. Fall back to storing
            // the raw joined text as a single chunk so short memories still work.
            String joined = String.join("\n", texts).trim();
            if (joined.isBlank()) {
                log.warn("[{}] Ingest produced no chunks and no content — nothing stored", correlationId);
                return;
            }
            log.warn("[{}] Chunker returned 0 chunks (text below threshold); "
                    + "storing 1 fallback chunk so the memory stays recallable", correlationId);
            chunks = List.of(joined);
        }

        log.debug("[{}] Created {} chunks", correlationId, chunks.size());

        List<List<Float>> embeddings = embeddingClient.embedBatch(chunks, correlationId);
        UUID[] messageIdArray = messageIds.toArray(new UUID[0]);

        for (int i = 0; i < chunks.size(); i++) {
            MemoryChunk chunk = MemoryChunk.builder()
                    .userId(userId)
                    .sourceMessageIds(messageIdArray)
                    .chunkText(chunks.get(i))
                    .embedding(MemoryChunk.toPrimitiveArray(embeddings.get(i)))
                    .build();

            chunkRepository.save(chunk);
        }

        log.debug("[{}] Saved {} chunks with embeddings", correlationId, chunks.size());
    }

    private Map<String, Object> mergeMaps(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> result = new HashMap<>();
        if (a != null) {
            result.putAll(a);
        }
        if (b != null) {
            result.putAll(b);
        }
        return result;
    }
}
