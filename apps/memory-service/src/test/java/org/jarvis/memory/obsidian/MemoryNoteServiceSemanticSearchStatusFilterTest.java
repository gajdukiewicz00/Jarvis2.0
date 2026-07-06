package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug hunt #38 (memory-service — logic-error): the raw JDBC pgvector
 * semantic-search query in {@code searchUnified()} excluded deleted notes
 * via {@code status <> 'deleted'} (lowercase), but {@code
 * MemoryForgetService.forget()} persists the status as uppercase {@code
 * 'DELETED'}. Case-sensitive SQL comparison means the guard was dead code —
 * it never actually excluded anything.
 */
@SuppressWarnings("unchecked")
class MemoryNoteServiceSemanticSearchStatusFilterTest {

    private MemoryNoteRepository repository;
    private MemoryEmbeddingClient embeddingClient;
    private MemoryNoteService service;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        ObsidianVaultWriter vaultWriter = mock(ObsidianVaultWriter.class);
        ObsidianMarkdownRenderer renderer = mock(ObsidianMarkdownRenderer.class);
        embeddingClient = mock(MemoryEmbeddingClient.class);
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> noopProvider = mock(ObjectProvider.class);
        when(noopProvider.getIfAvailable()).thenReturn(null);
        service = new MemoryNoteService(repository, vaultWriter, renderer, embeddingClient, noopProvider);
        jdbcTemplate = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void semanticSearchSqlExcludesUppercaseDeletedStatusNotLowercase() {
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of());
        when(repository.searchByText(any(), any())).thenReturn(List.of());

        service.searchUnified("find my forgotten note", 5);

        org.mockito.ArgumentCaptor<String> sqlCaptor = forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(Object.class), any(Object.class), any(Object.class));

        assertThat(sqlCaptor.getValue())
                .as("semantic-search SQL must exclude the actually-persisted uppercase 'DELETED' status")
                .contains("'DELETED'")
                .doesNotContain("'deleted'");
    }
}
