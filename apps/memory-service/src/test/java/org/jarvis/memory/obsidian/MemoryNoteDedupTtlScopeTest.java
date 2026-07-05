package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jarvis.memory.exception.DuplicateMemoryException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Roadmap P1 #9 — covers the ingest-time dedup, typed-scope, TTL/expiry and
 * review-queue additions to {@link MemoryNoteService}. Mirrors the setup
 * style of {@link MemoryNoteServiceExtraTest} but stays in its own file per
 * the "many small files" convention.
 */
@SuppressWarnings("unchecked")
class MemoryNoteDedupTtlScopeTest {

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

    private MemoryNoteEntity duplicate() {
        return MemoryNoteEntity.builder()
                .memoryId("mem-dup")
                .category(MemoryCategory.PROJECTS.name())
                .scope(MemoryScope.USER_PROFILE.name())
                .title("Existing note")
                .body("existing body")
                .source("jarvis")
                .privacy("local-only")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.40"))
                .tags(new java.util.ArrayList<>(List.of("a")))
                .linkedEntities(new java.util.ArrayList<>())
                .frontmatter(new java.util.LinkedHashMap<>())
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    // ---------------------------------------------------------------- dedup

    @Test
    void writeMergesIntoExistingNoteOnContentHashMatch() {
        MemoryNoteEntity existing = duplicate();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Existing note")
                .body("existing body")
                .tags(List.of("b"))
                .linkedEntities(List.of("x"))
                .confidence(new BigDecimal("0.90"))
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.merged()).isTrue();
        assertThat(outcome.note().getMemoryId()).isEqualTo("mem-dup");
        assertThat(outcome.note().getTags()).containsExactlyInAnyOrder("a", "b");
        assertThat(outcome.note().getLinkedEntities()).containsExactly("x");
        assertThat(outcome.note().getConfidence()).isEqualByComparingTo("0.90");
        assertThat(outcome.note().getFrontmatter()).containsKey("dedup_merged_at");
        assertThat(outcome.note().getUpdatedAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        verify(vaultWriter, never()).write(any());
        verify(embeddingClient, never()).embed(any());
        verify(repository, times(1)).save(existing);
    }

    @Test
    void writeKeepsHigherExistingConfidenceOnMerge() {
        MemoryNoteEntity existing = duplicate();
        existing.setConfidence(new BigDecimal("0.95"));
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Existing note")
                .body("existing body")
                .confidence(new BigDecimal("0.10"))
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.note().getConfidence()).isEqualByComparingTo("0.95");
    }

