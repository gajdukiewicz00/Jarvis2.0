package org.jarvis.memory.controller;

import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.jarvis.memory.obsidian.MemoryCategory;
import org.jarvis.memory.obsidian.MemoryNoteEntity;
import org.jarvis.memory.obsidian.MemoryNoteService;
import org.jarvis.memory.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedSearchControllerTest {

    private MemoryService memoryService;
    private MemoryNoteService noteService;
    private UnifiedSearchController controller;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        noteService = mock(MemoryNoteService.class);
        controller = new UnifiedSearchController(memoryService, noteService);
    }

    private MemoryNoteEntity note(String id, String title, String path) {
        return MemoryNoteEntity.builder()
                .memoryId(id)
                .category(MemoryCategory.PROJECTS.name())
                .title(title)
                .body("some body text that is reasonably short")
                .vaultRelativePath(path)
                .createdAt(Instant.parse("2026-05-01T10:00:00Z"))
                .build();
    }

    @Test
    void unifiedMergesChunkAndNoteResultsAndReportsModes() {
        SearchRequest request = SearchRequest.builder().query("hello").topK(5).build();
        UUID chunkId = UUID.randomUUID();
        SearchResponse chunkResponse = SearchResponse.builder()
                .retrievalMode("semantic")
                .chunks(List.of(SearchResponse.ChunkResult.builder()
                        .id(chunkId)
                        .text("chunk text")
                        .similarity(0.8)
                        .build()))
                .build();
        when(memoryService.search(eq(request), any())).thenReturn(chunkResponse);
        when(noteService.searchUnified(eq("hello"), eq(5), eq(true), eq(true)))
                .thenReturn(new MemoryNoteService.NoteSearchResult(
                        List.of(note("mem-1", "Obsidian note", "03_Memory/Projects/note.md"),
                                note("mem-2", "Bare note", null)),
                        "semantic"));

        Map<String, Object> result = controller.unified(request, null);

        assertThat(result.get("query")).isEqualTo("hello");
        assertThat(result.get("retrievalMode")).isEqualTo("semantic");
        assertThat(result.get("noteSearchMode")).isEqualTo("semantic");
        assertThat(result.get("count")).isEqualTo(3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("source")).isEqualTo("conversation");
        assertThat(results.get(0).get("id")).isEqualTo(String.valueOf(chunkId));
        assertThat(results.get(1).get("source")).isEqualTo("obsidian");
        assertThat(results.get(1).get("path")).isEqualTo("03_Memory/Projects/note.md");
        assertThat(results.get(2).get("source")).isEqualTo("memory");
        assertThat(results.get(2).get("path")).isNull();
    }

    @Test
    void unifiedUsesXUserIdHeaderWhenPresent() {
        SearchRequest request = SearchRequest.builder().query("q").topK(1).build();
        when(memoryService.search(any(), any())).thenReturn(
                SearchResponse.builder().retrievalMode("semantic").chunks(List.of()).build());
        when(noteService.searchUnified(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new MemoryNoteService.NoteSearchResult(List.of(), "keyword"));

        controller.unified(request, "header-user");

        assertThat(request.getUserId()).isEqualTo("header-user");
    }

    @Test
    void unifiedIgnoresBlankUserIdHeader() {
        SearchRequest request = SearchRequest.builder().query("q").topK(1).build();
        when(memoryService.search(any(), any())).thenReturn(
                SearchResponse.builder().retrievalMode("semantic").chunks(List.of()).build());
        when(noteService.searchUnified(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new MemoryNoteService.NoteSearchResult(List.of(), "keyword"));

        controller.unified(request, "   ");

        assertThat(request.getUserId()).isNull();
    }

    @Test
    void unifiedClampsTopKToAtLeastOne() {
        SearchRequest request = SearchRequest.builder().query("q").topK(0).build();
        when(memoryService.search(any(), any())).thenReturn(
                SearchResponse.builder().retrievalMode("semantic").chunks(List.of()).build());
        when(noteService.searchUnified(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new MemoryNoteService.NoteSearchResult(List.of(), "keyword"));

        controller.unified(request, null);

        verify(noteService).searchUnified(eq("q"), eq(1), eq(true), eq(true));
    }

    @Test
    void unifiedDegradesGracefullyWhenChunkStoreThrows() {
        SearchRequest request = SearchRequest.builder().query("q").topK(3).build();
        when(memoryService.search(any(), any())).thenThrow(new RuntimeException("chunk store down"));
        when(noteService.searchUnified(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new MemoryNoteService.NoteSearchResult(List.of(), "keyword"));

        Map<String, Object> result = controller.unified(request, null);

        assertThat(result.get("retrievalMode")).isEqualTo("none");
        assertThat(result.get("noteSearchMode")).isEqualTo("keyword");
        assertThat(result.get("count")).isEqualTo(0);
    }

    @Test
    void unifiedDegradesGracefullyWhenNoteStoreThrows() {
        SearchRequest request = SearchRequest.builder().query("q").topK(3).build();
        when(memoryService.search(any(), any())).thenReturn(
                SearchResponse.builder().retrievalMode("semantic").chunks(List.of()).build());
        when(noteService.searchUnified(any(), anyInt(), anyBoolean(), anyBoolean())).thenThrow(new RuntimeException("note store down"));

        Map<String, Object> result = controller.unified(request, null);

        assertThat(result.get("retrievalMode")).isEqualTo("semantic");
        assertThat(result.get("noteSearchMode")).isEqualTo("none");
        assertThat(result.get("count")).isEqualTo(0);
    }

    @Test
    void unifiedTruncatesLongNoteBodyToSnippet() {
        SearchRequest request = SearchRequest.builder().query("q").topK(1).build();
        String longBody = "x".repeat(200);
        MemoryNoteEntity longNote = MemoryNoteEntity.builder()
                .memoryId("mem-3")
                .category(MemoryCategory.PROJECTS.name())
                .title("Long")
                .body(longBody)
                .createdAt(Instant.now())
                .build();
        when(memoryService.search(any(), any())).thenReturn(
                SearchResponse.builder().retrievalMode("semantic").chunks(List.of()).build());
        when(noteService.searchUnified(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new MemoryNoteService.NoteSearchResult(List.of(longNote), "keyword"));

        Map<String, Object> result = controller.unified(request, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(((String) results.get(0).get("snippet"))).hasSize(160);
    }

    @Test
    void unifiedHandlesNullNoteBodyAsEmptySnippet() {
        SearchRequest request = SearchRequest.builder().query("q").topK(1).build();
        MemoryNoteEntity noBody = MemoryNoteEntity.builder()
                .memoryId("mem-4")
                .category(MemoryCategory.PROJECTS.name())
                .title("NoBody")
                .body(null)
                .createdAt(Instant.now())
                .build();
        when(memoryService.search(any(), any())).thenReturn(
                SearchResponse.builder().retrievalMode("semantic").chunks(List.of()).build());
        when(noteService.searchUnified(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(new MemoryNoteService.NoteSearchResult(List.of(noBody), "keyword"));

        Map<String, Object> result = controller.unified(request, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results.get(0).get("snippet")).isEqualTo("");
    }
}
