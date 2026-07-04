package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the note-CRUD paths NOT exercised by {@link MemoryNoteServiceTest}
 * (which focuses solely on {@code update()}): {@code write()}'s create and
 * Obsidian-upsert branches, {@code get()}, {@code list()}, {@code exportAll()}
 * and the semantic/keyword branches of {@code searchUnified()}.
 */
@SuppressWarnings("unchecked")
class MemoryNoteServiceExtraTest {

    private MemoryNoteRepository repository;
    private ObsidianVaultWriter vaultWriter;
    private ObsidianMarkdownRenderer renderer;
    private MemoryEmbeddingClient embeddingClient;
    private MemoryNoteService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        vaultWriter = mock(ObsidianVaultWriter.class);
        renderer = mock(ObsidianMarkdownRenderer.class);
        embeddingClient = mock(MemoryEmbeddingClient.class);
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> noopProvider = mock(ObjectProvider.class);
        when(noopProvider.getIfAvailable()).thenReturn(null);
        service = new MemoryNoteService(repository, vaultWriter, renderer, embeddingClient, noopProvider);
        when(repository.save(any(MemoryNoteEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------------------------------------------------------------- write()

    @Test
    void writeRejectsNullRequest() {
        assertThatThrownBy(() -> service.write(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request is required");
    }

    @Test
    void writeRejectsBlankTitle() {
        MemoryNoteRequest request = MemoryNoteRequest.builder().title("   ").build();
        assertThatThrownBy(() -> service.write(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title is required");
    }

    @Test
    void writeCreatesNewNoteWithDefaultsWhenOptionalFieldsAbsent() {
        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("  My New Note  ")
                .build();
        when(vaultWriter.write(any())).thenReturn("03_Memory/Projects/2026-05-01-my-new-note.md");
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.5f});

        MemoryNoteEntity result = service.write(request);

        assertThat(result.getMemoryId()).startsWith("mem-");
        assertThat(result.getTitle()).isEqualTo("My New Note");
        assertThat(result.getCategory()).isEqualTo(MemoryCategory.PROJECTS.name());
        assertThat(result.getSource()).isEqualTo("jarvis");
        assertThat(result.getPrivacy()).isEqualTo("local-only");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getTags()).isEmpty();
        assertThat(result.getLinkedEntities()).isEmpty();
        assertThat(result.getVaultRelativePath()).isEqualTo("03_Memory/Projects/2026-05-01-my-new-note.md");
        assertThat(result.getEmbedding()).containsExactly(0.5f);

        Map<String, Object> fm = result.getFrontmatter();
        assertThat(fm.get("type")).isEqualTo("memory");
        assertThat(fm.get("memory_id")).isEqualTo(result.getMemoryId());
        assertThat(fm.get("source")).isEqualTo("jarvis");
        assertThat(fm.get("privacy")).isEqualTo("local-only");
        assertThat(fm.get("status")).isEqualTo("active");
        assertThat(fm).doesNotContainKey("confidence");
        assertThat(fm.get("vault_relative_path")).isEqualTo("03_Memory/Projects/2026-05-01-my-new-note.md");

        verify(repository, times(2)).save(any(MemoryNoteEntity.class));
    }

    @Test
    void writeHonoursExplicitCategorySourcePrivacyTagsAndConfidence() {
        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .memoryId("mem-explicit")
                .category(MemoryCategory.HEALTH)
                .title("Checkup")
                .summary("summary")
                .body("body")
                .source("manual")
                .privacy("shared")
                .confidence(new BigDecimal("0.75"))
                .tags(List.of("x", "y"))
                .linkedEntities(List.of("z"))
                .build();
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);

        MemoryNoteEntity result = service.write(request);

        assertThat(result.getMemoryId()).isEqualTo("mem-explicit");
        assertThat(result.getCategory()).isEqualTo(MemoryCategory.HEALTH.name());
        assertThat(result.getSource()).isEqualTo("manual");
        assertThat(result.getPrivacy()).isEqualTo("shared");
        assertThat(result.getTags()).containsExactly("x", "y");
        assertThat(result.getLinkedEntities()).containsExactly("z");
        assertThat(result.getVaultRelativePath()).isNull();
        assertThat(result.getEmbedding()).isNull();
        assertThat(result.getFrontmatter().get("confidence")).isEqualTo("0.75");
    }

    @Test
    void writeUpsertsExistingObsidianSourcedNoteInsteadOfCreatingDuplicate() {
        String source = "obsidian:03_Memory/Projects/existing.md";
        MemoryNoteEntity existing = MemoryNoteEntity.builder()
                .memoryId("mem-existing")
                .category(MemoryCategory.PROJECTS.name())
                .title("Old title")
                .source(source)
                .privacy("local-only")
                .status("ACTIVE")
                .tags(new java.util.ArrayList<>())
                .linkedEntities(new java.util.ArrayList<>())
                .frontmatter(new LinkedHashMap<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(repository.findFirstBySourceOrderByCreatedAtDesc(source)).thenReturn(Optional.of(existing));
        when(repository.findById("mem-existing")).thenReturn(Optional.of(existing));
        when(vaultWriter.write(existing)).thenReturn("03_Memory/Projects/existing.md");
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f});

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Reindexed title")
                .source(source)
                .build();

        MemoryNoteEntity result = service.write(request);

        assertThat(result.getMemoryId()).isEqualTo("mem-existing");
        assertThat(result.getTitle()).isEqualTo("Reindexed title");
        verify(repository, times(1)).findFirstBySourceOrderByCreatedAtDesc(source);
        verify(repository, never()).save(argThatIsNewNote());
    }

    private MemoryNoteEntity argThatIsNewNote() {
        return org.mockito.ArgumentMatchers.argThat(n -> n != null && !"mem-existing".equals(n.getMemoryId()));
    }

    // ------------------------------------------------------------------ get()

    @Test
    void getReturnsNoteWhenPresent() {
        MemoryNoteEntity entity = MemoryNoteEntity.builder().memoryId("mem-1").build();
        when(repository.findById("mem-1")).thenReturn(Optional.of(entity));

        assertThat(service.get("mem-1")).isSameAs(entity);
    }

    @Test
    void getReturnsNullWhenMissing() {
        when(repository.findById("mem-x")).thenReturn(Optional.empty());

        assertThat(service.get("mem-x")).isNull();
    }

    // ----------------------------------------------------------------- list()

    @Test
    void listClampsLimitToUpperBoundAndPassesCategory() {
        when(repository.search(eq(MemoryCategory.HEALTH.name()), eq("ACTIVE"), any()))
                .thenReturn(List.of());

        service.list(MemoryCategory.HEALTH, 10_000);

        verify(repository).search(eq(MemoryCategory.HEALTH.name()), eq("ACTIVE"),
                argThat(pageable -> pageable.getPageSize() == 500));
    }

    @Test
    void listClampsLimitToLowerBoundAndAllowsNullCategory() {
        when(repository.search(eq((String) null), eq("ACTIVE"), any())).thenReturn(List.of());

        service.list(null, -5);

        verify(repository).search(eq((String) null), eq("ACTIVE"),
                argThat(pageable -> pageable.getPageSize() == 1));
    }

    private static Pageable argThat(java.util.function.Predicate<Pageable> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }

    // ------------------------------------------------------------- exportAll()

    @Test
    void exportAllDelegatesToRepositoryWithActiveStatusAndCapAt500() {
        List<MemoryNoteEntity> all = List.of(MemoryNoteEntity.builder().memoryId("mem-1").build());
        when(repository.search(eq((String) null), eq("ACTIVE"), any())).thenReturn(all);

        assertThat(service.exportAll()).isEqualTo(all);
    }

    // -------------------------------------------------------- searchUnified()

    @Test
    void searchUnifiedReturnsSemanticResultsFromJdbcWhenEmbeddingSucceeds() {
        when(embeddingClient.embed("find health notes")).thenReturn(new float[]{0.1f, 0.2f});
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("memory_id", "mem-1");
        row1.put("title", "Title 1");
        row1.put("category", "HEALTH");
        row1.put("body", "Body 1");
        row1.put("vault_relative_path", "03_Memory/Health/note1.md");
        row1.put("created_at", Timestamp.from(Instant.parse("2026-05-01T00:00:00Z")));

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("memory_id", "mem-2");
        row2.put("title", "Title 2");
        row2.put("category", "PROJECTS");
        row2.put("body", null);
        row2.put("vault_relative_path", null);
        row2.put("created_at", Instant.parse("2026-05-02T00:00:00Z"));

        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of(row1, row2));

        MemoryNoteService.NoteSearchResult result = service.searchUnified("find health notes", 5);

        assertThat(result.mode()).isEqualTo("semantic");
        assertThat(result.notes()).hasSize(2);
        assertThat(result.notes().get(0).getMemoryId()).isEqualTo("mem-1");
        assertThat(result.notes().get(0).getCreatedAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(result.notes().get(1).getCreatedAt()).isEqualTo(Instant.parse("2026-05-02T00:00:00Z"));
        assertThat(result.notes().get(1).getBody()).isNull();
    }

    @Test
    void searchUnifiedFallsBackToKeywordWhenJdbcReturnsNoRows() {
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f});
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(List.of());
        List<MemoryNoteEntity> keywordResults = List.of(MemoryNoteEntity.builder().memoryId("mem-k").build());
        when(repository.searchByText(eq("query text"), any())).thenReturn(keywordResults);

        MemoryNoteService.NoteSearchResult result = service.searchUnified("query text", 3);

        assertThat(result.mode()).isEqualTo("keyword");
        assertThat(result.notes()).isEqualTo(keywordResults);
    }

