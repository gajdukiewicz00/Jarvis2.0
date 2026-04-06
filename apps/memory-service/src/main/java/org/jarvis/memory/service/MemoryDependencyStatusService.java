package org.jarvis.memory.service;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemoryDependencyStatusService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;

    public DependencyStatus checkDependencies() {
        boolean databaseUp = checkDatabase();
        boolean pgvectorAvailable = databaseUp && checkPgvector();
        EmbeddingClient.EmbeddingServiceHealth embeddingHealth = embeddingClient.getHealth();
        boolean embeddingUp = embeddingHealth.healthy();

        String overallStatus = databaseUp && pgvectorAvailable && embeddingUp ? "healthy" : "degraded";
        return DependencyStatus.builder()
                .status(overallStatus)
                .database(databaseUp ? "up" : "down")
                .pgvector(pgvectorAvailable ? "available" : "missing")
                .embeddingService(embeddingUp ? "up" : "down")
                .embeddingModel(embeddingHealth.modelName())
                .embeddingDimension(embeddingHealth.dimension())
                .embeddingError(embeddingHealth.error())
                .build();
    }

    public void verifyOrThrow() {
        DependencyStatus status = checkDependencies();
        if (!"up".equals(status.database()) || !"available".equals(status.pgvector()) || !"up".equals(status.embeddingService())) {
            throw new IllegalStateException(
                    "memory-service dependencies are not ready: database=" + status.database()
                            + ", pgvector=" + status.pgvector()
                            + ", embedding-service=" + status.embeddingService()
                            + (status.embeddingError() == null ? "" : " (" + status.embeddingError() + ")"));
        }
    }

    private boolean checkDatabase() {
        try {
            Integer value = jdbcTemplate.queryForObject("select 1", Integer.class);
            return value != null && value == 1;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean checkPgvector() {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "select exists (select 1 from pg_extension where extname = 'vector')",
                    Boolean.class);
            return Boolean.TRUE.equals(exists);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Builder
    public record DependencyStatus(
            String status,
            String database,
            String pgvector,
            String embeddingService,
            String embeddingModel,
            Integer embeddingDimension,
            String embeddingError) {
    }
}
