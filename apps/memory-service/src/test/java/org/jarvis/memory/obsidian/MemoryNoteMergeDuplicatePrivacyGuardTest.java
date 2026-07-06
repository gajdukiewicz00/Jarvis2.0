package org.jarvis.memory.obsidian;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bug hunt #15 (memory-service — logic-error): {@code mergeDuplicate()} (the
 * default MERGE dedup-strategy path in {@code writeWithOutcome}) never
 * re-evaluated {@code MemoryScope.isSensitiveScope}/{@code resolvePrivacy},
 * so a resubmission that reclassifies an existing duplicate note's scope to
 * FINANCE/HEALTH was silently folded in without forcing local-only privacy —
 * unlike {@code update()}/{@code changeScope()}, which both explicitly
 * re-apply the guard. That let a now finance/health-sensitive note keep an
 * overly-permissive privacy value and flow to an external/remote LLM
 * provider via {@code MemoryService#privacyAllowed}.
 */
@SuppressWarnings("unchecked")
class MemoryNoteMergeDuplicatePrivacyGuardTest {

    private MemoryNoteRepository repository;
    private MemoryNoteService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        ObsidianVaultWriter vaultWriter = mock(ObsidianVaultWriter.class);
        ObsidianMarkdownRenderer renderer = mock(ObsidianMarkdownRenderer.class);
        MemoryEmbeddingClient embeddingClient = mock(MemoryEmbeddingClient.class);
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> noopProvider = mock(ObjectProvider.class);
        when(noopProvider.getIfAvailable()).thenReturn(null);
        service = new MemoryNoteService(repository, vaultWriter, renderer, embeddingClient, noopProvider);
        ReflectionTestUtils.setField(service, "metrics", new MemoryMetrics(new SimpleMeterRegistry()));
        when(repository.save(any(MemoryNoteEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MemoryNoteEntity existingPublicProjectNote() {
        return MemoryNoteEntity.builder()
                .memoryId("mem-dup")
                .category(MemoryCategory.PROJECTS.name())
                .scope(MemoryScope.PROJECT.name())
                .title("Q3 numbers")
                .body("shared body text")
                .source("jarvis")
                .privacy("public")
                .status("ACTIVE")
                .tags(new java.util.ArrayList<>())
                .linkedEntities(new java.util.ArrayList<>())
                .frontmatter(new java.util.LinkedHashMap<>())
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    @Test
    void mergeDuplicateForcesLocalOnlyPrivacyWhenResubmissionReclassifiesToFinance() {
        MemoryNoteEntity existing = existingPublicProjectNote();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Q3 numbers")
                .body("shared body text")
                .scope(MemoryScope.FINANCE)
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.merged()).isTrue();
        assertThat(outcome.note().getScope())
                .as("dedup merge must adopt the reclassified sensitive scope")
                .isEqualTo(MemoryScope.FINANCE.name());
        assertThat(outcome.note().getPrivacy())
                .as("dedup merge must force local-only privacy for a now-FINANCE-scoped note")
                .isEqualTo("local-only");
    }

    @Test
    void mergeDuplicateForcesLocalOnlyPrivacyWhenResubmissionReclassifiesToHealth() {
        MemoryNoteEntity existing = existingPublicProjectNote();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Q3 numbers")
                .body("shared body text")
                .scope(MemoryScope.HEALTH)
                .privacy("shared")
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.note().getScope()).isEqualTo(MemoryScope.HEALTH.name());
        assertThat(outcome.note().getPrivacy()).isEqualTo("local-only");
    }

    @Test
    void mergeDuplicateLeavesScopeAndPrivacyUntouchedWhenNeitherRequested() {
        MemoryNoteEntity existing = existingPublicProjectNote();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Q3 numbers")
                .body("shared body text")
                .tags(List.of("extra-tag"))
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.note().getScope()).isEqualTo(MemoryScope.PROJECT.name());
        assertThat(outcome.note().getPrivacy()).isEqualTo("public");
        assertThat(outcome.note().getTags()).contains("extra-tag");
    }

    @Test
    void mergeDuplicateHonoursExplicitNonSensitiveScopeAndPrivacyChange() {
        MemoryNoteEntity existing = existingPublicProjectNote();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));

        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .title("Q3 numbers")
                .body("shared body text")
                .scope(MemoryScope.USER_PROFILE)
                .privacy("shared")
                .build();

        MemoryNoteService.WriteOutcome outcome = service.writeWithOutcome(request);

        assertThat(outcome.note().getScope()).isEqualTo(MemoryScope.USER_PROFILE.name());
        assertThat(outcome.note().getPrivacy()).isEqualTo("shared");
    }
}
