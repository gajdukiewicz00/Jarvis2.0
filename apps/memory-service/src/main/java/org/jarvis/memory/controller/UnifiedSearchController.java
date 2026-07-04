package org.jarvis.memory.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.jarvis.memory.obsidian.MemoryNoteEntity;
import org.jarvis.memory.obsidian.MemoryNoteService;
import org.jarvis.memory.service.MemoryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unified memory search across BOTH stores:
 *   - chunk-store (pgvector semantic) — conversations + screen-context observations;
 *   - note-store (pgvector semantic-first, keyword fallback) — Obsidian-mirrored
 *     notes + explicit memory notes. {@code noteSearchMode} reports which ran.
 *
 * Each result carries {@code source} (conversation | obsidian | memory), a score
 * where available, timestamps, and the Obsidian file path for note hits.
 * Degrades gracefully: if either store fails, the other still returns.
 */
@Slf4j
@RestController
@RequestMapping({"/memory/search", "/api/v1/memory/search"})
@RequiredArgsConstructor
public class UnifiedSearchController {

    private final MemoryService memoryService;
    private final MemoryNoteService noteService;

    @PostMapping("/unified")
    public Map<String, Object> unified(
            @RequestBody SearchRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId != null && !userId.isBlank()) {
            request.setUserId(userId);
        }
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        int topK = Math.max(1, request.getTopK());
        List<Map<String, Object>> results = new ArrayList<>();
        String retrievalMode = "none";

        // 1) Semantic chunk-store (conversations + screen-context)
        try {
            SearchResponse chunks = memoryService.search(request, corrId);
            retrievalMode = chunks.getRetrievalMode();
            if (chunks.getChunks() != null) {
                for (SearchResponse.ChunkResult c : chunks.getChunks()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", "conversation");
                    m.put("id", String.valueOf(c.getId()));
                    m.put("text", c.getText());
                    m.put("score", c.getSimilarity());
                    m.put("createdAt", String.valueOf(c.getCreatedAt()));
                    results.add(m);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[{}] unified search: chunk-store failed: {}", corrId, e.getMessage());
        }

        // 2) Note-store (Obsidian + memory notes): semantic-first, keyword fallback
        String noteMode = "none";
        try {
            MemoryNoteService.NoteSearchResult nr = noteService.searchUnified(request.getQuery(), topK);
            noteMode = nr.mode();
            for (MemoryNoteEntity n : nr.notes()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", n.getVaultRelativePath() != null ? "obsidian" : "memory");
                m.put("id", n.getMemoryId());
                m.put("title", n.getTitle());
                m.put("path", n.getVaultRelativePath());
                m.put("category", String.valueOf(n.getCategory()));
                String body = n.getBody() == null ? "" : n.getBody();
                m.put("snippet", body.length() > 160 ? body.substring(0, 160) : body);
                m.put("createdAt", String.valueOf(n.getCreatedAt()));
                results.add(m);
            }
        } catch (RuntimeException e) {
            log.warn("[{}] unified search: note-store failed: {}", corrId, e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", request.getQuery());
        out.put("count", results.size());
        out.put("retrievalMode", retrievalMode);
        out.put("noteSearchMode", noteMode);
        out.put("results", results);
        return out;
    }
}
