package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Roadmap #11 — covers {@link MemoryNoteService#setPinned} (pin/unpin) and
 * {@link MemoryNoteService#changeScope} (dedicated scope-change op). Mirrors
 * the setup style of {@link MemoryNoteDedupTtlScopeTest} but stays in its own
 * file per the "many small files" convention.
 */
@SuppressWarnings("unchecked")
class MemoryNotePinAndScopeTest {

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

    private MemoryNoteEntity note(String memoryId, MemoryScope scope, String privacy) {
        return MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(MemoryCategory.PROJECTS.name())
                .scope(scope.name())
                .title("Existing note")
                .privacy(privacy)
                .status("ACTIVE")
                .pinned(false)
                .tags(new java.util.ArrayList<>())
                .linkedEntities(new java.util.ArrayList<>())
                .frontmatter(new java.util.LinkedHashMap<>(java.util.Map.of("privacy", privacy)))
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    // ------------------------------------------------------------------ pin

    @Test
    void setPinnedTrueMarksNotePinned() {
        MemoryNoteEntity existing = note("mem-1", MemoryScope.USER_PROFILE, "local-only");
        when(repository.findById("mem-1")).thenReturn(Optional.of(existing));

        MemoryNoteEntity result = service.setPinned("mem-1", true);

        assertThat(result).isNotNull();
        assertThat(result.isPinned()).isTrue();
        assertThat(result.getUpdatedAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        verify(vaultWriter, never()).write(any());
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    void setPinnedFalseUnmarksNote() {
        MemoryNoteEntity existing = note("mem-1", MemoryScope.USER_PROFILE, "local-only");
        existing.setPinned(true);
        when(repository.findById("mem-1")).thenReturn(Optional.of(existing));

        MemoryNoteEntity result = service.setPinned("mem-1", false);

        assertThat(result.isPinned()).isFalse();
    }

    @Test
    void setPinnedReturnsNullWhenNoteMissing() {
        when(repository.findById("mem-x")).thenReturn(Optional.empty());

        MemoryNoteEntity result = service.setPinned("mem-x", true);

        assertThat(result).isNull();
    }

    // ------------------------------------------------------------- change scope

    @Test
    void changeScopeUpdatesScopeAndRewritesVault() {
        MemoryNoteEntity existing = note("mem-1", MemoryScope.PROJECT, "shared");
        when(repository.findById("mem-1")).thenReturn(Optional.of(existing));
        when(vaultWriter.write(any())).thenReturn("03_Memory/Projects/2026-01-01-existing-note.md");

        MemoryNoteEntity result = service.changeScope("mem-1", MemoryScope.SESSION);

        assertThat(result.getScope()).isEqualTo(MemoryScope.SESSION.name());
        assertThat(result.getVaultRelativePath()).isEqualTo("03_Memory/Projects/2026-01-01-existing-note.md");
        verify(vaultWriter).write(existing);
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    void changeScopeToFinanceReappliesLocalOnlyPrivacyGuard() {
        MemoryNoteEntity existing = note("mem-1", MemoryScope.PROJECT, "shared");
        when(repository.findById("mem-1")).thenReturn(Optional.of(existing));
        when(vaultWriter.write(any())).thenReturn(null);

        MemoryNoteEntity result = service.changeScope("mem-1", MemoryScope.FINANCE);

        assertThat(result.getPrivacy()).isEqualTo("local-only");
        assertThat(result.getFrontmatter().get("privacy")).isEqualTo("local-only");
    }

    @Test
    void changeScopeToHealthReappliesLocalOnlyPrivacyGuard() {
        MemoryNoteEntity existing = note("mem-1", MemoryScope.PROJECT, "public");
        when(repository.findById("mem-1")).thenReturn(Optional.of(existing));
        when(vaultWriter.write(any())).thenReturn(null);

        MemoryNoteEntity result = service.changeScope("mem-1", MemoryScope.HEALTH);

        assertThat(result.getPrivacy()).isEqualTo("local-only");
    }

    @Test
    void changeScopeToNonSensitiveScopeLeavesPrivacyUntouched() {
        MemoryNoteEntity existing = note("mem-1", MemoryScope.FINANCE, "local-only");
        when(repository.findById("mem-1")).thenReturn(Optional.of(existing));
        when(vaultWriter.write(any())).thenReturn(null);

        MemoryNoteEntity result = service.changeScope("mem-1", MemoryScope.PROJECT);

        assertThat(result.getScope()).isEqualTo(MemoryScope.PROJECT.name());
        assertThat(result.getPrivacy()).isEqualTo("local-only");
    }

    @Test
    void changeScopeReturnsNullWhenNoteMissing() {
        when(repository.findById("mem-x")).thenReturn(Optional.empty());

        MemoryNoteEntity result = service.changeScope("mem-x", MemoryScope.PROJECT);

        assertThat(result).isNull();
    }

    @Test
    void changeScopeRejectsNullScope() {
        assertThatThrownBy(() -> service.changeScope("mem-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findById(any());
    }
}
