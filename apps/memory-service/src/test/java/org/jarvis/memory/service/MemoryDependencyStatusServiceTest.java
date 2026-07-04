package org.jarvis.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryDependencyStatusServiceTest {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingClient embeddingClient;
    private MemoryDependencyStatusService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        embeddingClient = mock(EmbeddingClient.class);
        service = new MemoryDependencyStatusService(jdbcTemplate, embeddingClient);
    }

    @Test
    void checkDependenciesReportsHealthyWhenEverythingIsUp() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class))
                .thenReturn(true);
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(true, "healthy", "model-x", 384, null));

        MemoryDependencyStatusService.DependencyStatus status = service.checkDependencies();

        assertThat(status.status()).isEqualTo("healthy");
        assertThat(status.database()).isEqualTo("up");
        assertThat(status.pgvector()).isEqualTo("available");
        assertThat(status.embeddingService()).isEqualTo("up");
        assertThat(status.embeddingModel()).isEqualTo("model-x");
        assertThat(status.embeddingDimension()).isEqualTo(384);
        assertThat(status.embeddingError()).isNull();
    }

    @Test
    void checkDependenciesReportsDegradedWhenDatabaseQueryThrows() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(true, "healthy", "model-x", 384, null));

        MemoryDependencyStatusService.DependencyStatus status = service.checkDependencies();

        assertThat(status.status()).isEqualTo("degraded");
        assertThat(status.database()).isEqualTo("down");
        // checkPgvector() is short-circuited (databaseUp && checkPgvector()) so the
        // vector-extension query is never issued once the base connectivity check fails.
        assertThat(status.pgvector()).isEqualTo("missing");
    }

    @Test
    void checkDependenciesReportsDegradedWhenDatabaseReturnsUnexpectedValue() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(0);
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(true, "healthy", "model-x", 384, null));

        MemoryDependencyStatusService.DependencyStatus status = service.checkDependencies();

        assertThat(status.database()).isEqualTo("down");
    }

    @Test
    void checkDependenciesReportsPgvectorMissingWhenExtensionQueryThrows() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("no ext"));
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(true, "healthy", "model-x", 384, null));

        MemoryDependencyStatusService.DependencyStatus status = service.checkDependencies();

        assertThat(status.pgvector()).isEqualTo("missing");
        assertThat(status.status()).isEqualTo("degraded");
    }

    @Test
    void checkDependenciesReportsDegradedWhenEmbeddingServiceDown() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class))
                .thenReturn(true);
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(false, "error", null, null, "timeout"));

        MemoryDependencyStatusService.DependencyStatus status = service.checkDependencies();

        assertThat(status.status()).isEqualTo("degraded");
        assertThat(status.embeddingService()).isEqualTo("down");
        assertThat(status.embeddingError()).isEqualTo("timeout");
    }

    @Test
    void verifyOrThrowSucceedsWhenAllDependenciesUp() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class))
                .thenReturn(true);
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(true, "healthy", "model-x", 384, null));

        service.verifyOrThrow();
    }

    @Test
    void verifyOrThrowIncludesEmbeddingErrorDetailWhenPresent() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class))
                .thenReturn(true);
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(false, "error", null, null, "connection refused"));

        assertThatThrownBy(() -> service.verifyOrThrow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding-service=down")
                .hasMessageContaining("connection refused");
    }

    @Test
    void verifyOrThrowOmitsParentheticalWhenEmbeddingErrorIsNull() {
        when(jdbcTemplate.queryForObject("select 1", Integer.class))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        when(embeddingClient.getHealth())
                .thenReturn(new EmbeddingClient.EmbeddingServiceHealth(true, "healthy", "model-x", 384, null));

        assertThatThrownBy(() -> service.verifyOrThrow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database=down")
                .hasMessageContaining("pgvector=missing")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("("));
    }
}