    @Test
    void writeRejectsDuplicateWhenStrategyIsReject() {
        MemoryNoteEntity existing = duplicate();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));
        MemoryDedupProperties props = new MemoryDedupProperties();
        props.setStrategy(MemoryDedupProperties.DedupStrategy.REJECT);
        ReflectionTestUtils.setField(service, "dedupProperties", props);

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Existing note")
                .body("existing body")
                .build();

        assertThatThrownBy(() -> service.writeWithOutcome(request))
                .isInstanceOf(DuplicateMemoryException.class)
                .hasMessageContaining("mem-dup");
        verify(repository, never()).save(any());
    }

    @Test
    void writeSkipsDedupCheckWhenDisabled() {
        MemoryDedupProperties props = new MemoryDedupProperties();
        props.setEnabled(false);
        ReflectionTestUtils.setField(service, "dedupProperties", props);
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Brand new")
                .body("brand new body")
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.merged()).isFalse();
        verify(repository, never()).findFirstByContentHashAndStatusOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void writeCreatesNewNoteWhenNoDuplicateFound() {
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Totally new")
                .body("totally new body")
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.merged()).isFalse();
        assertThat(outcome.note().getMemoryId()).startsWith("mem-");
        assertThat(outcome.note().getContentHash()).isNotBlank();
    }

    // ---------------------------------------------------------------- scope

    @Test
    void writeDefaultsScopeToUserProfileWhenAbsent() {
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);

        MemoryNoteEntity result = service.write(MemoryNoteRequest.builder().title("No scope given").build());

        assertThat(result.getScope()).isEqualTo(MemoryScope.USER_PROFILE.name());
    }

    @Test
    void writeHonoursExplicitScope() {
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);

        MemoryNoteEntity result = service.write(MemoryNoteRequest.builder()
                .title("Scoped note")
                .scope(MemoryScope.FINANCE)
                .build());

        assertThat(result.getScope()).isEqualTo(MemoryScope.FINANCE.name());
    }

    @Test
    void listWithScopeDelegatesToCategoryScopeSearch() {
        when(repository.searchByCategoryAndScope(eq(MemoryCategory.HEALTH.name()), eq(MemoryScope.HEALTH.name()),
                eq("ACTIVE"), any())).thenReturn(List.of());

        service.list(MemoryCategory.HEALTH, MemoryScope.HEALTH, 10);

        verify(repository).searchByCategoryAndScope(eq(MemoryCategory.HEALTH.name()), eq(MemoryScope.HEALTH.name()),
                eq("ACTIVE"), argThat(p -> p.getPageSize() == 10));
    }

    @Test
    void exportAllWithScopeDelegatesToCategoryScopeSearch() {
        when(repository.searchByCategoryAndScope(eq((String) null), eq(MemoryScope.FINANCE.name()),
                eq("ACTIVE"), any())).thenReturn(List.of());

        List<MemoryNoteEntity> result = service.exportAll(MemoryScope.FINANCE);

        assertThat(result).isEmpty();
        verify(repository).searchByCategoryAndScope(eq((String) null), eq(MemoryScope.FINANCE.name()),
                eq("ACTIVE"), any());
    }

    @Test
    void exportAllWithNullScopeFallsBackToPlainExportAll() {
        when(repository.search(eq((String) null), eq("ACTIVE"), any())).thenReturn(List.of());

        List<MemoryNoteEntity> result = service.exportAll(null);

        assertThat(result).isEmpty();
        verify(repository, never()).searchByCategoryAndScope(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ TTL

    @Test
    void writeHonoursExplicitExpiresAt() {
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);
        Instant expiry = Instant.parse("2030-01-01T00:00:00Z");

        MemoryNoteEntity result = service.write(MemoryNoteRequest.builder()
                .title("Expiring note")
                .expiresAt(expiry)
                .build());

        assertThat(result.getExpiresAt()).isEqualTo(expiry);
    }

    @Test
    void writeDerivesExpiresAtFromTtlSecondsWhenExplicitAbsent() {
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);
        Instant before = Instant.now();

        MemoryNoteEntity result = service.write(MemoryNoteRequest.builder()
                .title("Temporary note")
                .ttlSeconds(60L)
                .build());

        assertThat(result.getExpiresAt()).isAfter(before.plusSeconds(59));
        assertThat(result.getExpiresAt()).isBefore(before.plusSeconds(120));
    }

    @Test
    void writeLeavesExpiresAtNullWhenNeitherProvided() {
        when(vaultWriter.write(any())).thenReturn(null);
        when(embeddingClient.embed(any())).thenReturn(null);

        MemoryNoteEntity result = service.write(MemoryNoteRequest.builder().title("No TTL").build());

        assertThat(result.getExpiresAt()).isNull();
    }

    // --------------------------------------------------------- review queue

    @Test
    void pendingReviewDelegatesWithDefaultThreshold() {
        when(repository.findPendingReview(eq((String) null), eq(new BigDecimal("0.50")), any()))
                .thenReturn(List.of());

        service.pendingReview(null, 25);

        verify(repository).findPendingReview(eq((String) null), eq(new BigDecimal("0.50")),
                argThat(p -> p.getPageSize() == 25));
    }

    @Test
    void pendingReviewPassesScopeThrough() {
        when(repository.findPendingReview(eq(MemoryScope.PROJECT.name()), any(BigDecimal.class), any()))
                .thenReturn(List.of());

        service.pendingReview(MemoryScope.PROJECT, 10);

        verify(repository).findPendingReview(eq(MemoryScope.PROJECT.name()), any(BigDecimal.class), any());
    }

    private static Pageable argThat(java.util.function.Predicate<Pageable> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}
