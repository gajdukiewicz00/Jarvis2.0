package org.jarvis.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChunkingService
 */
class ChunkingServiceTest {

    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        // Default settings: size=500, overlap=50, minSize=100
        chunkingService = new ChunkingService(500, 50, 100);
    }

    @Test
    void chunkText_shortText_returnsEmpty() {
        String text = "Too short";
        List<String> chunks = chunkingService.chunkText(text);
        assertTrue(chunks.isEmpty(), "Text shorter than minSize should return empty");
    }

    @Test
    void chunkText_mediumText_returnsSingleChunk() {
        String text = "Это текст средней длины, который достаточно большой для одного чанка, " +
                "но не настолько большой, чтобы требовать разбиения на несколько частей. " +
                "Тем не менее, он должен быть достаточно длинным.";
        
        List<String> chunks = chunkingService.chunkText(text);
        
        assertEquals(1, chunks.size(), "Medium text should produce single chunk");
        assertEquals(text, chunks.get(0));
    }

    @Test
    void chunkText_longText_splitsIntoMultipleChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("Это предложение номер ").append(i).append(". ");
            sb.append("Оно достаточно длинное чтобы занимать место. ");
        }
        String text = sb.toString();

        List<String> chunks = chunkingService.chunkText(text);

        assertTrue(chunks.size() > 1, "Long text should produce multiple chunks");
        
        // Each chunk should be within size limit
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 550, "Chunk should not exceed size limit significantly");
            assertTrue(chunk.length() >= 100, "Chunk should meet minimum size");
        }
    }

    @Test
    void chunkText_nullText_returnsEmpty() {
        List<String> chunks = chunkingService.chunkText(null);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkText_emptyText_returnsEmpty() {
        List<String> chunks = chunkingService.chunkText("");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkConversation_multipleMessages_chunksCorrectly() {
        List<String> messages = List.of(
                "user: Привет, как дела?",
                "assistant: Привет! У меня всё отлично, спасибо что спросил.",
                "user: Можешь напомнить мне о встрече завтра в 10?",
                "assistant: Конечно! Создаю напоминание о встрече на завтра в 10:00."
        );

        List<String> chunks = chunkingService.chunkConversation(messages);

        assertFalse(chunks.isEmpty(), "Conversation should produce at least one chunk");
        
        // Combined text should be in chunks
        String combined = String.join(" ", messages);
        String chunksText = String.join(" ", chunks);
        
        // The essence of the conversation should be preserved
        assertTrue(chunksText.contains("Привет") || chunksText.contains("напоминание"));
    }

    @Test
    void estimateTokens_variousTexts_estimatesCorrectly() {
        // ~4 chars per token
        assertEquals(0, chunkingService.estimateTokens((String) null));
        assertEquals(0, chunkingService.estimateTokens(""));
        assertEquals(1, chunkingService.estimateTokens("Привет")); // 6 chars / 4
        assertEquals(25, chunkingService.estimateTokens("a".repeat(100))); // 100 / 4
    }

    @Test
    void chunkText_preservesSentences() {
        String text = "Первое предложение. " +
                "Второе предложение. " +
                "Третье предложение. " +
                "Четвёртое предложение. " +
                "Пятое предложение.";

        // Create service with smaller chunk size to force splits
        ChunkingService smallChunkService = new ChunkingService(100, 20, 50);
        List<String> chunks = smallChunkService.chunkText(text);

        // Each chunk should end with complete sentence (period)
        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                // Should end with period, question mark, or exclamation
                assertTrue(
                        trimmed.endsWith(".") || trimmed.endsWith("...") || 
                        trimmed.endsWith("?") || trimmed.endsWith("!"),
                        "Chunk should end with sentence boundary: " + trimmed
                );
            }
        }
    }
}


