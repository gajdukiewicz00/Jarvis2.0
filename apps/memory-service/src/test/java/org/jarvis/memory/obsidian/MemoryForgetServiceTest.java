package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryForgetServiceTest {

    private MemoryNoteRepository repository;
    private ObsidianVaultWriter vaultWriter;
    private MemoryForgetService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        vaultWriter = mock(ObsidianVaultWriter.class);
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> noopProvider =
                mock(ObjectProvider.class);
        when(noopProvider.getIfAvailable()).thenReturn(null);
        service = new MemoryForgetService(repository, vaultWriter, noopProvider);
    }

    private MemoryNoteEntity active() {
        return MemoryNoteEntity.builder()
                .memoryId("mem-1")
                .category(MemoryCategory.HEALTH.name())
                .title("blood pressure baseline")
                .summary("private health summary")
                .body("private body")
                .vaultRelativePath("03_Memory/Health/2026-05-01-bp.md")
                .source("jarvis")
                .privacy("local-only")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.7"))
                .tags(java.util.List.of("health"))
                .linkedEntities(java.util.List.of())
                .frontmatter(new java.util.HashMap<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void forgetMissingReturnsNotFound() {
        when(repository.findById("mem-x")).thenReturn(Optional.empty());
        var result = service.forget("mem-x", "owner", "no-reason");
        assertThat(result.removed()).isFalse();
        assertThat(result.reason()).isEqualTo("not-found");
    }

    @Test
    void forgetActiveTombstonesAndSoftDeletes() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));
        when(vaultWriter.writeTombstone(any(), any(), any()))
                .thenReturn("06_System/deleted-memory-log/2026-05-01/mem-1.md");

        var result = service.forget("mem-1", "owner", "user requested forget");

        assertThat(result.removed()).isTrue();
        assertThat(result.tombstonePath()).startsWith("06_System/deleted-memory-log/");
        verify(vaultWriter, times(1)).writeTombstone(any(), any(), any());
        verify(vaultWriter, times(1)).removeIfPresent("03_Memory/Health/2026-05-01-bp.md");
        // Soft-delete contract: status flipped, body/summary cleared, embedding nulled
        assertThat(n.getStatus()).isEqualTo("DELETED");
        assertThat(n.getBody()).isNull();
        assertThat(n.getSummary()).isNull();
        assertThat(n.getEmbedding()).isNull();
        assertThat(n.getDeletedAt()).isNotNull();
        assertThat(n.getVaultRelativePath()).startsWith("06_System/deleted-memory-log/");
        assertThat(n.getFrontmatter()).containsEntry("status", "deleted");
        verify(repository, times(1)).save(n);
    }

    @Test
    void forgetAlreadyDeletedIsIdempotent() {
        MemoryNoteEntity n = active();
        n.setStatus("DELETED");
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));
        var result = service.forget("mem-1", "owner", "redo");
        assertThat(result.removed()).isTrue();
        assertThat(result.reason()).isEqualTo("already-deleted");
        verify(vaultWriter, times(0)).writeTombstone(any(), any(), any());
    }

    @Test
    void forgetSurvivesTombstoneFailure() {
        MemoryNoteEntity n = active();
        when(repository.findById("mem-1")).thenReturn(Optional.of(n));
        when(vaultWriter.writeTombstone(any(), any(), any())).thenReturn(null);
        var result = service.forget("mem-1", "owner", "no-vault");
        assertThat(result.removed()).isTrue();
        assertThat(n.getStatus()).isEqualTo("DELETED");
        assertThat(n.getBody()).isNull();
    }
}
