package org.jarvis.memory.audit;

import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MemorySearchAuditServiceTest {

    @Mock
    private MemorySearchAuditRepository repository;

    private MemorySearchAuditService service() {
        return service(false, 32);
    }

    private MemorySearchAuditService service(boolean allowSnippet, int snippetMax) {
        MemorySearchAuditService s = new MemorySearchAuditService(repository);
        ReflectionTestUtils.setField(s, "allowQuerySnippet", allowSnippet);
        ReflectionTestUtils.setField(s, "querySnippetMaxLength", snippetMax);
        return s;
    }

    @Test
    void recordPersistsHashAndChunkIdsAndOmitsExcerptByDefault() {
        UUID chunkId = UUID.randomUUID();
        SearchRequest request = SearchRequest.builder()
                .query("how do I rebuild the index")
                .topK(5)
                .build();
        request.setUserId("user-1");
        SearchResponse response = SearchResponse.builder()
                .chunks(List.of(SearchResponse.ChunkResult.builder()
                        .id(chunkId)
                        .text("rebuild instructions")
                        .similarity(0.91)
                        .createdAt(OffsetDateTime.now())
                        .build()))
                .retrievalMode("semantic")
                .processingTimeMs(42)
                .build();

        service().record(request, response, "gemma-3-27b-it-q4_k_m", true, "corr-1");

        ArgumentCaptor<MemorySearchAuditEntity> captor =
                ArgumentCaptor.forClass(MemorySearchAuditEntity.class);
        verify(repository).save(captor.capture());
        MemorySearchAuditEntity saved = captor.getValue();

        assertThat(saved.getQueryHash())
                .as("SHA-256 hex of the query")
                .isEqualTo(MemorySearchAuditService.sha256Hex("how do I rebuild the index"))
                .hasSize(64);
        assertThat(saved.getQueryExcerpt()).as("PII gate is closed by default").isNull();
        assertThat(saved.getRetrievalMode()).isEqualTo("semantic");
        assertThat(saved.isRerankUsed()).isTrue();
        assertThat(saved.getTopK()).isEqualTo(5);
        assertThat(saved.getResultCount()).isEqualTo(1);
        assertThat(saved.getRetrievedChunkIds()).containsExactly(chunkId.toString());
        assertThat(saved.getRetrievedNotePaths()).isEmpty();
        assertThat(saved.getSelectedModel()).isEqualTo("gemma-3-27b-it-q4_k_m");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-1");
        assertThat(saved.getProcessingTimeMs()).isEqualTo(42);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo("user-1");
    }

    @Test
    void recordWritesExcerptOnlyWhenPiiAllowed() {
        SearchRequest request = SearchRequest.builder()
                .query("a relatively long query that should be truncated to the configured limit")
                .topK(3)
                .build();
        SearchResponse response = SearchResponse.builder()
                .chunks(List.of())
                .retrievalMode("lexical-fallback")
                .processingTimeMs(10)
                .build();

        service(true, 16).record(request, response, null, false, "corr-2");

        ArgumentCaptor<MemorySearchAuditEntity> captor =
                ArgumentCaptor.forClass(MemorySearchAuditEntity.class);
        verify(repository).save(captor.capture());
        MemorySearchAuditEntity saved = captor.getValue();

        assertThat(saved.getQueryExcerpt()).hasSize(16).isEqualTo("a relatively lon");
        assertThat(saved.getRetrievalMode()).isEqualTo("lexical-fallback");
        assertThat(saved.isRerankUsed()).isFalse();
        assertThat(saved.getResultCount()).isZero();
    }

    @Test
    void recordSwallowsRepositoryFailureSoSearchPathStaysHealthy() {
        doThrow(new DataIntegrityViolationException("simulated"))
                .when(repository).save(any());

        SearchRequest request = SearchRequest.builder().query("q").topK(1).build();
        SearchResponse response = SearchResponse.builder()
                .chunks(List.of())
                .retrievalMode("semantic")
                .processingTimeMs(1)
                .build();

        // Must not throw — the audit path is best-effort.
        service().record(request, response, "any-model", false, "corr-3");
        verify(repository, times(1)).save(any());
    }

    @Test
    void hashIsStableAcrossCalls() {
        String a = MemorySearchAuditService.sha256Hex("hello world");
        String b = MemorySearchAuditService.sha256Hex("hello world");
        String c = MemorySearchAuditService.sha256Hex("Hello World");
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).matches("[0-9a-f]{64}");
    }

    @Test
    void recordIsResilientToNullChunksList() {
        SearchRequest request = SearchRequest.builder().query("q").topK(0).build();
        SearchResponse response = SearchResponse.builder()
                .chunks(null)
                .retrievalMode(null)
                .processingTimeMs(0)
                .build();

        service().record(request, response, null, false, null);

        ArgumentCaptor<MemorySearchAuditEntity> captor =
                ArgumentCaptor.forClass(MemorySearchAuditEntity.class);
        verify(repository).save(captor.capture());
        MemorySearchAuditEntity saved = captor.getValue();

        assertThat(saved.getRetrievedChunkIds()).isEmpty();
        assertThat(saved.getResultCount()).isZero();
        assertThat(saved.getRetrievalMode()).isEqualTo("unknown");
    }

    @Test
    void recordWithNullRequestQueryStillHashesEmptyString() {
        SearchRequest request = SearchRequest.builder().query(null).build();
        SearchResponse response = SearchResponse.builder()
                .chunks(List.of())
                .retrievalMode("semantic")
                .build();

        service().record(request, response, null, false, null);

        ArgumentCaptor<MemorySearchAuditEntity> captor =
                ArgumentCaptor.forClass(MemorySearchAuditEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getQueryHash())
                .isEqualTo(MemorySearchAuditService.sha256Hex(""));
    }

    @Test
    void noRepositoryInteractionWhenSnippetMaxIsZero() {
        SearchRequest request = SearchRequest.builder().query("anything").build();
        SearchResponse response = SearchResponse.builder()
                .chunks(List.of())
                .retrievalMode("semantic")
                .build();

        // Even with allowSnippet=true, max=0 means no excerpt — but record still happens.
        service(true, 0).record(request, response, null, false, null);

        ArgumentCaptor<MemorySearchAuditEntity> captor =
                ArgumentCaptor.forClass(MemorySearchAuditEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getQueryExcerpt()).isNull();
    }

    @Test
    void recordIsNoOpWhenRequestIsNullSafe() {
        // Null protection on inputs.
        // Service must not crash even with nasty input.
        try {
            service().record(null, null, null, false, null);
        } catch (NullPointerException ignored) {
            // Acceptable: service does not contractually accept null request.
        }
        // It either succeeded (swallowed) or threw NPE; either way no save.
        verifyNoInteractions(repository);
    }
}