    @Test
    void searchUnifiedFallsBackToKeywordWhenEmbeddingIsNull() {
        when(embeddingClient.embed(any())).thenReturn(null);
        List<MemoryNoteEntity> keywordResults = List.of();
        when(repository.searchByText(any(), any())).thenReturn(keywordResults);

        MemoryNoteService.NoteSearchResult result = service.searchUnified("query text", 3);

        assertThat(result.mode()).isEqualTo("keyword");
    }

    @Test
    void searchUnifiedFallsBackToKeywordWhenEmbeddingIsEmptyArray() {
        when(embeddingClient.embed(any())).thenReturn(new float[0]);
        when(repository.searchByText(any(), any())).thenReturn(List.of());

        MemoryNoteService.NoteSearchResult result = service.searchUnified("query text", 3);

        assertThat(result.mode()).isEqualTo("keyword");
    }

    @Test
    void searchUnifiedFallsBackToKeywordWhenJdbcTemplateUnavailable() {
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f});
        // jdbcTemplate field left null (never wired) — mirrors environments where
        // it wasn't injected.
        when(repository.searchByText(any(), any())).thenReturn(List.of());

        MemoryNoteService.NoteSearchResult result = service.searchUnified("query text", 3);

        assertThat(result.mode()).isEqualTo("keyword");
    }

    @Test
    void searchUnifiedFallsBackToKeywordWhenEmbeddingClientThrows() {
        when(embeddingClient.embed(any())).thenThrow(new RuntimeException("embedding-service down"));
        when(repository.searchByText(any(), any())).thenReturn(List.of());

        MemoryNoteService.NoteSearchResult result = service.searchUnified("query text", 3);

        assertThat(result.mode()).isEqualTo("keyword");
    }

    @Test
    void searchUnifiedClampsTopKIntoValidRange() {
        when(embeddingClient.embed(any())).thenReturn(null);
        when(repository.searchByText(any(), any())).thenReturn(List.of());

        service.searchUnified("q", 500);

        verify(repository).searchByText(eq("q"), argThat(p -> p.getPageSize() == 50));
    }
}
