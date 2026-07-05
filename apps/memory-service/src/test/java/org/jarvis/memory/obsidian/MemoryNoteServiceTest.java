package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link MemoryNoteService#update(String, MemoryNoteRequest)} — the only
 * note-CRUD path with meaningful branching (partial-field apply, vault
 * re-write, re-embed). write()/get()/list()/exportAll() are thin repository
 * delegations already exercised indirectly via the controller test.
 */
class MemoryNoteServiceTest {

    private MemoryNoteRepository repository;
    private ObsidianVaultWriter vaultWriter;
    private ObsidianMarkdownRenderer renderer;
    private MemoryEmbeddingClient embeddingClient;
    private MemoryNoteService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        vaultWriter = mock(ObsidianVaultWriter.class);
        renderer = mock(ObsidianMarkdownRenderer.class);
        embeddingClient = mock(MemoryEmbeddingClient.class);
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> noopProvider =
                mock(ObjectProvider.class);
        when(noopProvider.getIfAvailable()).thenReturn(null);
        service = new MemoryNoteService(repository, vaultWriter, renderer, embeddingClient, noopProvider);
        when(repository.save(any(MemoryNoteEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MemoryNoteEntity active() {
        return MemoryNoteEntity.builder()
                .memoryId("mem-1")
                .category(MemoryCategory.PROJECTS.name())
                .title("old title")
                .summary("old summary")
                .body("old body")
                .vaultRelativePath("03_Memory/Projects/2026-05-01-old-title.md")
                .source("jarvis")
                .privacy("local-only")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.5"))
                .tags(new java.util.ArrayList<>(List.of("a")))
                .linkedEntities(new java.util.ArrayList<>())
                .frontmatter(new java.util.HashMap<>())
                .createdAt(Instant.parse("2026-05-01T10:00:00Z"))
                .updatedAt(Instant.parse("2026-05-01T10:00:00Z"))
                .build();
    }

    @Test
    void updateMissingNoteReturnsNull() {
        when(repository.findById("mem-x")).thenReturn(Optional.empty());

        MemoryNoteEntity result = service.update("mem-x",
                MemoryNoteRequest.builder().title("New title").build());

        assertThat(result).isNull();
        verify(vaultWriter, never()).write(any());
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    void updateAppliesOnlyProvidedFieldsAndReEmbeds() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));
        when(vaultWriter.write(n)).thenReturn("03_Memory/Projects/2026-05-01-new-title.md");
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f});

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("New title")
                .body("New body")
                .build();

        MemoryNoteEntity result = service.update("mem-1", request);

        assertThat(result).isSameAs(n);
        assertThat(result.getTitle()).isEqualTo("New title");
        assertThat(result.getBody()).isEqualTo("New body");
        // Fields absent from the request are left untouched.
        assertThat(result.getSummary()).isEqualTo("old summary");
        assertThat(result.getCategory()).isEqualTo(MemoryCategory.PROJECTS.name());
        assertThat(result.getTags()).containsExactly("a");
        assertThat(result.getVaultRelativePath())
                .isEqualTo("03_Memory/Projects/2026-05-01-new-title.md");
        assertThat(result.getEmbedding()).containsExactly(0.1f, 0.2f);
        verify(repository, times(2)).save(n);
    }

    @Test
    void updateAppliesCategoryAndTagsWhenProvided() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));
        when(vaultWriter.write(n)).thenReturn("03_Memory/Health/2026-05-01-old-title.md");
        when(embeddingClient.embed(any())).thenReturn(null);

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .category(MemoryCategory.HEALTH)
                .tags(List.of("b", "c"))
                .build();

        MemoryNoteEntity result = service.update("mem-1", request);

        assertThat(result.getCategory()).isEqualTo(MemoryCategory.HEALTH.name());
        assertThat(result.getTags()).containsExactly("b", "c");
        assertThat(result.getEmbedding()).isNull();
    }

    @Test
    void updateKeepsExistingVaultPathWhenVaultWriteFails() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));
        when(vaultWriter.write(n)).thenReturn(null);

        service.update("mem-1", MemoryNoteRequest.builder().title("Still same path").build());

        assertThat(n.getVaultRelativePath()).isEqualTo("03_Memory/Projects/2026-05-01-old-title.md");
    }

    @Test
    void updateBlankTitleIsIgnored() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));

        service.update("mem-1", MemoryNoteRequest.builder().title("   ").build());

        assertThat(n.getTitle()).isEqualTo("old title");
    }

    @Test
    void updateAppliesScopeWhenProvided() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));

        service.update("mem-1", MemoryNoteRequest.builder().scope(MemoryScope.FINANCE).build());

        assertThat(n.getScope()).isEqualTo(MemoryScope.FINANCE.name());
    }

    @Test
    void updateLeavesScopeUntouchedWhenAbsent() {
        MemoryNoteEntity n = active();
        n.setScope(MemoryScope.HEALTH.name());
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));

        service.update("mem-1", MemoryNoteRequest.builder().title("New title").build());

        assertThat(n.getScope()).isEqualTo(MemoryScope.HEALTH.name());
    }

    @Test
    void updateAppliesExplicitExpiresAt() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));
        Instant expiry = Instant.parse("2030-06-01T00:00:00Z");

        service.update("mem-1", MemoryNoteRequest.builder().expiresAt(expiry).build());

        assertThat(n.getExpiresAt()).isEqualTo(expiry);
    }
}
