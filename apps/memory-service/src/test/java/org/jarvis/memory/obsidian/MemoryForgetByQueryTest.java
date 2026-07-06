package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Roadmap #11 — "forget by query" backing a voice "забудь это" command: the
 * caller resolves the utterance to a text query and/or scope filter, and
 * {@link MemoryForgetService#forgetByQuery} deletes every ACTIVE match via
 * the same {@link MemoryForgetService#forget} path (tombstone + soft-delete
 * + audit) used by single-note forget, returning a count.
 */
@SuppressWarnings("unchecked")
class MemoryForgetByQueryTest {

    private MemoryNoteRepository repository;
    private ObsidianVaultWriter vaultWriter;
    private MemoryForgetService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        vaultWriter = mock(ObsidianVaultWriter.class);
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> noopProvider =
                mock(ObjectProvider.class);
        when(noopProvider.getIfAvailable()).thenReturn(null);
        service = new MemoryForgetService(repository, vaultWriter, noopProvider);
    }

    private MemoryNoteEntity active(String memoryId, MemoryScope scope) {
        return MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(MemoryCategory.PROJECTS.name())
                .scope(scope.name())
                .title("note " + memoryId)
                .status("ACTIVE")
                .tags(List.of())
                .linkedEntities(List.of())
                .frontmatter(new java.util.HashMap<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void forgetByQueryDeletesEachTextMatchAndReturnsCount() {
        MemoryNoteEntity a = active("mem-a", MemoryScope.USER_PROFILE);
        MemoryNoteEntity b = active("mem-b", MemoryScope.USER_PROFILE);
        when(repository.searchByText(eq("dentist"), any())).thenReturn(List.of(a, b));
        when(repository.findById("mem-a")).thenReturn(Optional.of(a));
        when(repository.findById("mem-b")).thenReturn(Optional.of(b));

        MemoryForgetService.ForgetByQueryResult result =
                service.forgetByQuery("dentist", null, "owner", "voice forget");

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.memoryIds()).containsExactlyInAnyOrder("mem-a", "mem-b");
        assertThat(a.getStatus()).isEqualTo("DELETED");
        assertThat(b.getStatus()).isEqualTo("DELETED");
    }

    @Test
    void forgetByQueryNarrowsTextMatchesByScopeWhenBothGiven() {
        MemoryNoteEntity matchingScope = active("mem-a", MemoryScope.HEALTH);
        MemoryNoteEntity otherScope = active("mem-b", MemoryScope.PROJECT);
        when(repository.searchByText(eq("checkup"), any())).thenReturn(List.of(matchingScope, otherScope));
        when(repository.findById("mem-a")).thenReturn(Optional.of(matchingScope));

        MemoryForgetService.ForgetByQueryResult result =
                service.forgetByQuery("checkup", MemoryScope.HEALTH, "owner", "voice forget");

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.memoryIds()).containsExactly("mem-a");
        verify(repository, never()).findById("mem-b");
    }

    @Test
    void forgetByQueryFallsBackToScopeOnlyLookupWhenQueryBlank() {
        MemoryNoteEntity note = active("mem-c", MemoryScope.TEMPORARY);
        when(repository.searchByCategoryAndScope(eq((String) null), eq(MemoryScope.TEMPORARY.name()),
                eq("ACTIVE"), any())).thenReturn(List.of(note));
        when(repository.findById("mem-c")).thenReturn(Optional.of(note));

        MemoryForgetService.ForgetByQueryResult result =
                service.forgetByQuery("   ", MemoryScope.TEMPORARY, "owner", "cleanup");

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.memoryIds()).containsExactly("mem-c");
        verify(repository, never()).searchByText(anyString(), any());
    }

    @Test
    void forgetByQueryReturnsZeroCountWhenNoMatches() {
        when(repository.searchByText(eq("nothing"), any())).thenReturn(List.of());

        MemoryForgetService.ForgetByQueryResult result =
                service.forgetByQuery("nothing", null, "owner", "voice forget");

        assertThat(result.count()).isZero();
        assertThat(result.memoryIds()).isEmpty();
    }

    @Test
    void forgetByQueryRejectsWhenNeitherQueryNorScopeGiven() {
        assertThatThrownBy(() -> service.forgetByQuery(null, null, "owner", "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.forgetByQuery("  ", null, "owner", "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
