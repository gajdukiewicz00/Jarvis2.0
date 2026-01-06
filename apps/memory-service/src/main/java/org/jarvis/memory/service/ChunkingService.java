package org.jarvis.memory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service for chunking text into smaller pieces for embedding.
 * 
 * Strategies:
 * 1. Split by sentence boundaries
 * 2. Respect max chunk size
 * 3. Add overlap between chunks for context continuity
 */
@Slf4j
@Service
public class ChunkingService {

    private final int chunkSize;
    private final int overlap;
    private final int minSize;

    // Pattern to split by sentence (handles ., !, ? followed by space or end)
    private static final Pattern SENTENCE_PATTERN = Pattern.compile("(?<=[.!?])\\s+");

    public ChunkingService(
            @Value("${memory.chunking.size:500}") int chunkSize,
            @Value("${memory.chunking.overlap:50}") int overlap,
            @Value("${memory.chunking.min-size:100}") int minSize) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.minSize = minSize;
        
        log.info("ChunkingService: size={}, overlap={}, minSize={}", chunkSize, overlap, minSize);
    }

    /**
     * Chunk a single text into smaller pieces
     */
    public List<String> chunkText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        text = text.trim();
        
        // If text is small enough, return as single chunk
        if (text.length() <= chunkSize) {
            return text.length() >= minSize ? List.of(text) : List.of();
        }

        List<String> chunks = new ArrayList<>();
        
        // Split into sentences first
        String[] sentences = SENTENCE_PATTERN.split(text);
        
        StringBuilder currentChunk = new StringBuilder();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;
            
            // If adding this sentence would exceed chunk size
            if (currentChunk.length() + sentence.length() + 1 > chunkSize) {
                // Save current chunk if it's big enough
                if (currentChunk.length() >= minSize) {
                    chunks.add(currentChunk.toString().trim());
                }
                
                // Start new chunk with overlap from previous
                String overlapText = getOverlapText(currentChunk.toString());
                currentChunk = new StringBuilder(overlapText);
            }
            
            // Add sentence to current chunk
            if (currentChunk.length() > 0 && !currentChunk.toString().endsWith(" ")) {
                currentChunk.append(" ");
            }
            currentChunk.append(sentence);
        }
        
        // Don't forget the last chunk
        if (currentChunk.length() >= minSize) {
            chunks.add(currentChunk.toString().trim());
        }
        
        // If no chunks were created (text is all one long sentence), force split
        if (chunks.isEmpty() && text.length() >= minSize) {
            return forceSplit(text);
        }
        
        return chunks;
    }

    /**
     * Chunk multiple texts (e.g., conversation messages)
     */
    public List<String> chunkConversation(List<String> messages) {
        // Combine messages with role markers
        StringBuilder combined = new StringBuilder();
        for (String msg : messages) {
            if (combined.length() > 0) {
                combined.append(" ");
            }
            combined.append(msg.trim());
        }
        
        return chunkText(combined.toString());
    }

    /**
     * Get overlap text from the end of a chunk
     */
    private String getOverlapText(String chunk) {
        if (chunk.length() <= overlap) {
            return chunk;
        }
        
        // Try to break at word boundary
        String tail = chunk.substring(chunk.length() - overlap);
        int spaceIdx = tail.indexOf(' ');
        if (spaceIdx > 0 && spaceIdx < overlap / 2) {
            return tail.substring(spaceIdx + 1);
        }
        
        return tail;
    }

    /**
     * Force split long text without sentence boundaries
     */
    private List<String> forceSplit(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            
            // Try to break at word boundary
            if (end < text.length()) {
                int spaceIdx = text.lastIndexOf(' ', end);
                if (spaceIdx > start + minSize) {
                    end = spaceIdx;
                }
            }
            
            String chunk = text.substring(start, end).trim();
            if (chunk.length() >= minSize) {
                chunks.add(chunk);
            }
            
            // Move start with overlap
            start = end - overlap;
            if (start <= 0 || start >= text.length() - minSize) {
                start = end;
            }
        }
        
        return chunks;
    }

    /**
     * Estimate token count (rough: ~4 chars per token for Russian/English)
     */
    public int estimateTokens(String text) {
        if (text == null) return 0;
        return text.length() / 4;
    }

    /**
     * Estimate tokens for multiple texts
     */
    public int estimateTokens(List<String> texts) {
        return texts.stream().mapToInt(this::estimateTokens).sum();
    }
}



